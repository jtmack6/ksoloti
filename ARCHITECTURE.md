# Ksoloti Architecture Overview

This document provides a high-level view of the Ksoloti architecture and build system.

## What is Ksoloti?

Ksoloti is a **visual music DSP platform** that allows users to create audio processing patches through a graphical interface. Patches are compiled to native ARM machine code and run on standalone hardware (Ksoloti Core board).

The system consists of three main components:

1. **Java Patcher Application** - Visual patch editor (desktop GUI)
2. **ARM Firmware** - Real-time audio processing engine (embedded)
3. **USB Protocol** - Communication bridge between GUI and hardware

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    USER'S COMPUTER                          │
│                                                             │
│  ┌───────────────────────────────────────────────────┐     │
│  │   Java Patcher Application (Swing GUI)           │     │
│  │                                                   │     │
│  │   • Visual patch editor                          │     │
│  │   • Object library manager                       │     │
│  │   • C++ code generator                           │     │
│  │   • ARM compiler wrapper                         │     │
│  │   • USB communication                            │     │
│  └───────────────┬───────────────────────────────────┘     │
│                  │                                          │
│                  │ USB (bulk transfer)                      │
│                  │                                          │
└──────────────────┼──────────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────────┐
│              KSOLOTI CORE HARDWARE                          │
│                                                             │
│  ┌───────────────────────────────────────────────────┐     │
│  │   ARM Firmware (STM32F427, Cortex-M4F @ 192MHz)  │     │
│  │                                                   │     │
│  │   • ChibiOS RTOS                                 │     │
│  │   • Audio codec driver (ADAU1961)                │     │
│  │   • USB device stack                             │     │
│  │   • Patch runtime engine                         │     │
│  │   • Dynamic patch loading                        │     │
│  └───────────────────────────────────────────────────┘     │
│                                                             │
│  Audio In/Out, MIDI, GPIO, SD Card, etc.                   │
└─────────────────────────────────────────────────────────────┘
```

## Build System Architecture

Ksoloti uses a hybrid build system with different tools for different components:

### Java Application Build

**Build Tool:** Apache Ant (NetBeans-based project)

```
src/main/java/          Java source code
    └── axoloti/        Main package
build.xml               Ant build configuration
nbproject/              NetBeans project files
lib/                    Java dependencies (JARs)
    ├── usb4java/       USB communication
    ├── flatlaf/        Look and feel
    └── ...             Other libraries
    ↓
[Apache Ant]
    ↓
dist/Ksoloti.jar        Executable JAR with bundled deps
```

**Key Features:**
- Auto-generates `Version.java` from Git tags
- Bundles all dependencies into single JAR
- Includes source code in JAR for debugging
- Platform-independent (runs on any OS with Java)

### Firmware Build

**Build Tool:** GNU Make + ARM GCC Cross-Compiler

```
firmware/
├── main.c              Entry point
├── patch.c             Patch runtime
├── codec_*.c           Audio drivers
├── usb_*.c             USB stack
├── Makefile            Main build config
├── Makefile.patch.mk   Patch compilation
└── board.h             Hardware definitions
    ↓
[ARM GCC Toolchain]
    ↓
firmware/build/
├── ksoloti.bin         Firmware binary (flash to board)
└── ksoloti.elf         Debug symbols
```

**Cross-Compilation:**
- Firmware is ARM code (Cortex-M4)
- Built on x86/ARM64 host computers
- Requires ARM cross-compiler toolchain

### Platform-Specific Toolchains

Ksoloti includes pre-built or downloadable toolchains for each platform:

```
platform_linux_x64/          Linux x86_64
platform_linux_aarch64/      Linux ARM64
platform_mac_x64/            macOS Intel
platform_mac_aarch64/        macOS Apple Silicon
platform_win_x64/            Windows 64-bit
    │
    ├── bin/
    │   ├── arm-none-eabi-gcc    ARM cross-compiler
    │   ├── arm-none-eabi-g++    ARM C++ compiler
    │   ├── make                 GNU Make
    │   ├── dfu-util             Firmware flashing tool
    │   └── ...
    │
    └── arm-none-eabi/           ARM libraries and headers
        └── lib/
            ├── libc.a           C standard library
            ├── libm.a           Math library
            └── nosys.specs      Embedded specs file
