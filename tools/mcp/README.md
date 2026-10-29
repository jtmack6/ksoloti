# Ksoloti MCP server

Lets an MCP client (Claude Code, Claude Desktop, ...) read the Ksoloti object library, explain and
author `.axp` patches, validate them, compile them headlessly, and hand them to the running patcher.
Read `ARCHITECTURE.md` → "Patch Language Reference" for the model this works on.

## Install

```sh
cd tools/mcp
uv run server.py          # or: pip install mcp && python3 server.py
```

Claude Code:

```sh
claude mcp add ksoloti -- uv run --directory /path/to/ksoloti/tools/mcp server.py
```

## Config (environment)

| Var | Default | Meaning |
|---|---|---|
| `KSOLOTI_LIBS` | newest `~/ksoloti/<version>/` | folder holding `axoloti-factory/`, `ksoloti-objects/`, ... |
| `KSOLOTI_JAR` | `<repo>/dist/Ksoloti.jar` | used by `compile_patch` |
| `JAVA` | `java` | JDK 21 |

## Tools

Library (read-only):
- `search_objects(query)` — full text over type path, description, port names
- `describe_object(type_or_uuid, include_code=False)` — all overloads, ports, params, attribs, C++
- `find_equivalent("cycle~")` — Max/PD name → Ksoloti object(s)
- `explain_patch(path)` — objects, nets tagged audio/control, settings, unresolved objects
- `library_status()`

Authoring:
- `create_patch(path)`, `add_object(path, type, x, y, name?, params?, attribs?)`,
  `connect(path, from_obj, outlet, to_obj, inlet)`, `set_param(path, obj, name, value)`
- `validate_patch(path)` — static checks (resolution, endpoints, type compatibility, one source per inlet)
- `compile_patch(path)` — authoritative: `java -jar Ksoloti.jar -runTest path`. Only works while the
  GUI is **not** running (the jar hands files to a running instance and exits).
- `open_in_patcher(path)` — sends the file to the running GUI over its single-instance socket
  (port 55576); press Go Live there. USB stays owned by the GUI. A file that is already open is only
  focused, not reloaded: close its window before sending it again.

## Limits

Param values are stored in UI units, as the patcher writes them. `validate_patch` approximates the
patcher's rules; trust `compile_patch`. No live parameter control yet (would need a small Java-side
socket; see the plan in the repo history).
