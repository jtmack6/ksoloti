# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About Ksoloti

Ksoloti is a music DSP platform for sketching audio algorithms on standalone hardware (Ksoloti Core board, based on STM32F427). It consists of:

- **Java Patcher Application** - Visual patch editor GUI that generates C++ code from visual patches
- **ARM Firmware** - Real-time audio processing engine running on STM32F427 (Cortex-M4F @ 192MHz)
- **USB Protocol** - Bidirectional communication between patcher and hardware

Users create patches visually in the GUI, which generates optimized C++ code. This code is compiled to native ARM machine code and uploaded to the hardware via USB for real-time execution with sub-millisecond latency.

## Build Commands

### Java Patcher Application

```bash
# Build the Java GUI application
ant clean jar

# Quick build and run (wrapper script)
./byld_java.sh

# Output: dist/Ksoloti.jar
# Entry point: axoloti.Axoloti class (main method)
```

The build system auto-generates `src/main/java/axoloti/Version.java` from Git tags during build.

### Firmware

```bash
# Build all firmware variants for both boards (Axoloti and Ksoloti)
./kompile_shortcut.sh

# Build all variants for one board (optional 0/1 flags: NORMAL USBAUDIO SPILINK FLASHER I2SCODEC)
cd firmware
./compile_firmware.sh BOARD_KSOLOTI_CORE
./compile_firmware.sh BOARD_KSOLOTI_CORE 1 0 0 0 0   # normal only

# Build single firmware variant directly (omit FWOPTIONDEF for normal)
cd firmware
make BOARDDEF=BOARD_KSOLOTI_CORE FWOPTIONDEF=FW_USBAUDIO

# Compile user patch (example)
cd firmware
./compile_patch.sh BOARD_KSOLOTI_CORE FW_NORMAL xpatch
```

**Firmware variants:**
- `FW_NORMAL` - Standard firmware
- `FW_USBAUDIO` - USB audio interface mode
- `FW_SPILINK` - SPI communication mode
- `FW_I2SCODEC` - I2S codec support

**Build output:** `firmware/build/<board>/<mode>/`, copied to `firmware/build/*.bin`, `*.elf`. Flasher builds to `firmware/flasher/flasher_build/`.

**Java version:** JDK 21 (`javac.source=21` in `nbproject/project.properties`).

See also `BUILD.md` (setup/prerequisites) and `ARCHITECTURE.md` (high-level overview).

## Architecture Overview

### Java Application Structure

```
src/main/java/axoloti/
├── Axoloti.java          # Application entry point, single instance management
├── MainFrame.java        # Main window, patch management
├── Patch.java            # Patch model + C++ code generation (GenerateCode3())
├── PatchGUI.java         # Visual patch editor with drag-drop
├── USBBulkConnection.java # USB bulk protocol (GUI side)
├── object/               # Object system
│   ├── AxoObject.java           # Object definition (loaded from XML)
│   ├── AxoObjectInstance.java   # Object instance in patch
│   └── AxoObjects.java          # Object library loader
├── usb/                  # USB device helpers
├── attribute/            # Object attributes
├── datatypes/            # DSP data types (frac32, int32, bool32)
├── inlets/               # Input ports
├── outlets/              # Output ports
├── parameters/           # Parameter widgets (knobs, sliders)
└── displays/             # Display widgets (VU meters, scopes)

src/main/java/qcmds/      # Command queue system (separate package, not under axoloti/)
├── QCmdProcessor.java    # Command processor thread
├── QCmd*.java            # GUI-side commands
└── SCmd*.java            # Serial/USB commands to firmware
```

**Key files:**
- `Patch.java` `GenerateCode3()` (~line 2018) - Code generation logic
- `src/main/java/qcmds/QCmdProcessor.java` - Async command queue for USB operations
- `object/AxoObjectInstance.java` - 54KB file, complex object instance logic

### Firmware Structure