```

**Why Platform-Specific Directories?**
- Each OS/architecture needs its own native executables
- Scripts auto-detect platform and add correct `bin/` to PATH
- Users don't need to install ARM toolchain separately
- Ensures consistent builds across all platforms

### Build Script Flow

When you run `./kompile_shortcut.sh`:

```
kompile_shortcut.sh
    ↓
Detects OS: uname → Darwin/Linux/Windows
    ↓
Detects Architecture: uname -m → x86_64/arm64/aarch64
    ↓
Sets PATH: export PATH="platform_mac_aarch64/bin:$PATH"
    ↓
Calls: firmware/compile_firmware.sh BOARD_KSOLOTI_CORE
    ↓
For each variant (NORMAL, USBAUDIO, SPILINK, I2SCODEC):
    ↓
    make BOARD=BOARD_KSOLOTI_CORE
        ↓
        arm-none-eabi-gcc -c *.c      Compile C files
        ↓
        arm-none-eabi-g++ -c *.cpp    Compile C++ files
        ↓
        arm-none-eabi-gcc -o *.elf    Link executable
        ↓
        arm-none-eabi-objcopy -O binary   Create .bin file
    ↓
Output: firmware/build/ksoloti.bin (and variants)
```

## Code Generation Flow

This is the core of how Ksoloti works:

### 1. User Creates Visual Patch

```
User drags objects in GUI
    ↓
Creates instances: oscillator, filter, envelope, etc.
    ↓
Connects them: outlet → inlet
    ↓
Sets parameters: frequency, cutoff, etc.
```

### 2. Java Generates C++ Code

**File:** `src/main/java/axoloti/Patch.java` (method `GenerateCode3()`)

```java
// Simplified pseudocode
void GenerateCode3() {
    // Generate C++ class for each object instance
    for (AxoObjectInstance obj : objects) {
        code += "class obj_" + obj.id + " {\n";
        code += obj.generateInitCode();
        code += obj.generateDSPCode();
        code += "};\n";
    }

    // Generate root patch class
    code += "class rootc {\n";
    code += "  obj_1_oscillator osc;\n";
    code += "  obj_2_filter filt;\n";
    code += "  void Init() { osc.Init(); filt.Init(); }\n";
    code += "  void dsp() {\n";
    code += "    filt.input = osc.output;\n";
    code += "    osc.dsp(); filt.dsp();\n";
    code += "  }\n";
    code += "};\n";

    writeToFile("xpatch.cpp", code);
}
```

**Generated file structure:**

```cpp
#include <xpatch.h>  // Precompiled header with all firmware APIs

// Object instances
class obj_1_lfo_sine {
    int32_t phase;
    void Init() { phase = 0; }
    void dsp() {
        phase += inlet_pitch;
        outlet_wave = sine_table[phase >> 21];
    }
};

// Root patch
class rootc {
    obj_1_lfo_sine lfo;
    void Init() { lfo.Init(); }
    void dsp() { lfo.dsp(); }
};

rootc root;

// Entry points: the firmware does not link against symbols by name.
// The patch binary exports a patchMeta struct (firmware/patch.h) holding
// function pointers (fptr_patch_init, fptr_patch_dsp_process, ...) that
// patch.c calls every 16-sample block.
```

### 3. ARM Compiler Builds Patch

**File:** `firmware/compile_patch.sh`

```bash
arm-none-eabi-g++ \
    -mcpu=cortex-m4 \
    -mfloat-abi=hard \
    -O3 \
    -include xpatch.h \
    -c xpatch.cpp \
    -o xpatch.o

