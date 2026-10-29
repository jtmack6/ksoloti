#!/usr/bin/env python3
"""Ksoloti MCP server: object-library lookup, .axp authoring/validation, hand-off to the patcher.

Config (env):
  KSOLOTI_LIBS  library root holding axoloti-factory/, ksoloti-objects/, ... (default: newest ~/ksoloti/<ver>)
  KSOLOTI_JAR   path to Ksoloti.jar for headless compile (default: <repo>/dist/Ksoloti.jar)
  JAVA          java executable (default: java)
"""
import os
import re
import socket
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from mcp.server.fastmcp import FastMCP

REPO = Path(__file__).resolve().parents[2]
SINGLE_INSTANCE_PORT = 55576  # Axoloti.java
LIB_NAMES = ["axoloti-factory", "axoloti-contrib", "ksoloti-objects", "ksoloti-contrib"]
SCALAR = {"frac32", "int32", "bool32"}


def _libs_root() -> Path:
    if os.environ.get("KSOLOTI_LIBS"):
        return Path(os.environ["KSOLOTI_LIBS"]).expanduser()
    home = Path.home() / "ksoloti"
    versions = sorted((p for p in home.iterdir() if p.is_dir()), key=lambda p: p.name) if home.exists() else []
    if not versions:
        sys.exit("No Ksoloti library folder found; set KSOLOTI_LIBS")
    return versions[-1]


def base_type(port_type: str) -> str:
    """'frac32.bipolar' -> 'frac32', 'frac32buffer.positive' -> 'frac32buffer'."""
    return port_type.split(".")[0]


# Element names a patch may use for a param instance (AxoObjectInstance.java @ElementListUnion).
PARAM_ELEMS = ["frac32.u.mapvsl", "frac32.s.mapvsl", "frac32.u.map", "frac32.s.map", "int32.small",
               "int32.hradio", "int32.vradio", "int32", "int2x16", "bin8", "bin12", "bin16", "bin32",
               "bool32.tgl", "bool32.mom"]


def _param_elem(param_type: str) -> str:
    """'frac32.s.map.pitch' -> 'frac32.s.map' (longest matching instance element)."""
    return max((e for e in PARAM_ELEMS if param_type == e or param_type.startswith(e + ".")), key=len, default=param_type)


# ---------------------------------------------------------------- library index

def _ports(parent, tag):
    el = parent.find(tag)
    if el is None:
        return []
    return [{"name": p.get("name"), "type": p.tag, "description": p.get("description", "")} for p in el]


def _attribs(parent):
    el = parent.find("attribs")
    if el is None:
        return []
    out = []
    for a in el:
        d = {"name": a.get("name"), "type": a.tag}
        d.update({k: v for k, v in a.items() if k != "name"})
        if a.tag == "combo":
            d["entries"] = [m.text for m in a.findall("MenuEntries/string")]
        out.append(d)
    return out


def _parse_axo(path: Path, prefix: str, lib: str):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        return [(None, {"error": f"{path}: {e}"})]
    out = []
    for obj in root:
        if not obj.tag.startswith("obj."):
            continue
        oid = obj.get("id") or path.stem
        typ = f"{prefix}/{oid}" if prefix else oid
        code = {c.tag: (c.text or "") for c in obj if c.tag.startswith("code.")}
        out.append((typ, {
            "type": typ, "library": lib, "file": str(path),
            "uuid": obj.get("uuid"), "sha": obj.get("sha"),
            "description": (obj.findtext("sDescription") or "").strip(),
            "author": obj.findtext("author"), "license": obj.findtext("license"),
            "help": obj.findtext("helpPatch"),
            "inlets": _ports(obj, "inlets"), "outlets": _ports(obj, "outlets"),
            "params": _ports(obj, "params"), "displays": _ports(obj, "displays"),
            "attribs": _attribs(obj), "code": code,
        }))
    return out


