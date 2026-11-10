#!/usr/bin/env bash
# EmuCoreC: build FFmpeg 8.1.1 for Android (aarch64) with the NDK.
#
# The RPCS3 3rdparty/ffmpeg submodule only carries headers + vcpkg triplets,
# and the RPCS3-Android/ffmpeg-android prebuilts are stuck at 5.1, which is too
# old for the FFmpeg 6+ APIs RPCS3 master uses. So we build the matching
# upstream release from source. Output lands in app/build/ffmpeg-android and is
# consumed by android/ffmpeg.cmake via 3rdparty_ffmpeg.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION=8.1.1
API=29
LOCAL="${LOCALAPPDATA//\\//}"
NDK="${NDK_DIR:-$LOCAL/Android/Sdk/ndk/29.0.14206865}"
SRC_DIR="${FFMPEG_SRC_DIR:-$ROOT/app/build/ffmpeg-src}"
OUT_DIR="${FFMPEG_OUT_DIR:-$ROOT/app/build/ffmpeg-android}"
MAKE="${FFMPEG_MAKE:-$LOCAL/mingw/mingw64/bin/mingw32-make.exe}"
JOBS="${FFMPEG_JOBS:-8}"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
CC="$TOOLCHAIN/bin/clang"
AR="$LOCAL/mingw/mingw64/bin/ar"
RANLIB="$LOCAL/mingw/mingw64/bin/ranlib"
NM="$LOCAL/mingw/mingw64/bin/nm"
STRIP="$TOOLCHAIN/bin/llvm-strip"
SYSROOT="$TOOLCHAIN/sysroot"
HOST_CC="$LOCAL/mingw/mingw64/bin/gcc"

# Download and unpack the source once.
if [ ! -f "$SRC_DIR/FFmpeg-n$VERSION/configure" ]; then
  mkdir -p "$SRC_DIR"
  if [ ! -f "$SRC_DIR/ffmpeg-$VERSION.tar.gz" ]; then
    curl -L --fail -o "$SRC_DIR/ffmpeg-$VERSION.tar.gz" \
      "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n$VERSION.tar.gz"
  fi
  tar -xzf "$SRC_DIR/ffmpeg-$VERSION.tar.gz" -C "$SRC_DIR"
fi

cd "$SRC_DIR/FFmpeg-n$VERSION"

mkdir -p "$OUT_DIR"

./configure \
  --prefix="$OUT_DIR" \
  --cc="$CC" \
  --host-cc="$HOST_CC" \
  --ar="$AR" --ranlib="$RANLIB" --nm="$NM" --strip="$STRIP" \
  --target-os=android \
  --arch=aarch64 \
  --enable-cross-compile \
  `# mingw32-make lower-cases the .S prerequisites on Windows, so clang is handed a plain .s` \
  `# and skips the C preprocessor -- but FFmpeg's aarch64 asm needs it (#include "asm.S",` \
  `# the function/const macros). Pinning the language keeps that working whatever the case.` \
  --as="$CC -x assembler-with-cpp" \
  --extra-cflags="--target=aarch64-linux-android$API --sysroot=$SYSROOT -O3 -fPIC -fvisibility=hidden" \
  --extra-ldflags="--target=aarch64-linux-android$API --sysroot=$SYSROOT" \
  --enable-static --disable-shared --enable-pic \
  `# -fvisibility=hidden (in extra-cflags above): the hand-written NEON asm addresses FFmpeg's` \
  `# tables PC-relative, which only links into libemucorec-core.so while those symbols stay` \
  `# unexported.` \
  `# NEON assembly ON: it was --disable-asm, i.e. plain C for every decoder -- PS3 cutscene` \
  `# video (MPEG-2/H.264/VC-1), scaling and audio resampling. FFMPEG_ASM=0 restores plain C.` \
  $( [ "${FFMPEG_ASM:-1}" = "1" ] && echo "--enable-asm --enable-neon --enable-inline-asm" || echo "--disable-asm" ) \
  --disable-programs --disable-doc --disable-debug \
  --disable-avdevice --disable-avfilter \
  --disable-network --disable-autodetect

# mingw32-make defaults to cmd.exe as its shell, which breaks ffmpeg's
# response-file recipe (echo $^ > file). Force a POSIX shell.
#
# -k: that same recipe is what archives each library, so without -k make stops at
# the first library and leaves most objects -- including every NEON .S -- unbuilt,
# which then shows up much later as undefined ff_*_neon symbols in the core link.
# The archive step is redone below from the object globs, so make may keep going.
BUILD_LOG="$OUT_DIR/build.log"
"$MAKE" SHELL="$(command -v sh)" -k -j"$JOBS" > "$BUILD_LOG" 2>&1 || true
"$MAKE" SHELL="$(command -v sh)" install >> "$BUILD_LOG" 2>&1 || true

# -k also means genuine compiler errors no longer abort the build, and re-archiving
# would happily pack an incomplete library. Fail loudly instead.
if grep -q "error:" "$BUILD_LOG"; then
  echo "EmuCoreC: FFmpeg build failed, first compiler errors:" >&2
  grep "error:" "$BUILD_LOG" | head -20 >&2
  exit 1
fi

# The ar @response-file step is unreliable under Windows make (the .objs list
# file is frequently missing when ar runs); re-archive from the object globs.
cd "$SRC_DIR/FFmpeg-n$VERSION"
for lib in libavcodec libavformat libavutil libswscale libswresample; do
  if [ ! -s "$lib/$lib.a" ] || [ "$(stat -c%s "$lib/$lib.a")" -lt 1000 ]; then
    echo "EmuCoreC: re-archiving $lib from object glob"
    "$AR" rcs "$lib/$lib.a" $(find "$lib" -name "*.o" ! -path "*tests*")
  fi
done

mkdir -p "$OUT_DIR/lib"
cp libavcodec/libavcodec.a libavformat/libavformat.a \
   libavutil/libavutil.a libswscale/libswscale.a libswresample/libswresample.a \
   "$OUT_DIR/lib/"

# Stamped for the Gradle task: it only skips the (slow) FFmpeg build while this
# matches the mode the task expects, so flipping FFMPEG_ASM rebuilds instead of
# silently linking the previous flavour.
if [ "${FFMPEG_ASM:-1}" = "1" ]; then echo "asm" > "$OUT_DIR/ffmpeg-mode.txt"; else echo "c" > "$OUT_DIR/ffmpeg-mode.txt"; fi

echo "EmuCoreC: FFmpeg $VERSION built into $OUT_DIR ($(cat "$OUT_DIR/ffmpeg-mode.txt"))"