arm-none-eabi-gcc \
    -Wl,--gc-sections \
    -T patch.ld \
    xpatch.o \
    -o xpatch.elf

arm-none-eabi-objcopy \
    -O binary \
    xpatch.elf \
    xpatch.bin
```

**Key Flags:**
- `-mcpu=cortex-m4` - Target ARM Cortex-M4 processor
- `-mfloat-abi=hard` - Use hardware floating point
- `-O3` - Aggressive optimization
- `-include xpatch.h` - Precompiled header (fast compilation)

### 4. USB Upload to Hardware

**Files:** `src/main/java/qcmds/SCmdUploadPatch.java`, `firmware/pconnection.c`

```
Java: SCmdUploadPatch command
    ↓
Reads xpatch.bin from disk
    ↓
Splits into USB packets (512 bytes each)
    ↓
Sends over USB bulk endpoint
    ↓
Firmware: Receives packets
    ↓
Writes to SRAM regions (0x20000000, 0x10000000)
    ↓
Firmware: Calls PatchInit()
    ↓
Patch is now running!
```

### 5. Real-Time Execution

**File:** `firmware/patch.c`

```c
// Audio interrupt callback (every 16 samples @ 48kHz = 0.33ms)
void audio_callback(int32_t* input_buffer, int32_t* output_buffer) {
    // Copy audio inputs
    for (int i = 0; i < 16; i++) {
        AudioInputLeft[i] = input_buffer[i*2];
        AudioInputRight[i] = input_buffer[i*2+1];
    }

    // Call patch DSP code
    PatchProcess(input_buffer, output_buffer);

    // Copy audio outputs
    for (int i = 0; i < 16; i++) {
        output_buffer[i*2] = AudioOutputLeft[i];
        output_buffer[i*2+1] = AudioOutputRight[i];
    }
}
```

## Key Technologies

### Java Application
- **Swing** - GUI framework
- **USB4Java** - USB communication (wraps libusb)
- **Simple XML** - Patch/object file serialization
- **RSyntaxTextArea** - Code editor with syntax highlighting
- **FlatLaf** - Modern look and feel

### Firmware
- **ChibiOS 16.1.0** - Real-Time Operating System
  - Threads, mutexes, semaphores
  - Hardware abstraction layer (HAL)
  - USB device stack
- **STM32 HAL** - STM32F4 hardware drivers
- **CMSIS DSP** - ARM math library
- **FatFS** - SD card filesystem

### Build Tools
- **Apache Ant** - Java build automation
- **GNU Make** - Firmware build system
- **ARM GCC 9.3.1** - ARM Embedded Toolchain (2020-q2-update)
  - C/C++ cross-compiler for ARM Cortex-M
  - Newlib (embedded C library)
  - GDB (debugging)

## Memory Architecture

### Ksoloti Core Hardware (STM32F427)

```
Flash Memory:          896 KB      Firmware storage
SRAM1:                 44 KB       Main firmware RAM
SRAM2:                 8 KB        USB buffers
SRAM3:                 64 KB       Patch code/data
CCM RAM:               14 KB       Core-coupled memory (fast)
External SDRAM:        32 MB       Audio buffers, patch data
```

**Patch Loading:**
- Firmware reserves empty SRAM3 region for patch code
- Patch binary uploaded via USB
- Firmware jumps to patch entry points (function pointers)
- Patch has access to firmware APIs (audio, MIDI, GPIO, etc.)

## Fixed-Point DSP

Ksoloti uses **Q27 fixed-point arithmetic** for efficiency:

```c
typedef int32_t frac32;  // Q27 format (5 integer bits, 27 fractional)

// Range: -16.0 to +16.0
// Resolution: 2^-27 ≈ 0.0000000075

// Example: 1.0 in Q27
frac32 one = 1 << 27;  // 0x08000000