def _parse_axs(path: Path, typ: str, lib: str):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        return {"error": f"{path}: {e}"}
    inlets, outlets = [], []
    letter = {"f": "frac32", "i": "int32", "b": "bool32", "a": "frac32buffer", "string": "charptr32"}
    for o in root.findall("obj"):
        m = re.fullmatch(r"patch/(inlet|outlet) (\w+)", o.get("type", ""))
        if m:
            (inlets if m[1] == "inlet" else outlets).append(
                {"name": o.get("name"), "type": letter.get(m[2], m[2]), "description": ""})
    return {"type": typ, "library": lib, "file": str(path), "uuid": None, "subpatch": True,
            "description": (root.findtext("notes") or "").strip(),
            "inlets": inlets, "outlets": outlets, "params": [], "displays": [], "attribs": [], "code": {}}


class Library:
    def __init__(self, root: Path):
        self.root = root
        self.objects: dict[str, list[dict]] = {}  # type -> overloads
        self.by_uuid: dict[str, dict] = {}
        self.errors: list[str] = []
        for lib in LIB_NAMES:
            objdir = root / lib / "objects"
            if not objdir.is_dir():
                continue
            for p in objdir.rglob("*"):
                if p.suffix not in (".axo", ".axs"):
                    continue
                prefix = p.parent.relative_to(objdir).as_posix().replace(".", "")
                if p.suffix == ".axo":
                    for typ, d in _parse_axo(p, prefix, lib):
                        if typ is None:
                            self.errors.append(d["error"])
                            continue
                        self.objects.setdefault(typ, []).append(d)
                        if d["uuid"]:
                            self.by_uuid[d["uuid"]] = d
                else:
                    typ = f"{prefix}/{p.stem}" if prefix else p.stem
                    d = _parse_axs(p, typ, lib)
                    if "error" in d:
                        self.errors.append(d["error"])
                    else:
                        self.objects.setdefault(typ, []).append(d)

    def get(self, type_or_uuid: str):
        if type_or_uuid in self.objects:
            return self.objects[type_or_uuid]
        if type_or_uuid in self.by_uuid:
            return [self.by_uuid[type_or_uuid]]
        return []

    def resolve(self, typ: str, uuid: str | None):
        """Mimic the patcher: uuid first, then type path."""
        if uuid and uuid in self.by_uuid:
            return self.by_uuid[uuid]
        ov = self.objects.get(typ)
        return ov[0] if ov else None


LIB = Library(_libs_root())

# ------------------------------------------------------------------ MCP tools

mcp = FastMCP("ksoloti")


def _summary(d):
    sig = lambda ps: ", ".join(f"{p['name']}:{p['type']}" for p in ps)  # noqa: E731
    return f"{d['type']}  [{d['library']}]  in({sig(d['inlets'])}) -> out({sig(d['outlets'])})\n    {d['description'].splitlines()[0] if d['description'] else ''}"


@mcp.tool()
def search_objects(query: str, limit: int = 25) -> str:
    """Search the object library by type path, description, or port name. Space-separated terms must all match."""
    terms = query.lower().split()
    hits = []
    for typ, overloads in LIB.objects.items():
        d = overloads[0]
        hay = " ".join([typ, d["description"], *(p["name"] for p in d["inlets"] + d["outlets"] + d["params"])]).lower()
        if all(t in hay for t in terms):
            score = (0 if query.lower() in typ.lower() else 1, len(typ))
            hits.append((score, _summary(d)))
    hits.sort()
    return "\n".join(h for _, h in hits[:limit]) or "No matches."


@mcp.tool()
def describe_object(type_or_uuid: str, include_code: bool = False) -> str:
    """Full definition of an object: every overload with ports, params, attribs, displays. Optionally the C++ snippets."""
    ovs = LIB.get(type_or_uuid)
    if not ovs:
        return f"Unknown object '{type_or_uuid}'. Try search_objects."
    out = []
    for i, d in enumerate(ovs):
        out.append(f"=== {d['type']}  overload {i + 1}/{len(ovs)}  uuid={d['uuid']}  ({d['library']})")
        out.append(d["description"])
        if d.get("help"):
            out.append(f"help patch: {d['help']}")
        for sec in ("inlets", "outlets", "params", "displays"):
            if d[sec]:
                out.append(f"{sec}:")
                out += [f"  {p['name']}: {p['type']}  {p['description']}".rstrip() for p in d[sec]]
        if d["attribs"]:
            out.append("attribs (compile-time):")
            for a in d["attribs"]:
                extra = {k: v for k, v in a.items() if k not in ("name", "type")}
                out.append(f"  {a['name']}: {a['type']}  {extra if extra else ''}".rstrip())
        if include_code:
            for k, v in d["code"].items():
                out.append(f"--- {k}\n{v.strip()}")
        out.append(f"file: {d['file']}")
    return "\n".join(out)


