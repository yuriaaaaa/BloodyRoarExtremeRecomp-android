# Primal Fury (Android)

An arm64 Android build of this recomp, named Primal Fury. It uses SDL3, a Vulkan 1.1 renderer (shaders compiled with
glslang's HLSL front end) and a launcher app with settings and touch controls.

No game code or data is in this repository and no APK is published. You build the APK yourself from the
`default.xbe` of your own USA disc (title 48550001, SHA-1 `e58541178170e32ba71babce2c9ccf905bdb4db4`).

## Building

```sh
GAME_XBE=/path/to/default.xbe ANDROID_HOME=~/android-sdk SRC=~/deps android/build.sh
```

You need:

- the Android SDK (platform 35, build-tools 35) with NDK 27.2.12479018
- CMake 3.20+, Ninja and Gradle 8
- checkouts of zydis, tomlplusplus and imgui in `$SRC`
- SDL3 and glslang built for arm64 into `$SRC/out/android/prefix`

The script recompiles the xbe into `generated/`, which git ignores, builds `libmain.so` and signs the APK with
`build/primalfury.jks`, or with the key named by `PRIMALFURY_KEYSTORE`. Keep the same key so updates install over the old
app. If Maven Central returns HTTP 429, run the script again.

## Playing

1. Copy the game to `Internal storage/PrimalFury`, either as the ISO or as the extracted folder
   (`default.xbe`, `fpack/`, `cmn/` …).
2. Start the app.

The launcher covers:

- resolution scale
- aspect ratio and stretch
- vsync and the FPS cap
- the debug log, written to `PrimalFury/logs`

## Controls

- The touch controls, including a D-pad, can be moved and resized.
- A three-finger tap opens the settings during play.
- The touch controls hide when a controller connects.

## Android-specific changes

- **Movies:** played through `D3DDevice_UpdateOverlay`, which converts YUY2/UYVY to BGRA. While a movie plays,
  frames are paced at 30 fps. Menus and gameplay keep the FPS cap.
- **Default render states:** come from `D3D_InitDefaultState`, using the layout entry `CDevice_AutoDepth`.
- **Complex render-state setters:** named in `config/game.toml`, including texture factor, ZEnable and StencilEnable.
- **Depth bias:** SolidOffset and DepthOffset are applied as Vulkan depth bias.
- **Audio:** rendered in 10 ms chunks on a wall clock, so the guest's play cursors advance evenly.
