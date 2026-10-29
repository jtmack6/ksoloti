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

// Entry point called by firmware
void PatchProcess(int32_t* inbuf, int32_t* outbuf) {
    root.dsp();
}
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