EQUIV = {
    # Max / PD name -> Ksoloti type(s)
    "cycle~": ["osc/sine"], "osc~": ["osc/sine"], "phasor~": ["osc/phasor"], "saw~": ["osc/saw"],
    "rect~": ["osc/square"], "tri~": ["osc/tri"], "noise~": ["noise/white"], "pink~": ["noise/pink"],
    "*~": ["math/*", "mix/gain", "gain/vca"], "+~": ["math/+", "mix/mix 2"], "-~": ["math/-"],
    "dac~": ["audio/out stereo", "ksoloti/usbaudio/out stereo (USB firmware: codec out is not sent over USB)"], "adc~": ["audio/in stereo", "ksoloti/usbaudio/in stereo"], "ezdac~": ["audio/out stereo"],
    "lores~": ["filter/lp1", "filter/svf"], "svf~": ["filter/svf"], "biquad~": ["filter/biquad"],
    "reson~": ["filter/svf"], "onepole~": ["filter/lp1"], "hip~": ["filter/hp1"], "lop~": ["filter/lp1"],
    "delay~": ["delay/write", "delay/read"], "tapin~": ["delay/write"], "tapout~": ["delay/read"],
    "adsr~": ["env/adsr"], "line~": ["env/line"], "vline~": ["env/line"], "curve~": ["env/d"],
    "metro": ["timer/pulse", "lfo/square"], "counter": ["logic/counter"], "sel": ["logic/sel"],
    "select": ["logic/sel"], "gate": ["logic/gate"], "trigger": ["(not needed: no message ordering)"],
    "t": ["(not needed: no message ordering)"], "notein": ["midi/in/keyb", "midi/in/keyb zone"],
    "ctlin": ["midi/in/cc"], "bendin": ["midi/in/bend"], "noteout": ["midi/out/note"], "mtof": ["conv/mtof"],
    "ftom": ["conv/ftom"], "clip~": ["dist/clip"], "tanh~": ["dist/soft"], "scope~": ["disp/scope 128 b"],
    "meter~": ["disp/vu"], "number~": ["disp/dial p"], "snapshot~": ["conv/a2f"], "sig~": ["conv/f2a"],
    "tabread~": ["table/read"], "tabwrite~": ["table/write"], "buffer~": ["table/alloc 16b sdram"],
    "groove~": ["wave/play"], "sfplay~": ["wave/play"], "pack": ["(not needed: no lists)"],
    "loadbang": ["(use <attribs>; or env/d triggered by const)"], "random": ["rand/uniform f"],
    "poly~": ["(subpatch mode: polyphonic)"], "send~": ["patch/outlet a + patch/inlet a"],
    "receive~": ["patch/inlet a"], "s": ["(wire it; nets are free)"], "r": ["(wire it; nets are free)"],
}


@mcp.tool()
def find_equivalent(max_or_pd_object: str) -> str:
    """Suggest the Ksoloti object(s) closest to a Max/MSP or Pure Data object name."""
    name = max_or_pd_object.strip()
    cands = EQUIV.get(name) or EQUIV.get(name.lower()) or []
    out = [f"{name} -> {c}" for c in cands]
    for c in cands:
        if c in LIB.objects:
            out.append("  " + _summary(LIB.objects[c][0]))
    bare = name.rstrip("~").lower()
    if bare and len(bare) > 1:
        out.append(f"--- library search for '{bare}':")
        out.append(search_objects(bare, limit=8))
    return "\n".join(out)


# ----------------------------------------------------------------- .axp model