```
firmware/
├── main.c                # Firmware entry point, system init, RTOS startup
├── patch.c               # DSP thread, audio callback, patch runtime
├── pconnection.c         # USB packet protocol handler
├── codec_ADAU1961_SAI.c  # Audio codec driver (SAI interface)
├── bulk_usb.c/h          # USB bulk endpoint
├── midi_usb.c/h          # USB MIDI class device
├── audio_usb.c/h         # USB audio implementation
├── board.h/c             # Board config (GPIO, clocks) - 141KB of pin defs
├── axoloti_math.c/h      # Fixed-point math (Q27 format)
├── axoloti_filters.c/h   # SVF filters, biquads
├── axoloti_oscs.c/h      # Oscillators
├── sdcard.c/h            # SD card filesystem (FatFS)
├── sdram.c/h             # External SDRAM driver
├── i2scodec.c            # External I2S codec (FW_I2SCODEC)
├── usbh_*.c              # USB host (MIDI devices on the host port)
├── mounter.c, mass_storage/ # SD card USB mass-storage mode
├── flasher/              # Separate flasher firmware project
├── ramlink_ksoloti.ld, ramlink_axoloti.ld # Patch memory layout (.sram1/.sram3/.sdram sections)
├── Makefile.patch.mk     # Patch build (used by compile_patch.sh)
├── xpatch.h              # Patch precompiled header
└── mutable_instruments/  # MI DSP modules (Braids, Rings, etc.)
```

**RTOS:** ChibiOS 16.1.0 (RT kernel 3.2.1; threads, mutexes, queues, HAL)

### Code Generation Flow

```
User edits patch (PatchGUI.java)
    ↓
Code generation (Patch.java GenerateCode3())
    ↓ Generates C++ file with:
    ↓ - Object instances
    ↓ - Root patch class with Init() and dsp() methods
    ↓ - Audio I/O handling
    ↓
Compile patch (QCmdCompilePatch)
    ↓ Uses: arm-none-eabi-g++ -O3 ...
    ↓ Links against precompiled header (xpatch.h.gch)
    ↓
Upload to firmware (SCmdUploadPatch)
    ↓ USB bulk transfer to SRAM regions
    ↓
Runtime linking and execution (patch.c)
    ↓ Firmware calls patch via patchMeta function pointers (fptr_patch_init, fptr_dsp_process — see patch.h)
    ↓ Sub-millisecond audio latency (16 samples @ 48kHz = 0.33ms)
```

### USB Communication (QCmd System)

**Java → Firmware:**
- Commands queued in `QCmdProcessor` and serialized to USB bulk endpoint
- Examples: `SCmdUploadPatch`, `SCmdStart`, `SCmdStop`, `SCmdPing`

**Firmware → Java:**
- Async responses/events: parameter updates, log messages, errors
- Sent from `firmware/pconnection.c`, parsed in `src/main/java/axoloti/USBBulkConnection.java`

## DSP Data Types

Ksoloti uses **fixed-point arithmetic** for efficiency:

- **frac32** - Q27 format (5 integer bits, 27 fractional)
  - Range: -16.0 to +16.0
  - Resolution: 2^-27 ≈ 7.45e-9
- **int32** - Standard 32-bit integer
- **bool32** - 32-bit boolean (0 or non-zero)
- **Buffers** - 16-sample audio buffers (`BUFSIZE = 16`, 48 kHz sample rate)

## File Formats

- **`.axp`** - Patch files (XML format, contains object instances, connections, settings)
- **`.axo`** - Object definition files (XML with embedded C++ code snippets)
- **`.axs`** - Subpatch files (can be instantiated as objects)

All serialized using Simple XML library.

## Platform-Specific Toolchains

The `platform_*` directories contain complete ARM GCC cross-compilation toolchains for each platform:

- `platform_linux_x64/` - Linux x86_64
- `platform_linux_aarch64/` - Linux ARM64
- `platform_mac_x64/` - macOS Intel
- `platform_mac_aarch64/` - macOS Apple Silicon
- `platform_win_x64/` - Windows 64-bit

Each contains:
- `bin/arm-none-eabi-gcc` - ARM cross-compiler
- `bin/make` - GNU Make
- `bin/dfu-util` - USB DFU flashing tool

Scripts auto-detect OS/architecture and use the appropriate toolchain.

**Note:** `platform_*/bin/arm-none-eabi-*` is gitignored. On a fresh macOS checkout, run `platform_mac_x64/build.sh` to download ARM GCC 9-2020-q2; on Apple Silicon, then copy it into `platform_mac_aarch64/` (see `BUILD.md`).

## Common Development Patterns

### Adding a New Object Type

1. Create `.axo` file in an object library. Libraries are not in this repo — the factory library is git-cloned from `ksoloti/ksoloti-objects` (see `utils/Preferences.java`) into the user's Ksoloti data folder
2. Define inlets, outlets, parameters, displays, attributes in XML
3. Add C++ code sections: init code, k-rate code (control rate), s-rate code (audio rate)
4. Object library auto-reloads on file change

### Modifying Code Generation

Edit `src/main/java/axoloti/Patch.java` GenerateCode3() method. May require corresponding firmware runtime changes in `firmware/patch.c`.

### Adding a USB Command

1. Create `QCmd*.java` for GUI-side command in `src/main/java/qcmds/`
2. Create `SCmd*.java` for USB protocol serialization
3. Add packet handler in `firmware/pconnection.c`

### Debugging

- **Java:** Use any Java IDE (NetBeans project structure). Main entry: `axoloti.Axoloti`
- **Firmware:** OpenOCD + GDB via SWD interface
- **USB Protocol:** Check `firmware/pconnection.c` packet handlers for protocol details
- **Generated Code:** Look in build directory after patch compilation to see generated C++

## Key Dependencies

**Java (in `/lib/`):**
- usb4java-1.3.0 - USB communication (libusb wrapper)
- flatlaf-3.6.1 - Modern look-and-feel
- simple-xml-2.7.1 - XML serialization
- rsyntaxtextarea-3.6.0 - Code editor with syntax highlighting
- jgit-7.3.0 - Git integration for library management
- batik-1.19 - SVG rendering

**Firmware:**
- ChibiOS 16.1.0 - RTOS
- CMSIS - ARM Cortex DSP library
- STM32 HAL - STM32F4 hardware abstraction
- FatFS - SD card filesystem

## Development Notes

- **Version Generation:** `src/main/java/axoloti/Version.java` is auto-generated during build from Git tags. Don't edit manually.
- **Fixed-Point Math:** Be aware of Q27 format when working with DSP code. Audio samples use 28-bit range (left-shifted by 4 bits from 32-bit int).
- **Precompiled Headers:** Firmware patch compilation uses `xpatch.h.gch` for fast compilation.
- **Code Style:** This is a legacy codebase (based on Axoloti by Johannes Taelman). Maintain consistency with existing patterns.
- **Hardware Support:** Primary target is Ksoloti Core, with legacy support for Axoloti Core boards.

## MCP server (`tools/mcp/`)

`tools/mcp/server.py` exposes the object library and `.axp` authoring to MCP clients (see its README).
Gotchas learned building it: params in a patch use the instance element names (`frac32.s.map`, not
`frac32.s.map.pitch`); the patcher will not reload a file that is already open; with USB Audio firmware,
audio reaches the host only through `ksoloti/usbaudio/out stereo`, not `audio/out stereo`.

## Resources

- [Ksoloti Forum](https://ksoloti.discourse.group)
- [Ksoloti Resources/Docs](https://ksoloti.github.io)
- [Discord Community](https://discord.com/invite/629kNnhj5R)
- [Axoloti Community Forum Backup](https://sebiik.github.io/community.axoloti.com.backup/)
