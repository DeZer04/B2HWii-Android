#!/bin/sh
# Builds libbasara_disc.so (the launcher's ISO/RVZ import, runtime/disc) on its own with the Android NDK,
# as runtime/cmake/PublicProducts.cmake does: disc_extract.cpp + disc_import_jni.cpp + zstd's decoder,
# static libc++, 16 KB pages. For the Java-only Indonesian edition, which takes the other native
# libraries from the official release; the result goes to generated/android-native/jniLibs/arm64-v8a.
#   NDK_BIN=<ndk>/toolchains/llvm/prebuilt/windows-x86_64/bin ZSTD_LIB=<zstd-1.5.7>/lib sh tools/b-disc-ndk.sh
set -e
: "${NDK_BIN:?set NDK_BIN to the NDK's llvm bin folder}"
: "${ZSTD_LIB:?set ZSTD_LIB to zstd-1.5.7/lib}"
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OBJ="$ROOT/generated/disc-ndk"
OUT="$ROOT/generated/android-native/jniLibs/arm64-v8a"
mkdir -p "$OBJ" "$OUT"
EXE=; [ -x "$NDK_BIN/clang.exe" ] && EXE=.exe
for f in "$ZSTD_LIB"/common/*.c "$ZSTD_LIB"/decompress/*.c; do
  "$NDK_BIN/clang$EXE" --target=aarch64-linux-android29 -c -O2 -fPIC -fvisibility=hidden -DZSTD_DISABLE_ASM \
    -I"$ZSTD_LIB" -I"$ZSTD_LIB/common" "$f" -o "$OBJ/$(basename "$f" .c).o"
done
"$NDK_BIN/clang++$EXE" --target=aarch64-linux-android29 -std=c++20 -O2 -fPIC -fexceptions -fvisibility=hidden \
  -I"$ZSTD_LIB" -shared -static-libstdc++ -Wl,-soname,libbasara_disc.so -Wl,-z,max-page-size=16384 \
  -Wl,--gc-sections -s "$ROOT/runtime/disc/disc_extract.cpp" "$ROOT/runtime/disc/disc_import_jni.cpp" "$OBJ"/*.o \
  -o "$OUT/libbasara_disc.so"
ls -l "$OUT/libbasara_disc.so"