def _load(path: str) -> tuple[Path, ET.ElementTree]:
    p = Path(path).expanduser()
    return p, ET.parse(p)


def _save(p: Path, tree: ET.ElementTree):
    ET.indent(tree, space="   ")
    tree.write(p, encoding="unicode", xml_declaration=False)
    p.write_text(p.read_text() + "\n")


def _instances(root):
    return [o for o in root if o.tag in ("obj", "patcher", "patchobj", "zombie")]


def _find_inst(root, name):
    return next((o for o in _instances(root) if o.get("name") == name), None)


def _inst_def(o):
    """Resolve an instance to its object definition (embedded <object> for patchobj, else library)."""
    if o.tag == "patchobj":
        emb = o.find("object")
        if emb is not None:
            return {"type": o.get("type"), "inlets": _ports(emb, "inlets"), "outlets": _ports(emb, "outlets"),
                    "params": _ports(emb, "params"), "attribs": _attribs(emb), "embedded": True}
    if o.tag == "patcher":
        return None
    return LIB.resolve(o.get("type"), o.get("uuid"))


@mcp.tool()
def explain_patch(path: str) -> str:
    """Human-readable summary of a .axp/.axs: objects, nets (signal vs control), settings, unresolved objects."""
    p, tree = _load(path)
    root = tree.getroot()
    out = [f"{p.name}  ({root.tag}, appVersion {root.get('appVersion')})", "", "objects:"]
    for o in _instances(root):
        d = _inst_def(o)
        status = "" if d or o.tag == "patcher" else "  !! UNRESOLVED"
        params = ", ".join(f"{pp.get('name')}={pp.get('value')}" for pp in o.findall("params/*"))
        attrs = ", ".join(f"{a.get('attributeName')}={a.get('selection') or a.get('value') or a.get('file') or a.get('table') or a.get('obj') or (a.findtext('sText') or '')[:20]!r}" for a in o.findall("attribs/*"))
        out.append(f"  {o.get('name')}: {o.get('type')} @({o.get('x')},{o.get('y')}){status}")
        if params:
            out.append(f"      params: {params}")
        if attrs:
            out.append(f"      attribs: {attrs}")
    out.append("")
    out.append("nets:")
    for n in root.findall("nets/net"):
        srcs = [f"{s.get('obj')}.{s.get('outlet')}" for s in n.findall("source")]
        dsts = [f"{d.get('obj')}.{d.get('inlet')}" for d in n.findall("dest")]
        kind = ""
        so = _find_inst(root, n.find("source").get("obj")) if n.find("source") is not None else None
        sd = _inst_def(so) if so is not None else None
        if sd:
            port = next((pt for pt in sd["outlets"] if pt["name"] == n.find("source").get("outlet")), None)
            if port:
                kind = f"  [{'audio' if base_type(port['type']) == 'frac32buffer' else 'control'} {port['type']}]"
        out.append(f"  {' + '.join(srcs)} -> {', '.join(dsts)}{kind}")
    st = root.find("settings")
    if st is not None:
        out.append("")
        out.append("settings: " + ", ".join(f"{c.tag}={c.text}" for c in st))
    return "\n".join(out)


@mcp.tool()
def create_patch(path: str) -> str:
    """Create an empty .axp at path (fails if it exists)."""
    p = Path(path).expanduser()
    if p.exists():
        return f"{p} already exists."
    root = ET.Element("patch-1.0", appVersion="1.1.0")
    ET.SubElement(root, "nets")
    st = ET.SubElement(root, "settings")
    ET.SubElement(st, "subpatchmode").text = "no"
    ET.SubElement(root, "notes").text = ""
    _save(p, ET.ElementTree(root))
    return f"Created {p}"


