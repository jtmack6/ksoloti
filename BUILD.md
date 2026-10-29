# Building and Running Ksoloti

This guide covers how to compile and run the Ksoloti patcher application and firmware.

## Prerequisites

### macOS

- **Java JDK 8 or later** - Required for building and running the Java application
- **Apache Ant** - Build system for Java application
- **Xcode Command Line Tools** - Required for building platform tools
- **ARM GCC Toolchain** - Downloaded automatically (see below)

Install prerequisites:
```bash
# Install Homebrew if needed
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Install Java and Ant
brew install openjdk ant

# Install Xcode Command Line Tools
xcode-select --install
```

### Linux

- Java JDK 8 or later
- Apache Ant
- Build essentials (gcc, make, etc.)
- ARM GCC Toolchain (downloaded automatically)

```bash
# Ubuntu/Debian
sudo apt-get install openjdk-11-jdk ant build-essential

# Fedora
sudo dnf install java-11-openjdk-devel ant gcc make
```

### Windows

- Java JDK 8 or later
- Apache Ant
- Git Bash or MSYS2
- ARM GCC Toolchain (downloaded automatically)

## First-Time Setup (macOS)

On macOS, you need to download the ARM GCC cross-compiler toolchain:

```bash
cd platform_mac_x64
./build.sh
```

This will download and install:
- ARM GCC 9-2020-q2-update (cross-compiler for ARM)
- libusb (USB communication library)
- dfu-util (Device Firmware Update utility)
- GNU Make

**For Apple Silicon (M1/M2/M3) Macs**: After running the build script, copy the toolchain to the ARM64 platform directory:

```bash
# From the repository root
cp -r platform_mac_x64/bin/arm-none-eabi-* platform_mac_aarch64/bin/
cp -r platform_mac_x64/arm-none-eabi platform_mac_aarch64/
cp -r platform_mac_x64/lib platform_mac_aarch64/
```

## Building

### Quick Build (Everything)

Build both firmware and Java application in one command:

```bash
# Build all firmware variants + Java app
./kompile_shortcut.sh && ant clean jar
```

### Build Java Application Only

```bash
# Clean build
ant clean jar

# Quick build (if no changes to dependencies)
ant jar

# Output: dist/Ksoloti.jar
```

### Build Firmware Only

```bash
# Build all firmware variants for both Axoloti and Ksoloti boards
./kompile_shortcut.sh

# Or build firmware directly
cd firmware
./compile_firmware.sh BOARD_KSOLOTI_CORE

# Output: firmware/build/*.bin
```

**Firmware variants built:**
- `ksoloti.bin` - Normal firmware (default)
- `ksoloti_usbaudio.bin` - USB audio interface mode
- `ksoloti_spilink.bin` - SPI communication mode
- `ksoloti_i2scodec.bin` - I2S codec support

### Wrapper Scripts

Convenient wrapper scripts are available:

```bash
./byld_java.sh        # Build Java app and launch it
./qlean.sh            # Clean build artifacts
./Ksoloti.sh          # Launch the application
```

## Running the Application

### Launch the Patcher GUI

```bash
# Using the wrapper script
./Ksoloti.sh

# Or run the JAR directly
java -jar dist/Ksoloti.jar
```

On first launch, the application will:
1. Create a home directory at `~/ksoloti/` (or `~/axoloti/`)
2. Download community libraries
3. Initialize the object library

### Connecting to Hardware

1. Connect your Ksoloti Core board via USB
2. In the patcher, go to **Board** menu
3. Select **Connect**
4. Select your board from the list

### Flashing Firmware

To upload firmware to your Ksoloti Core:

1. **Board → Firmware → Flash (Enter DFU Mode)**
2. Select the firmware variant (usually "Normal")
3. Wait for the flash process to complete
4. Board will reboot automatically

## Development Workflow

### Typical Patch Development

1. Launch the patcher: `./Ksoloti.sh`
2. Create a new patch: **File → New Patch**
3. Add objects by typing in the patch canvas
4. Connect objects by dragging between outlets and inlets
5. **Patch → Live** to compile and upload to hardware
6. Adjust parameters in real-time
7. **File → Save** when satisfied

### Building and Testing Firmware Changes

If you modify firmware code:

```bash
# Build firmware
cd firmware
./compile_firmware.sh BOARD_KSOLOTI_CORE

# Launch patcher
cd ..
./Ksoloti.sh

# Flash firmware: Board → Firmware → Flash
```

### Building and Testing Java Changes

If you modify Java code:

```bash
# Rebuild and run
ant clean jar && ./Ksoloti.sh
```

## Build Output Locations

```
dist/
  └── Ksoloti.jar              # Java application

firmware/build/
  ├── ksoloti.bin              # Normal firmware
  ├── ksoloti_usbaudio.bin     # USB audio firmware
  ├── ksoloti_spilink.bin      # SPI firmware
  ├── ksoloti_i2scodec.bin     # I2S firmware
  ├── axoloti.bin              # Legacy Axoloti firmware
  └── *.elf, *.map, *.list     # Debug files

build/classes/                 # Compiled Java classes
```

