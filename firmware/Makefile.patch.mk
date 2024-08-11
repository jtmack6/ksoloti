BOARDDEF=
FWOPTIONDEF=

CCFLAGS = \
    -DARM_MATH_CM4 \
    -DCORTEX_USE_FPU=TRUE \
    -DSTM32F427xx \
    -DTHUMB \
    -DTHUMB_NO_INTERWORKING \
    -DTHUMB_PRESENT \
    -D__FPU_PRESENT \
    -O2	\
    -fgcse-after-reload \
    -foptimize-strlen \
    -freorder-blocks-algorithm=simple \
    -fno-schedule-insns \
    -fno-split-paths \
    -fno-data-sections \
    -fno-exceptions \
    -fno-unwind-tables \
    -fno-math-errno \
    -fno-rtti \
    -fno-threadsafe-statics \
    -fno-unwind-tables \
    -fno-use-cxa-atexit \
    -fomit-frame-pointer \
    -fpermissive \
    -mcpu=cortex-m4 \
    -mfloat-abi=hard \
    -mfpu=fpv4-sp-d16 \
    -mthumb \
    -mtune=cortex-m4 \
    -mword-relocations \
    -nostdlib \
    -std=c++11 \
	\
    -fgcse-after-reload \
    -finline-functions \
    -fipa-cp-clone \
    -fmerge-constants \
    -fno-data-sections \
    -fno-fp-int-builtin-inexact \
    -fno-function-cse \
    -fno-ipa-bit-cp \
    -fno-ipa-icf \
    -fno-ipa-icf-functions \
    -fno-ipa-icf-variables \
    -fno-ipa-ra \
    -fno-ipa-vrp \
    -fno-ira-share-save-slots \
    -fno-ira-share-spill-slots \
    -fno-loop-block \
    -fno-loop-interchange \
    -fno-loop-strip-mine \
    -fno-lto \
    -fno-lra-remat \
    -fmerge-all-constants \
    -fpartial-inlining \
    -fno-peel-loops \
    -fno-printf-return-value \
    -fno-schedule-insns	\
    -fno-split-loops \
    -fno-split-paths \
    -fno-ssa-backprop \
    -fno-ssa-phiopt \
    -fno-stdarg-opt \
    -fno-store-merging \
    -fno-strict-overflow \
    -fno-strict-volatile-bitfields \
    -fno-tree-loop-if-convert-stores \
    -fno-tree-switch-conversion \
    -fno-unsafe-loop-optimizations \
    -fno-unsafe-math-optimizations \
    -fno-whole-program \
    -foptimize-strlen \
    -fpredictive-commoning \
    -freciprocal-math \
    -fschedule-fusion \
    -fschedule-insns \
    -ftoplevel-reorder \
    -ftree-copyrename \
    -ftree-loop-distribute-patterns \
    -ftree-loop-vectorize \
    -ftree-vectorize \
    -ftree-parallelize-loops=1 \
    -ftree-partial-pre \
    -ftree-slp-vectorize \
    -funit-at-a-time \
    -funswitch-loops \
    -fvect-cost-model=dynamic \
    -D$(BOARDDEF)

ELFNAME=
ifeq ($(BOARDDEF),BOARD_KSOLOTI_CORE)
  RAMLINKOPT = -Tramlink_ksoloti.ld
  ELFNAME = ksoloti
else ifeq ($(BOARDDEF),BOARD_AXOLOTI_CORE)
  RAMLINKOPT = -Tramlink_axoloti.ld
  ELFNAME = axoloti
endif

ifeq ($(FWOPTIONDEF),FW_SPILINK)
  ELFNAME := $(ELFNAME)_spilink
endif

LDFLAGS = \
    $(RAMLINKOPT) \
    -Bsymbolic \
    -fno-exceptions \
    -fno-unwind-tables \
    -flto \
    -fno-rtti \
    -mcpu=cortex-m4 \
    -mtune=cortex-m4 \
    -mfloat-abi=hard \
    -mfpu=fpv4-sp-d16 \
    -mno-thumb-interwork \
    -mthumb \
    -nostartfiles

CC=arm-none-eabi-gcc
CPP=arm-none-eabi-g++
#CPP=arm-none-eabi-gcc -lstdc++
LD=arm-none-eabi-gcc
CP=arm-none-eabi-objcopy
DMP=arm-none-eabi-objdump
SIZ=arm-none-eabi-size

axoloti_runtime ?= ..
axoloti_release ?= ..
axoloti_home ?= ..
axoloti_libraries ?= ..
axoloti_firmware ?= ../firmware

CHIBIOS = ${axoloti_release}/chibios
CMSIS = ${axoloti_release}/CMSIS