@mcp.tool()
def add_object(path: str, type: str, x: int, y: int, name: str = "", params: dict | None = None,
               attribs: dict | None = None) -> str:
    """Add a library object instance. params/attribs are {name: value}; param values are in UI units
    (e.g. -64..64 for frac32.s.map). Returns the instance name to use in connect()."""
    ovs = LIB.get(type)
    if not ovs:
        return f"Unknown object type '{type}'."
    d = ovs[0]
    p, tree = _load(path)
    root = tree.getroot()
    if not name:
        stem = type.rsplit("/", 1)[-1].split(" ")[0]  # patcher convention: "audio/out stereo" -> out_1
        i = 1
        while _find_inst(root, f"{stem}_{i}") is not None:
            i += 1
        name = f"{stem}_{i}"
    elif _find_inst(root, name) is not None:
        return f"Instance '{name}' already exists."
    if d.get("subpatch"):
        o = ET.Element("patcher", type=type, name=name, x=str(x), y=str(y))
    else:
        o = ET.Element("obj", type=type, uuid=d["uuid"], name=name, x=str(x), y=str(y))
    pe = ET.SubElement(o, "params")
    for pdef in d["params"]:
        val = (params or {}).get(pdef["name"])
        if val is not None:
            ET.SubElement(pe, _param_elem(pdef["type"]), name=pdef["name"], value=str(val))
    bad = set(params or {}) - {q["name"] for q in d["params"]}
    ae = ET.SubElement(o, "attribs")
    for adef in d["attribs"]:
        val = (attribs or {}).get(adef["name"])
        if val is None:
            continue
        el = ET.SubElement(ae, adef["type"], attributeName=adef["name"])
        key = {"combo": "selection", "file": "file", "table": "table", "objref": "obj"}.get(adef["type"], "value")
        if adef["type"] == "text":
            ET.SubElement(el, "sText").text = str(val)
        else:
            el.set(key, str(val))
        if adef["type"] == "combo" and adef.get("entries") and str(val) not in adef["entries"]:
            bad.add(f"{adef['name']} (combo entries: {adef['entries']})")
    bad |= set(attribs or {}) - {a["name"] for a in d["attribs"]}
    # insert before <nets>
    nets = root.find("nets")
    root.insert(list(root).index(nets) if nets is not None else len(root), o)
    _save(p, tree)
    msg = f"Added {name} ({type})"
    if len(ovs) > 1:
        msg += f"; {len(ovs)} overloads exist, the patcher picks one by connected inlet type"
    if bad:
        msg += f"\nWARNING unknown or invalid params/attribs: {sorted(bad)}"
    return msg


@mcp.tool()
def connect(path: str, from_obj: str, outlet: str, to_obj: str, inlet: str) -> str:
    """Connect from_obj.outlet -> to_obj.inlet. Adds the dest to an existing net on that outlet if one exists."""
    p, tree = _load(path)
    root = tree.getroot()
    for nm in (from_obj, to_obj):
        if _find_inst(root, nm) is None:
            return f"No instance named '{nm}'."
    nets = root.find("nets")
    if nets is None:
        nets = ET.SubElement(root, "nets")
    for n in nets.findall("net"):
        for dd in n.findall("dest"):
            if dd.get("obj") == to_obj and dd.get("inlet") == inlet:
                return f"{to_obj}.{inlet} is already connected (one source per inlet). Remove that net first."
    net = next((n for n in nets.findall("net")
                if any(s.get("obj") == from_obj and s.get("outlet") == outlet for s in n.findall("source"))), None)
    if net is None:
        net = ET.SubElement(nets, "net")
        ET.SubElement(net, "source", obj=from_obj, outlet=outlet)
    ET.SubElement(net, "dest", obj=to_obj, inlet=inlet)
    _save(p, tree)
    v = validate_patch(str(p))
    return f"Connected {from_obj}.{outlet} -> {to_obj}.{inlet}\n{v}"


@mcp.tool()
def set_param(path: str, obj: str, name: str, value: float) -> str:
    """Set a parameter's stored value (UI units) on an instance."""
    p, tree = _load(path)
    o = _find_inst(tree.getroot(), obj)
    if o is None:
        return f"No instance named '{obj}'."
    d = _inst_def(o)
    pdef = next((q for q in (d or {}).get("params", []) if q["name"] == name), None)
    if pdef is None:
        return f"{obj} has no param '{name}'."
    pe = o.find("params")
    el = next((e for e in pe if e.get("name") == name), None)
    if el is None:
        el = ET.SubElement(pe, _param_elem(pdef["type"]), name=name)
    el.set("value", str(value))
    _save(p, tree)
    return f"{obj}.{name} = {value}"