// Multiply two Q27 values
frac32 multiply(frac32 a, frac32 b) {
    return (int32_t)(((int64_t)a * b) >> 27);
}
```

**Why Fixed-Point?**
- Cortex-M4F has fast integer math
- Deterministic performance (no floating-point exceptions)
- Sufficient precision for audio (16-bit DAC/ADC)

## Patch Language Reference

Ksoloti has no written grammar; the language is defined by the Simple XML annotations in
`src/main/java/axoloti/object/AxoObject.java` and `src/main/java/axoloti/Patch.java`, and its
semantics by `Patch.java` `GenerateCode3()`. This section writes that down, aimed at readers
coming from Max or Pure Data.

### Vocabulary: Max/PD → Ksoloti

| Max / PD | Ksoloti | Difference that matters |
|---|---|---|
| object box, external | `.axo` object (`<obj.normal>` inside `<objdefs>`) | Defined by C++ snippets in XML, compiled into every patch that uses it |
| abstraction | `.axs` subpatch, or `<patchobj>` embedded in the patch | Embedded definitions are copied into the `.axp`, so the file is self-contained |
| patch cord | `<net>` inside `<nets>` | Becomes a plain C++ variable; zero runtime cost |
| signal cord (`~`) | `frac32buffer` port, `code.srate` section | 16-sample block of Q27 fixed-point, not float |
| control message | `frac32`, `int32`, `bool32` port, `code.krate` section | Runs once per block (3 kHz). No message queue, no hot/cold inlets, no order-of-evaluation rules |
| bang | `bool32.rising` inlet / `bool32.pulse` outlet | A level that is sampled each block, not an event |
| creation arguments, `loadbang` | `<attribs>` | Compile-time constants, text-substituted as `attr_<name>` before compilation |
| number box, dial | `<params>` | Run-time values, live-editable over USB as `param_<name>` |
| `scope~`, `meter~`, `print` | `<displays>` | Firmware → GUI only, cannot feed back into the patch |
| `inlet` / `outlet` in an abstraction | `patch/inlet f|i|b|a|string`, `patch/outlet …` objects | Typed; `a` is audio (`frac32buffer`) |
| `poly~` | subpatch mode `polyphonic`, `polychannel`, `polyexpression` | Voices are N instances of the subpatch class |

### Execution model

The whole patch compiles to one C++ class, `rootc`, with `Init()` and `dsp()` methods.
Each object instance is a nested class with its own `Init()` and `dsp()`. Per 16-sample block
the firmware calls `rootc::dsp()`, which calls every object's `dsp()` **in object-list order**
(creation order by default; the "sort by execution" preference reorders top-to-bottom,
left-to-right). Inside one object's `dsp()`:

1. `code.krate` runs once.
2. `code.srate` runs in a loop `for (buffer_index = 0; buffer_index < BUFSIZE; buffer_index++)`.

Nets are member variables of `rootc`, passed by reference into each `dsp()` call. Because nets
persist across blocks, **feedback is allowed and is implicitly a one-block delay**: if an inlet
reads a net whose source runs later in the order, the generator reads a latched copy of the
previous block's value (`Net.NeedsLatch()`, `Patch.GenerateDSPCodePlusPlusSubObj()`). There is
no cycle error and no need for a `z-1` object, but the delay is 16 samples, not one.

MIDI does not flow through cords. Objects that react to MIDI implement `code.midihandler`,
which the generator assembles into one `MidiInHandler()` on `rootc`, called between blocks.

### Port types

The set of port kinds is closed; it is the `@ElementListUnion` in `AxoObject.java`.

| Base type | C type | Meaning | Range hints (UI only, not checked) |
|---|---|---|---|
| `bool32` | `int32_t` | 0 / non-zero | inlets: `.rising`, `.risingfalling`; outlet: `.pulse` |
| `int32` | `int32_t` | integer | `.pos`, `.bipolar` |
| `frac32` | `int32_t` | Q27 fixed-point, ±16.0 | `.pos` (shown 0..64), `.bipolar` (shown −64..64) |
| `frac32buffer` | `int32_t[16]` | one audio block of `frac32` | `.pos`, `.bipolar` |
| `charptr32` | `char*` | string (table names, file names) | — |

**Automatic conversions** (`datatypes/*.java`, `GenerateConversionToType()`): any of
`frac32`, `int32`, `bool32` converts to any other (`int32 → frac32` is `<< 21`,
`frac32 → bool32` is `> 0`). A `frac32buffer` connected to a scalar inlet passes **sample 0
only**. There is **no scalar → buffer promotion**; an object that accepts both provides two
overloads instead (see `conv/b2p` below). Connecting two incompatible types, or two sources to
one inlet of different types, is an invalid net and the inlet gets its default value.

### Anatomy of an object (`.axo`)

`objects/ksoloti/conv/b2p.axo` from `ksoloti-objects`, abridged. It defines the same object
twice; the patcher picks the overload whose inlet type matches what is connected:

```xml
<objdefs appVersion="1.0.12">
  <obj.normal id="b2p" uuid="f6f63d71…" sha="93baeaf…">
    <upgradeSha>b80b299…</upgradeSha>                <!-- previous version of this object -->
    <sDescription>bipolar to unipolar</sDescription>
    <author>Johannes Taelman, Ksoloti</author>
    <license>BSD</license>
    <helpPatch>b2p.axh</helpPatch>                    <!-- optional -->
    <inlets>  <frac32 name="i" description="input"/>  </inlets>
    <outlets> <frac32 name="o" description="output"/> </outlets>
    <displays/> <params/> <attribs/>
    <code.krate><![CDATA[outlet_o = (inlet_i >> 1) + (1 << 26);]]></code.krate>
  </obj.normal>

  <obj.normal id="b2p" uuid="5b5f940…" sha="8613e8a…">   <!-- audio-rate overload -->
    …
    <inlets>  <frac32buffer name="i"/> </inlets>
    <outlets> <frac32buffer name="o"/> </outlets>
    <code.srate><![CDATA[outlet_o = (inlet_i >> 1) + (1 << 26);]]></code.srate>
  </obj.normal>
</objdefs>
```

Code sections and where they land in the generated class (`AxoObjectInstance.java`):

| Section | Generated location | Typical use |
|---|---|---|
| `code.declaration` | class body | member variables, helper classes |
| `code.init` | `Init()` | reset state |
| `code.dispose` | `Dispose()` | free SDRAM, close files |
| `code.krate` | `dsp()`, once per block | control logic |
| `code.srate` | `dsp()`, inside the 16-sample loop | per-sample DSP |
| `code.midihandler` | `MidiInHandler()` | MIDI reactions |

Inside the snippets the generator provides these names: `inlet_<name>`, `outlet_<name>`,
`param_<name>`, `attr_<name>`, `disp_<name>`, plus `parent` (the enclosing `rootc`) and, in
`code.srate`, `buffer_index`. Snippets are inserted verbatim, so they can use any firmware API
exposed through `xpatch.h` (`axoloti_math.h`, `axoloti_filters.h`, SDRAM allocation, …).

**Params vs attribs.** `<params>` are run-time: `frac32.u.map`, `frac32.s.map` and their
scaled variants (`.pitch`, `.freq`, `.gain`, `.ratio`, `.kdecaytime`, …), `int32`,
`int32.hradio` / `.vradio`, `bin1` … `bin32`, `bin1.momentary`. The `.map` suffix exposes a
modulation / MIDI-CC slot. `<attribs>` are compile-time: `spinner`, `combo`, `int32`, `objref`,
`table`, `file`, `wavefile`, `text`. Changing an attrib forces a recompile; changing a param
does not.

### Anatomy of a patch (`.axp`)

`patches/demos/hacked_eq5.axp` from `ksoloti-objects`, abridged:

```xml
<patch-1.0 appVersion="1.0.12">
  <obj type="audio/outconfig" uuid="eace67e…" name="outconfig_1" x="14" y="14">
    <params/>
    <attribs> <combo attributeName="mode" selection="Stereo"/> </attribs>
  </obj>
  <obj type="osc/brds/chaoticfeedbackfm" uuid="e5d9cdd6…" name="chaoticfeedbackfm_1" x="168" y="14">
    <params> <frac32.s.map name="pitch" value="-60.53"/> … </params>
    <attribs/>
  </obj>
  <patchobj type="patch/object" uuid="b190fbc0…" name="hacked_eq5hq_1" x="336" y="14">
    <params> … </params>
    <object id="patch/object" uuid="b190fbc0…">   <!-- full object definition embedded here -->
      <inlets> <frac32buffer name="in"/> <frac32.bipolar name="low"/> … </inlets>
      …
    </object>
  </patchobj>
  <nets>
    <net>
      <source obj="chaoticfeedbackfm_1" outlet="out"/>
      <dest   obj="hacked_eq5hq_1"      inlet="in"/>   <!-- one source, N dests -->
    </net>
  </nets>
  <settings> <subpatchmode>no</subpatchmode> <MidiChannel>1</MidiChannel> <NPresets>8</NPresets> … </settings>
  <notes/>
</patch-1.0>
```

Instance kinds (`Patch.java` `@ElementListUnion`): `obj` (library object), `patcher`
(inline subpatch editable in place), `patchobj` (embedded object definition), `comment`,
`hyperlink`, and `zombie`, a placeholder kept so a patch still loads when an object cannot be
resolved. The `name` attribute is the instance name used in `<nets>` and in the generated
C++ (`<name>_i`).

### Versioning and resolution

An object carries `uuid` (identity), `sha` (hash of its definition) and `upgradeSha` (the
`sha` it supersedes). A patch stores both `type` (path such as `osc/sine`) and `uuid` for each
instance. The loader resolves by `uuid` first and falls back to `type`, so renaming or editing a
library object does not silently break old patches; unresolvable instances become zombies.

### Where to read the semantics

- `src/main/java/axoloti/Patch.java` — `GenerateCode3()`, `GenerateDSPCodePlusPlusSub()`, `SortByExecution()`
- `src/main/java/axoloti/object/AxoObjectInstance.java` — per-object class emission (`Init(` ~line 939, `dsp(` ~line 1030)
- `src/main/java/axoloti/Net.java` — `NeedsLatch()`, net validity
- `src/main/java/axoloti/datatypes/` — type conversions
- `firmware/patch.h`, `firmware/patch.c` — `patchMeta` function pointers and the block loop

## Development Workflow Summary

```
Edit Java Code → ant jar → ./Ksoloti.sh
                              ↓
Edit Firmware → make → Flash via DFU
                              ↓
Create Patch → Live → Compile → Upload → Run on Hardware
```

## Where Tools Come From

| Tool | Source | How it's Obtained |
|------|--------|-------------------|
| **ARM GCC** | [ARM Developer](https://developer.arm.com/downloads/-/gnu-rm) | `platform_mac_x64/build.sh` downloads automatically |
| **Make** | GNU | Bundled in platform directories or system package |
| **DFU-Util** | [dfu-util.sourceforge.net](http://dfu-util.sourceforge.net) | Built by `platform_*/build.sh` |
| **libusb** | [libusb.info](https://libusb.info) | Built by `platform_*/build.sh` |
| **Java Libraries** | Various (Maven/manual) | Included in `lib/` directory |
| **ChibiOS** | [chibios.org](http://www.chibios.org) | Included in `chibios/` directory |
| **STM32 HAL** | STMicroelectronics | Included in `firmware/STM32F4xx_HAL_Driver/` |

## Summary

Ksoloti is a **code-generating visual DSP platform**:

1. **User** creates patches visually in Java GUI
2. **Java** generates optimized C++ code from patch
3. **ARM GCC** compiles C++ to native ARM machine code
4. **USB** uploads binary to hardware
5. **Firmware** executes patch in real-time with <1ms latency

This architecture provides the **ease of visual programming** with the **performance of native code**.
