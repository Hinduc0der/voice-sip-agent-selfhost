#!/usr/bin/env bash
# Builds pjproject's pjsua2 SWIG/Java binding for Android and drops the results into this module:
#   src/generated/java      org.pjsip.pjsua2.* (SWIG output)
#   src/generated/jniLibs   libpjsua2.so + libc++_shared.so per ABI
# Both are git-ignored; rerun this script after a clean checkout or a pjproject upgrade.
#
# Media profile: null sound device only (no Android audio backend is compiled in, so pjsua never
# touches AudioManager), no video, G.711 + mono Opus (up to 16 kHz) + bundled libsrtp. TLS/DTLS via a static OpenSSL
# LTS build linked into libpjsua2.so.
set -euo pipefail

PJ_VERSION=2.17
PJ_SHA256=065fe06c06788d97c35f563796d59f00ce52fe9558a52d7b490a042a966facce
OSSL_VERSION=3.5.8
OSSL_SHA256=a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2
OPUS_VERSION=1.6.1
OPUS_SHA256=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
ABIS=${ABIS:-arm64-v8a}
APP_PLATFORM=${APP_PLATFORM:-android-34}
# Use one NDK toolchain for ThinLTO across all static dependencies and the JNI wrapper.
NATIVE_CFLAGS="-O2 -fPIC -flto=thin -ffunction-sections -fdata-sections -fvisibility=hidden"
NATIVE_LDFLAGS="-flto=thin -Wl,--gc-sections"

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
OSSL_TARBALL="$WORK/openssl-$OSSL_VERSION.tar.gz"
if [ ! -f "$OSSL_TARBALL" ]; then
  curl -fsSL -o "$OSSL_TARBALL.part" \
    "https://github.com/openssl/openssl/releases/download/openssl-$OSSL_VERSION/openssl-$OSSL_VERSION.tar.gz"
  mv "$OSSL_TARBALL.part" "$OSSL_TARBALL"
fi
echo "$OSSL_SHA256  $OSSL_TARBALL" | shasum -a 256 -c -
OPUS_TARBALL="$WORK/opus-$OPUS_VERSION.tar.gz"
if [ ! -f "$OPUS_TARBALL" ]; then
  curl -fsSL -o "$OPUS_TARBALL.part" "https://downloads.xiph.org/releases/opus/opus-$OPUS_VERSION.tar.gz"
  mv "$OPUS_TARBALL.part" "$OPUS_TARBALL"
