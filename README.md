# Gearmulator MD/MM – Android app

Touch front panel (LCD, 8 data knobs + LEVEL/SOUND, all buttons, 16 trigs with LEDs) for the
Machinedrum / Monomachine emulation. Audio via AAudio. arm64-v8a only.

## Requirements
- Android Studio (Koala+), NDK, CMake 3.22 (SDK Manager)
- A **complete** checkout: `git clone --recursive` of the repo. The zip you got has empty
  submodule folders (`source/cpu/dsp56300`, `source/cpu/mc68k`, `source/3rdparty/asmjit`, ...),
  the build fails without them.
- Firmware ROMs (8 MiB .bin) from your own hardware. Not included.

## Build
1. Copy this folder over `<repo>/source/Android/`.
2. Open `<repo>/source/Android` in Android Studio, let it sync (it creates the Gradle wrapper), Run.
   CLI: `gradle wrapper && ./gradlew assembleDebug`. Prefer a *release* build: the DSP JIT/emulation is heavy.
3. In the app: import the ROM once per model, then Start.

## Controls
- Buttons are momentary and multi-touch (hold FUNC, tap another button).
- Drag a knob up/down to turn it.
- Back button stops the emulation.
