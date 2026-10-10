#!/usr/bin/env bash
# Builds pjproject's pjsua2 SWIG/Java binding for Android and drops the results into this module:
#   src/generated/java      org.pjsip.pjsua2.* (SWIG output)
#   src/generated/jniLibs   libpjsua2.so + libc++_shared.so per ABI
# Both are git-ignored; rerun this script after a clean checkout or a pjproject upgrade.
#
# Media profile: null sound device only (no Android audio backend is compiled in, so pjsua never
# touches AudioManager), no video, no TLS yet, G.711 + G.722 + bundled libsrtp.
set -euo pipefail

PJ_VERSION=2.17
PJ_SHA256=065fe06c06788d97c35f563796d59f00ce52fe9558a52d7b490a042a966facce
ABIS=${ABIS:-arm64-v8a}
APP_PLATFORM=${APP_PLATFORM:-android-34}

HERE=$(cd "$(dirname "$0")" && pwd)
MODULE=$(dirname "$HERE")
WORK=${PJ_WORK:-$MODULE/build/pjproject}
SDK=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$MODULE/../local.properties" 2>/dev/null)}
export ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-$SDK/ndk/28.2.13676358}
[ -d "$ANDROID_NDK_ROOT" ] || { echo "NDK not found: $ANDROID_NDK_ROOT" >&2; exit 1; }
command -v swig >/dev/null || { echo "swig not found (brew install swig)" >&2; exit 1; }

mkdir -p "$WORK"
TARBALL="$WORK/pjproject-$PJ_VERSION.tar.gz"
if [ ! -f "$TARBALL" ]; then
  curl -fsSL -o "$TARBALL.part" "https://github.com/pjsip/pjproject/archive/refs/tags/$PJ_VERSION.tar.gz"
  mv "$TARBALL.part" "$TARBALL"
fi
echo "$PJ_SHA256  $TARBALL" | shasum -a 256 -c -

GEN_JAVA="$MODULE/src/generated/java"
GEN_LIBS="$MODULE/src/generated/jniLibs"
rm -rf "$GEN_JAVA" "$GEN_LIBS"
mkdir -p "$GEN_JAVA" "$GEN_LIBS"

for ABI in $ABIS; do
  SRC="$WORK/$ABI/pjproject-$PJ_VERSION"
  rm -rf "$WORK/$ABI"
  mkdir -p "$WORK/$ABI"
  tar xzf "$TARBALL" -C "$WORK/$ABI"
  cp "$HERE/config_site.h" "$SRC/pjlib/include/pj/config_site.h"
  # Bulk byte[] <-> ByteVector copies; the stock binding is a List<Short> with one JNI call per byte.
  # Must precede %template(ByteVector), so it goes right after the and_string.i include.
  I="$SRC/pjsip-apps/src/swig/pjsua2.i"
  awk -v ext="$HERE/pjsua2-java-ext.i" '{ print } /^%include "and_string.i"/ { while ((getline l < ext) > 0) print l }' "$I" > "$I.new"
  mv "$I.new" "$I"
  grep -q vsaOutCap "$I"

  (
    cd "$SRC"
    TARGET_ABI=$ABI APP_PLATFORM=$APP_PLATFORM ./configure-android --use-ndk-cflags \
      --disable-sound --disable-video --disable-ssl --disable-upnp \
      --disable-opus --disable-silk --disable-bcg729 --disable-lyra --disable-opencore-amr \
      --disable-openh264 --disable-vpx --disable-ffmpeg --disable-v4l2 --disable-sdl \
      --disable-libyuv --disable-libwebrtc --disable-android-mediacodec \
      --disable-gsm-codec --disable-ilbc-codec --disable-speex-codec --disable-g7221-codec \
      --disable-l16-codec --disable-speex-aec
    make dep
    make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
    make -C pjsip-apps/src/swig/java
  )

  OUT="$SRC/pjsip-apps/src/swig/java/android/pjsua2/src/main"
  mkdir -p "$GEN_LIBS/$ABI"
  cp "$OUT/jniLibs/$ABI/"*.so "$GEN_LIBS/$ABI/"
  # Java sources are ABI independent; the last ABI's copy wins.
  rm -rf "$GEN_JAVA/org"
  cp -R "$OUT/java/org" "$GEN_JAVA/"
done

ls -l "$GEN_LIBS"/*/
echo "pjsua2 $PJ_VERSION built for: $ABIS"