def _compatible(src: str, dst: str, dst_def) -> str | None:
    """Return None if OK, else a message. Mirrors datatypes/*.IsConvertableToType plus overload swap."""
    s, d = base_type(src), base_type(dst)
    if s == d:
        return None
    if s == "charptr32" or d == "charptr32":
        return f"{src} -> {dst}: strings only connect to strings"
    if s == "frac32buffer" and d in SCALAR:
        return f"note: {src} -> {dst} passes sample 0 of the buffer only"
    if s in SCALAR and d == "frac32buffer":
        # the patcher may switch to a k-rate overload with a matching scalar inlet
        return None if dst_def is None else f"{src} -> {dst}: no scalar->buffer promotion (check for a k-rate overload or use conv/f2a)"
    return None  # scalar <-> scalar: always convertible


@mcp.tool()
def validate_patch(path: str) -> str:
    """Static checks: object resolution, net endpoints, type compatibility, duplicate inlets, unknown params.
    Cheap approximation of what the patcher does; compile_patch is authoritative."""
    p, tree = _load(path)
    root = tree.getroot()
    errs, warns = [], []
    defs = {}
    for o in _instances(root):
        d = _inst_def(o)
        defs[o.get("name")] = d
        if d is None and o.tag != "patcher":
            errs.append(f"{o.get('name')}: cannot resolve {o.get('type')} (uuid {o.get('uuid')})")
            continue
        if d:
            known = {q["name"] for q in d["params"]}
            for pp in o.findall("params/*"):
                if pp.get("name") not in known:
                    errs.append(f"{o.get('name')}: unknown param '{pp.get('name')}'")
    # Overload promotion, as the patcher does on load (Patch.PromoteOverloading): pick the overload whose
    # inlets match the base types of what is connected to them. Iterate so promotions propagate downstream.
    for _ in range(3):
        changed = False
        for o in _instances(root):
            d = defs.get(o.get("name"))
            if not d or d.get("embedded") or len(LIB.get(d["type"])) < 2:
                continue
            conn = {}
            for n in root.findall("nets/net"):
                s = n.find("source")
                sdef = defs.get(s.get("obj")) if s is not None else None
                sp = next((pt for pt in (sdef or {}).get("outlets", []) if pt["name"] == s.get("outlet")), None)
                for dd in n.findall("dest"):
                    if dd.get("obj") == o.get("name") and sp:
                        conn[dd.get("inlet")] = base_type(sp["type"])
            if not conn:
                continue
            def score(ov):
                return sum(1 for pt in ov["inlets"] if conn.get(pt["name"]) == base_type(pt["type"]))
            best = max(LIB.get(d["type"]), key=score)
            if best is not d and score(best) > score(d):
                defs[o.get("name")] = best
                changed = True
        if not changed:
            break
    seen_inlets = set()
    for n in root.findall("nets/net"):
        srcs, dsts = n.findall("source"), n.findall("dest")
        if len(srcs) != 1:
            errs.append(f"net with {len(srcs)} sources -> {[d.get('obj') for d in dsts]} (must be exactly 1)")
            continue
        s = srcs[0]
        sdef = defs.get(s.get("obj"))
        if s.get("obj") not in defs:
            errs.append(f"net source '{s.get('obj')}' does not exist")
            continue
        sport = next((pt for pt in (sdef or {}).get("outlets", []) if pt["name"] == s.get("outlet")), None)
        if sdef and sport is None:
            # maybe another overload has it
            if not any(pt["name"] == s.get("outlet") for ov in LIB.get(sdef["type"]) for pt in ov["outlets"]):
                errs.append(f"{s.get('obj')} has no outlet '{s.get('outlet')}'")
        for dd in dsts:
            key = (dd.get("obj"), dd.get("inlet"))
            if key in seen_inlets:
                errs.append(f"{key[0]}.{key[1]} has more than one source")
            seen_inlets.add(key)
            if dd.get("obj") not in defs:
                errs.append(f"net dest '{dd.get('obj')}' does not exist")
                continue
            ddef = defs.get(dd.get("obj"))
            dport = next((pt for pt in (ddef or {}).get("inlets", []) if pt["name"] == dd.get("inlet")), None)
            if ddef and sport and dport and base_type(dport["type"]) != base_type(sport["type"]):
                # the patcher promotes to an overload whose inlet matches the connected type (PromoteOverload)
                for ov in LIB.get(ddef["type"]):
                    alt = next((pt for pt in ov["inlets"] if pt["name"] == dd.get("inlet")
                                and base_type(pt["type"]) == base_type(sport["type"])), None)
                    if alt:
                        dport = alt
                        break
            if ddef and dport is None:
                if not any(pt["name"] == dd.get("inlet") for ov in LIB.get(ddef["type"]) for pt in ov["inlets"]):
                    errs.append(f"{dd.get('obj')} has no inlet '{dd.get('inlet')}'")
                continue
            if sport and dport:
                m = _compatible(sport["type"], dport["type"], ddef)
                if m:
                    (warns if m.startswith("note") else errs).append(f"{s.get('obj')}.{s.get('outlet')} -> {dd.get('obj')}.{dd.get('inlet')}: {m}")
    out = []
    out += [f"ERROR {e}" for e in errs]
    out += [f"WARN  {w}" for w in warns]
    return "\n".join(out) if out else "OK: no problems found (static check)."


