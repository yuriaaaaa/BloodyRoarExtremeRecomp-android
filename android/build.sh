#!/usr/bin/env bash
# Builds the private Android APK from your own Bloody Roar Extreme (USA) default.xbe.
#   GAME_XBE=/path/to/default.xbe android/build.sh
# Needs: the Android SDK (platform 35, build-tools 35) with NDK 27.2.12479018, CMake 3.20+, Ninja, Gradle 8,
# and SDL3 + glslang built for arm64 into $DEPS, with zydis, tomlplusplus and imgui checkouts in $SRC.
# Output: android/app/build/outputs/apk/release/app-release.apk
set -euo pipefail
cd "$(dirname "$0")/.."

GAME_XBE="${GAME_XBE:-assets/default.xbe}"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
SRC="${SRC:-$HOME/deps}"
DEPS="${DEPS:-$SRC/out/android/prefix}"
NDK="$SDK/ndk/27.2.12479018"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
GRADLE="${GRADLE:-gradle}"
BUILD="$(pwd)/build"

if [ "$GAME_XBE" != assets/default.xbe ]; then mkdir -p assets && cp "$GAME_XBE" assets/default.xbe; fi

echo "== host tools"
cmake -S android/host -B "$BUILD/host" -G Ninja -DCMAKE_BUILD_TYPE=Release -DDEPS="$SRC" >/dev/null
ninja -C "$BUILD/host" xbe_recomp

if [ ! -d generated/game ] || [ assets/default.xbe -nt generated/game ] || [ config/game.toml -nt generated/game ]; then
    echo "== recompiling default.xbe"
    "$BUILD/host/xbe_recomp" config/game.toml --xdk sdk/xdk
    touch generated/game
fi

echo "== native library"
cmake -S android/native -B "$BUILD/android" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DCMAKE_BUILD_TYPE=Release \
    -DDEPS_PREFIX="$DEPS" -DIMGUI_DIR="$SRC/imgui" >/dev/null
ninja -C "$BUILD/android"

echo "== staging"
OUT="$BUILD/android-apk"
rm -rf "$OUT" && mkdir -p "$OUT/jniLibs/arm64-v8a"
"$STRIP" --strip-unneeded -o "$OUT/jniLibs/arm64-v8a/libmain.so" "$BUILD/android/libmain.so"
cp "$DEPS/lib/libSDL3.so" "$OUT/jniLibs/arm64-v8a/"

KS="${PRIMALFURY_KEYSTORE:-$BUILD/primalfury.jks}"
if [ ! -f "$KS" ]; then
    echo "== creating signing key $KS"
    keytool -genkeypair -keystore "$KS" -storepass "${PRIMALFURY_KEYSTORE_PASS:-android}" -keypass "${PRIMALFURY_KEYSTORE_PASS:-android}" \
        -alias primalfury -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Primal Fury Android" >/dev/null
fi
export PRIMALFURY_KEYSTORE="$(cd "$(dirname "$KS")" && pwd)/$(basename "$KS")"

echo "== apk"
echo "sdk.dir=$SDK" > android/local.properties
(cd android && "$GRADLE" --no-daemon -q assembleRelease)
echo "APK: $(pwd)/android/app/build/outputs/apk/release/app-release.apk"