fi
echo "$OPUS_SHA256  $OPUS_TARBALL" | shasum -a 256 -c -
HOST_TAG=$(ls "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt" | head -1)
LLVM_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"
API=${APP_PLATFORM#android-}
JOBS=$(sysctl -n hw.ncpu 2>/dev/null || nproc)

GEN_JAVA="$MODULE/src/generated/java"
GEN_LIBS="$MODULE/src/generated/jniLibs"
rm -rf "$GEN_JAVA" "$GEN_LIBS"
mkdir -p "$GEN_JAVA" "$GEN_LIBS"

for ABI in $ABIS; do
  SRC="$WORK/$ABI/pjproject-$PJ_VERSION"
  rm -rf "$WORK/$ABI"
  mkdir -p "$WORK/$ABI"
  tar xzf "$TARBALL" -C "$WORK/$ABI"

  # Static OpenSSL for this ABI, installed into $WORK/$ABI/openssl.
  case $ABI in
    arm64-v8a) OSSL_TARGET=android-arm64; OPUS_HOST=aarch64-linux-android; OPUS_CC_TARGET=$OPUS_HOST ;;
    armeabi-v7a) OSSL_TARGET=android-arm; OPUS_HOST=arm-linux-androideabi; OPUS_CC_TARGET=armv7a-linux-androideabi ;;
    x86_64) OSSL_TARGET=android-x86_64; OPUS_HOST=x86_64-linux-android; OPUS_CC_TARGET=$OPUS_HOST ;;
    x86) OSSL_TARGET=android-x86; OPUS_HOST=i686-linux-android; OPUS_CC_TARGET=$OPUS_HOST ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
  esac
  SSL_PREFIX="$WORK/$ABI/openssl"
  tar xzf "$OSSL_TARBALL" -C "$WORK/$ABI"
  (
    cd "$WORK/$ABI/openssl-$OSSL_VERSION"
    export PATH="$LLVM_BIN:$PATH"
    export AR="$LLVM_BIN/llvm-ar" RANLIB="$LLVM_BIN/llvm-ranlib"
    ./Configure "$OSSL_TARGET" -D__ANDROID_API__="$API" no-shared no-tests no-apps no-docs no-engine \
      no-comp no-ssl3 no-weak-ssl-ciphers --prefix="$SSL_PREFIX" --openssldir=/nonexistent --libdir=lib \
      $NATIVE_CFLAGS
    make -j"$JOBS" build_libs
    make install_dev
  )
  # Static libopus.
  OPUS_PREFIX="$WORK/$ABI/opus"
  tar xzf "$OPUS_TARBALL" -C "$WORK/$ABI"
  (
    cd "$WORK/$ABI/opus-$OPUS_VERSION"
    export CC="$LLVM_BIN/${OPUS_CC_TARGET}${API}-clang"
    export AR="$LLVM_BIN/llvm-ar" RANLIB="$LLVM_BIN/llvm-ranlib" STRIP="$LLVM_BIN/llvm-strip"
    CFLAGS="$NATIVE_CFLAGS" LDFLAGS="$NATIVE_LDFLAGS" ./configure --host="$OPUS_HOST" --prefix="$OPUS_PREFIX" \
      --disable-shared --enable-static --with-pic --disable-doc --disable-extra-programs
    make -j"$JOBS"
    make install
  )
  cp "$HERE/config_site.h" "$SRC/pjlib/include/pj/config_site.h"
  # Accept "*." wildcard server certificates (RFC 6125); stock pjsip rejects them per RFC 5922.
  patch -p1 -d "$SRC" < "$HERE/pjsip-tls-wildcard.patch"
  # Bulk byte[] <-> ByteVector copies; the stock binding is a List<Short> with one JNI call per byte.
  # Must precede %template(ByteVector), so it goes right after the and_string.i include.
  I="$SRC/pjsip-apps/src/swig/pjsua2.i"
  awk -v ext="$HERE/pjsua2-java-ext.i" '{ print } /^%include "and_string.i"/ { while ((getline l < ext) > 0) print l }' "$I" > "$I.new"
  mv "$I.new" "$I"
  grep -q vsaOutCap "$I"

  (
    cd "$SRC"
    # configure-android replaces CXXFLAGS, but carries CPPFLAGS into both C and C++.
    export CPPFLAGS="${CPPFLAGS:-} $NATIVE_CFLAGS"
    export LDFLAGS="${LDFLAGS:-} $NATIVE_LDFLAGS"
    TARGET_ABI=$ABI APP_PLATFORM=$APP_PLATFORM ./configure-android --use-ndk-cflags \
      --with-ssl="$SSL_PREFIX" --with-opus="$OPUS_PREFIX" \
      --disable-sound --disable-video --disable-upnp \
      --disable-silk --disable-bcg729 --disable-lyra --disable-opencore-amr \
      --disable-openh264 --disable-vpx --disable-ffmpeg --disable-v4l2 --disable-sdl \
      --disable-libyuv --disable-libwebrtc --disable-android-mediacodec \
      --disable-gsm-codec --disable-ilbc-codec --disable-speex-codec --disable-g722-codec --disable-g7221-codec \
      --disable-l16-codec --disable-speex-aec
    make dep
    grep -q "PJ_HAS_SSL_SOCK 1" pjlib/include/pj/compat/os_auto.h || { echo "SSL not enabled" >&2; exit 1; }
    grep -q "PJMEDIA_HAS_OPUS_CODEC 1" pjmedia/include/pjmedia-codec/config_auto.h || { echo "Opus not enabled" >&2; exit 1; }
    grep -q "PJMEDIA_HAS_G722_CODEC 0" pjmedia/include/pjmedia-codec/config_auto.h || { echo "G.722 not disabled" >&2; exit 1; }
    make -j"$JOBS"
    # Localize dependencies only at the shared JNI link, not configure's executables.
    make -C pjsip-apps/src/swig/java \
      LDFLAGS="$LDFLAGS -Wl,--version-script=$HERE/pjsua2.exports.map -Wl,--exclude-libs,ALL"
  )

  OUT="$SRC/pjsip-apps/src/swig/java/android/pjsua2/src/main"
  LIB="$OUT/jniLibs/$ABI/libpjsua2.so"
  EXPORTS="$WORK/$ABI/pjsua2.exports.txt"
  "$LLVM_BIN/llvm-nm" -D --defined-only --extern-only "$LIB" > "$EXPORTS"
  grep -q 'Java_org_pjsip_pjsua2_pjsua2JNI_' "$EXPORTS" || { echo "JNI exports missing" >&2; exit 1; }
  awk '$NF !~ /^(Java_org_pjsip_pjsua2_pjsua2JNI_|JNI_OnLoad$|JNI_OnUnload$)/ {
         print "Unexpected native export: " $NF > "/dev/stderr"; bad=1
       } END { exit bad }' "$EXPORTS"
  mkdir -p "$GEN_LIBS/$ABI"
  cp "$OUT/jniLibs/$ABI/"*.so "$GEN_LIBS/$ABI/"
  # Java sources are ABI independent; the last ABI's copy wins.
  rm -rf "$GEN_JAVA/org"
  cp -R "$OUT/java/org" "$GEN_JAVA/"
done

ls -l "$GEN_LIBS"/*/
echo "pjsua2 $PJ_VERSION built for: $ABIS"