def _patcher_running() -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", SINGLE_INSTANCE_PORT)) == 0


@mcp.tool()
def compile_patch(path: str, timeout_s: int = 180) -> str:
    """Authoritative check: run the patcher headlessly (-runTest) to generate and compile the patch.
    Refuses while the GUI patcher is open (the jar would hand the file to it and exit); use open_in_patcher then."""
    p = Path(path).expanduser().resolve()
    if _patcher_running():
        return "The Ksoloti patcher is running; headless compile would just open the file there. Use open_in_patcher and press Go Live, or quit the patcher."
    jar = Path(os.environ.get("KSOLOTI_JAR", REPO / "dist" / "Ksoloti.jar"))
    if not jar.exists():
        return f"{jar} not found; build with `ant` or set KSOLOTI_JAR."
    cmd = [os.environ.get("JAVA", "java"), "-jar", str(jar), "-runTest", str(p)]
    try:
        r = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True, timeout=timeout_s)
    except subprocess.TimeoutExpired:
        return f"Timed out after {timeout_s}s: {' '.join(cmd)}"
    tail = "\n".join((r.stdout + r.stderr).splitlines()[-40:])
    return f"exit {r.returncode} ({'PASS' if r.returncode == 0 else 'FAIL'})\n{tail}"


@mcp.tool()
def open_in_patcher(path: str) -> str:
    """Open a .axp/.axs in the running Ksoloti patcher via its single-instance socket. User then presses Go Live."""
    p = Path(path).expanduser().resolve()
    if not p.exists():
        return f"{p} does not exist."
    if not _patcher_running():
        return "No running patcher found on port 55576. Start Ksoloti first."
    with socket.create_connection(("127.0.0.1", SINGLE_INSTANCE_PORT), timeout=2) as s:
        s.sendall(f"{p}\n".encode())
    return f"Sent {p} to the patcher."


@mcp.tool()
def library_status() -> str:
    """Which libraries are indexed, object counts, parse errors."""
    counts = {}
    for ovs in LIB.objects.values():
        counts[ovs[0]["library"]] = counts.get(ovs[0]["library"], 0) + 1
    lines = [f"root: {LIB.root}"] + [f"  {k}: {v} types" for k, v in sorted(counts.items())]
    lines.append(f"  total {len(LIB.objects)} types, {len(LIB.by_uuid)} uuids")
    if LIB.errors:
        lines.append(f"parse errors ({len(LIB.errors)}):")
        lines += [f"  {e}" for e in LIB.errors[:10]]
    return "\n".join(lines)


if __name__ == "__main__":
    mcp.run()