EMPTY := 
SPACE := $(EMPTY) $(EMPTY)
BUILDDIR=$(subst $(SPACE),\ ,${axoloti_libraries}/build)
FIRMWARE=$(subst $(SPACE),\ ,${axoloti_firmware})

include $(CHIBIOS)/boards/ST_STM32F4_DISCOVERY/board.mk
include $(CHIBIOS)/os/hal/platforms/STM32F4xx/platform.mk
include $(CHIBIOS)/os/hal/hal.mk
include $(CHIBIOS)/os/ports/GCC/ARMCMx/STM32F4xx/port.mk
include $(CHIBIOS)/os/kernel/kernel.mk
include $(CHIBIOS)/os/various/fatfs_bindings/fatfs.mk

INCDIR = $(CMSIS)/Core/Include $(CMSIS)/DSP/Include \
         $(PORTINC) $(KERNINC) $(TESTINC) \
         $(HALINC) $(PLATFORMINC) $(BOARDINC) $(FATFSINC) \
         ${FIRMWARE} $(CHIBIOS) ${FIRMWARE}/mutable_instruments

# Paths
IINCDIR = $(patsubst %,-I%,$(INCDIR) $(DINCDIR) $(UINCDIR))
LLIBDIR = $(patsubst %,-L%,$(DLIBDIR) $(ULIBDIR))

all: ${BUILDDIR}/xpatch.bin

${BUILDDIR}/xpatch.h.gch: ${FIRMWARE}/xpatch.h ${FIRMWARE}/patch.h ${FIRMWARE}/axoloti.h ${FIRMWARE}/parameter_functions.h ${FIRMWARE}/axoloti_math.h ${FIRMWARE}/axoloti_filters.h
#	@echo Building precompiled header
	@$(CPP) $(CCFLAGS) $(IINCDIR) -Winvalid-pch -MD -MP -c ${FIRMWARE}/xpatch.h  -o ${BUILDDIR}/xpatch.h.gch

${BUILDDIR}/xpatch.bin: ${BUILDDIR}/xpatch.cpp ${BUILDDIR}/xpatch.h.gch
#	@echo Removing previous build files
	@rm -f ${BUILDDIR}/xpatch.o ${BUILDDIR}/xpatch.elf ${BUILDDIR}/xpatch.bin ${BUILDDIR}/xpatch.d ${BUILDDIR}/xpatch.map ${BUILDDIR}/xpatch.lst
#	@echo Compiling patch dependencies
	@$(CPP) $(CCFLAGS) -H $(IINCDIR) -Winvalid-pch -MD -MP --include ${BUILDDIR}/xpatch.h -c ${BUILDDIR}/xpatch.cpp -o ${BUILDDIR}/xpatch.o
#	@echo Linking patch dependencies
ifeq ($(BOARDDEF), BOARD_KSOLOTI_CORE)
	@$(LD) $(LDFLAGS) ${BUILDDIR}/xpatch.o -Wl,-Map=${BUILDDIR}/xpatch.map,--cref,--just-symbols=${FIRMWARE}/build/$(ELFNAME).elf -o ${BUILDDIR}/xpatch.elf
else
	@$(LD) $(LDFLAGS) ${BUILDDIR}/xpatch.o -Wl,-Map=${BUILDDIR}/xpatch.map,--cref,--just-symbols=${FIRMWARE}/build/$(ELFNAME).elf -o ${BUILDDIR}/xpatch.elf
endif
#	@echo Creating LST file for debugging
	@$(DMP) -belf32-littlearm -marm --demangle --disassemble ${BUILDDIR}/xpatch.elf > ${BUILDDIR}/xpatch.lst
#   --source-comment --line-numbers 

#	@echo Creating binary
#	$(CP) -O binary -j .text  -j .init_array -j .rodata -j .rodata\* xpatch.elf xpatch.bin
#   -j .text.startup -j .text.memcpy
	@$(CP) -O binary ${BUILDDIR}/xpatch.elf ${BUILDDIR}/xpatch.bin
#	@echo Displaying size statistic
	@$(SIZ) --format=sysv ${BUILDDIR}/xpatch.elf
	@$(SIZ) --format=berkeley ${BUILDDIR}/xpatch.elf

.PHONY: clean

clean:
	@rm -f ${BUILDDIR}/xpatch.o ${BUILDDIR}/xpatch.elf ${BUILDDIR}/xpatch.bin ${BUILDDIR}/xpatch.d ${BUILDDIR}/xpatch.map ${BUILDDIR}/xpatch.lst ${BUILDDIR}/xpatch.h.gch