## Troubleshooting

### "arm-none-eabi-gcc: No such file or directory"

The ARM GCC toolchain is not installed. Run:

```bash
cd platform_mac_x64
./build.sh
```

On Apple Silicon, also copy the toolchain to `platform_mac_aarch64/` (see First-Time Setup above).

### "nosys.specs: No such file or directory"

You have an incompatible ARM GCC version. Ksoloti requires ARM GCC 9.x. Uninstall any Homebrew ARM GCC and use the platform build script instead.

```bash
brew uninstall arm-none-eabi-gcc arm-none-eabi-binutils
cd platform_mac_x64
./build.sh
```

### Build fails with "Java version not supported"

Ensure you have Java 8 or later:

```bash
java -version
```

### Board not detected

- Check USB cable (must support data, not just power)
- Try a different USB port
- On Linux, you may need udev rules (see Linux-specific documentation)
- On macOS, no drivers needed (uses native libusb)

## Clean Builds

To completely clean all build artifacts:

```bash
# Clean Java build
ant clean

# Clean firmware build
rm -rf firmware/build/*

# Quick clean script
./qlean.sh
```

## Advanced: Building Application Bundles

### Building Self-Contained App Bundles with jpackage

The `build_jpackage_multiarch.sh` script creates self-contained application bundles with an embedded JDK runtime. Users don't need to install Java separately.

**Build for your current platform:**

```bash
# macOS Apple Silicon
./build_jpackage_multiarch.sh mac aarch64

# macOS Intel
./build_jpackage_multiarch.sh mac x64

# Linux x64
./build_jpackage_multiarch.sh linux x64

# Linux ARM64 (e.g., Raspberry Pi)
./build_jpackage_multiarch.sh linux aarch64

# Windows x64
./build_jpackage_multiarch.sh win x64
```

**What the script does:**

1. Downloads Zulu JDK 21 for the target platform (cached in `./jdks/`)
2. Cleans previous builds (`./qlean.sh` + `ant clean`)
3. Compiles all firmware variants (`./kompile_shortcut.sh`)
4. Builds the Java JAR (`ant jar`)
5. Runs `jpackage` to create the app bundle with embedded JDK
6. Copies platform-specific resources:
   - ARM GCC cross-compiler toolchain (`platform_*`)
   - Compiled firmware binaries (`firmware/build/`)
   - ChibiOS RTOS and CMSIS libraries
7. Removes temporary build artifacts to reduce bundle size

**Output location:**

```
packagetemp_jpackage/
  └── Ksoloti-<version>-<os>_<arch>/
      ├── Ksoloti.app/              # macOS app bundle
      │   └── Contents/
      │       ├── MacOS/Ksoloti     # Launcher executable
      │       ├── Resources/        # Icon and resources
      │       ├── app/              # Java application
      │       └── runtime/          # Bundled JDK
      └── Ksoloti/                  # Linux/Windows layout
          ├── bin/
          │   ├── Ksoloti           # Launcher script/executable
          │   ├── firmware/         # Compiled firmware
          │   ├── platform_*/       # Cross-compiler toolchain
          │   └── CMSIS/            # ARM libraries
          └── lib/
              ├── app/              # Java application
              └── runtime/          # Bundled JDK
```

**Running the packaged app:**

```bash
# macOS
open packagetemp_jpackage/Ksoloti-1.1.0-mac_aarch64/Ksoloti.app

# Linux
packagetemp_jpackage/Ksoloti-1.1.0-linux_x64/Ksoloti/bin/Ksoloti

# Windows
packagetemp_jpackage\Ksoloti-1.1.0-win_x64\Ksoloti\bin\Ksoloti.exe
```

**Distribution:**

The entire app bundle directory can be:
- Zipped and distributed directly
- Packaged into a DMG (macOS), DEB/RPM (Linux), or MSI (Windows)
- Code-signed for distribution (macOS/Windows)

**Bundle size:** Approximately 500-700 MB per platform (includes JDK + toolchain + firmware)

**Technical notes:**

The build script includes macOS-specific compatibility fixes:
- Uses `sed -E` instead of `grep -P` for version extraction (BSD grep compatibility)
- Handles macOS JDK bundle structure (`zulu-21.jdk/Contents/Home`) automatically
- Downloads platform-specific JDK variants from Azul Zulu builds

First build will download the JDK (~200 MB), subsequent builds reuse the cached JDK in `./jdks/`.

### Legacy Packaging (Deprecated)

The older `pkg.sh` and Ant-based bundling system is deprecated:

```bash
./pkg.sh                          # Legacy build package (deprecated)
```

Use `build_jpackage_multiarch.sh` instead for modern, self-contained bundles.

## Getting Help

- [Ksoloti Forum](https://ksoloti.discourse.group)
- [Ksoloti Discord](https://discord.com/invite/629kNnhj5R)
- [Ksoloti Documentation](https://ksoloti.github.io)
- [Axoloti Community Archive](https://sebiik.github.io/community.axoloti.com.backup/)
