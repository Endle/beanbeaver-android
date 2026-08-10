#!/usr/bin/env bash
#
# Build bb-receipt-ffi for Android and install into bbreceiptkit/ (this tree):
#   - jniLibs/<abi>/libbb_mobile_ffi.so
#   - jniLibs/<abi>/libc++_shared.so
#   - optionally jniLibs/<abi>/libonnxruntime.so
#   - src/main/kotlin/uniffi/…  (UniFFI Kotlin glue)
#
# Run from the android/ directory (or any cwd; paths are rooted here):
#   ./build-android.sh
#   PROFILE=debug ./build-android.sh
#
# Prerequisites: rustup + cargo, Android NDK (ANDROID_NDK_HOME), ANDROID_API
# default 34 (app minSdk / Android 14).
#
# ORT_ANDROID_LIB_LOCATION=<dir> links ONNX Runtime from a source build instead
# of letting `ort` download a prebuilt one (see scripts/build-ort-android.sh;
# required for F-Droid, which never accepts prebuilt shared libraries):
#
#   ORT_ANDROID_LIB_LOCATION="$(./scripts/build-ort-android.sh --print-lib-location)" \
#     ./build-android.sh
#
# It deliberately is NOT spelled ORT_LIB_LOCATION, the variable ort-sys actually
# reads. That one would apply to every cargo invocation below, including the
# *host* uniffi-bindgen build, which would then try to link Android .a files
# into a macOS dylib. We pass it inline to the target build only.
set -euo pipefail

ANDROID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ANDROID_ROOT"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ANDROID_ROOT/target}"

# The umbrella library: spend-core's UniFFI seam, carrying the parse core's
# namespace in the same artifact. See beanbeaver-mobile-util's CLAUDE.md.
CRATE=bb-mobile-ffi
LIB_NAME=bb_mobile_ffi
PACKAGE=beanbeaver-android-ffi-build
PROFILE="${PROFILE:-release}"
profile_dir="$PROFILE"; [ "$PROFILE" = "debug" ] && profile_dir=debug

ANDROID_API="${ANDROID_API:-34}"
# Default arm64-v8a only: pyke `ort` has no prebuilt for x86_64-linux-android.
ABIS="${ABIS:-arm64-v8a}"

PKG="$ANDROID_ROOT/bbreceiptkit"
JNI="$PKG/src/main/jniLibs"
# Fallback only — codegen emits a uniffi/<namespace>/ tree (two of them now)
# and the whole tree is copied wholesale below.
GEN_OUT="$PKG/src/main/kotlin/uniffi/bb_mobile_ffi"
WORK="$CARGO_TARGET_DIR/android-work"

abi_to_target() {
  case "$1" in
    arm64-v8a)     echo aarch64-linux-android ;;
    armeabi-v7a)   echo armv7-linux-androideabi ;;
    x86_64)        echo x86_64-linux-android ;;
    x86)           echo i686-linux-android ;;
    *) echo "unknown ABI: $1" >&2; return 1 ;;
  esac
}

target_ort_key() {
  case "$1" in
    aarch64-linux-android) echo "aarch64-linux-android" ;;
    armv7-linux-androideabi) echo "armv7-linux-androideabi" ;;
    x86_64-linux-android) echo "x86_64-linux-android" ;;
    i686-linux-android) echo "i686-linux-android" ;;
    *) echo "$1" ;;
  esac
}

# The pinned NDK, read from the one place that declares it. AGP strips the .so
# and extracts Play debug symbols with this same NDK, so the compiler and the
# objcopy must not be different revisions.
NDK_VERSION="$(awk -F= '/^bb\.ndkVersion=/{gsub(/[[:space:]]/,"",$2); print $2}' \
  "$ANDROID_ROOT/gradle.properties" 2>/dev/null || true)"

find_ndk() {
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    echo "$ANDROID_NDK_HOME"; return
  fi
  if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    echo "$ANDROID_NDK_ROOT"; return
  fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  # Prefer the pin over "newest installed", so auto-discovery agrees with Gradle.
  if [ -n "${NDK_VERSION:-}" ] && [ -d "$sdk/ndk/$NDK_VERSION" ]; then
    echo "$sdk/ndk/$NDK_VERSION"; return
  fi
  if [ -d "$sdk/ndk" ]; then
    ls -1d "$sdk/ndk"/* 2>/dev/null | sort -V | tail -1
    return
  fi
  return 1
}

NDK="$(find_ndk || true)"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  cat >&2 <<EOF
error: Android NDK not found.
  Install the pinned NDK via Android Studio's SDK Manager, or:
    sdkmanager "ndk;${NDK_VERSION:-<version>}"
  build-android.sh auto-discovers \$ANDROID_HOME/ndk/${NDK_VERSION:-<version>}
  when ANDROID_NDK_HOME is unset.
EOF
  exit 1
fi
if [ -n "${NDK_VERSION:-}" ] && [ "$(basename "$NDK")" != "$NDK_VERSION" ]; then
  cat >&2 <<EOF
error: NDK version mismatch.
  using:  $NDK
  pinned: $NDK_VERSION  (bb.ndkVersion in gradle.properties)

AGP strips the .so and extracts Play debug symbols with the pinned NDK, so the
library has to be built with that same one. Either install it:
    sdkmanager "ndk;$NDK_VERSION"
or unset ANDROID_NDK_HOME so auto-discovery picks the pinned directory.
EOF
  exit 1
fi
echo ">> NDK: $NDK"

case "$(uname -s)" in
  Darwin) NDK_HOST=darwin-x86_64 ;;
  Linux)  NDK_HOST=linux-x86_64 ;;
  *) echo "unsupported host $(uname -s)" >&2; exit 1 ;;
esac
if [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ]; then
  if [ -d "$NDK/toolchains/llvm/prebuilt/darwin-arm64" ]; then
    NDK_HOST=darwin-arm64
  fi
fi
LLVM="$NDK/toolchains/llvm/prebuilt/$NDK_HOST"
if [ ! -d "$LLVM" ]; then
  echo "error: NDK llvm toolchain not at $LLVM" >&2
  exit 1
fi
# Host toolchain PATH (Apple clang), captured before the NDK is prepended. The
# host uniffi-bindgen build below must NOT see the NDK's llvm/bin: NDK 30 ships
# only a darwin-x86_64 host toolchain with no macOS compiler-rt, so a host link
# through it fails to find libclang_rt.osx. The Android target builds still get
# the NDK on PATH.
HOST_PATH="$PATH"
export PATH="$LLVM/bin:$PATH"

CARGO_CFG="$WORK/cargo-config.toml"
rm -rf "$WORK"; mkdir -p "$WORK"
{
  for abi in $ABIS; do
    target="$(abi_to_target "$abi")"
    case "$target" in
      armv7-linux-androideabi) clang_trip=armv7a-linux-androideabi ;;
      *) clang_trip="$target" ;;
    esac
    linker="$LLVM/bin/${clang_trip}${ANDROID_API}-clang"
    if [ ! -x "$linker" ]; then
      echo "error: missing linker $linker (API $ANDROID_API?)" >&2
      exit 1
    fi
    # max-page-size=16384: 16 KB-align the .so so Play accepts it on Android 15+.
    # (NDK r27+ lld already defaults to this; kept explicit for older NDKs.)
    cat <<EOF
[target.$target]
linker = "$linker"
ar = "$LLVM/bin/llvm-ar"
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]

EOF
  done
} > "$CARGO_CFG"

export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}"
cargo_config_args=(--config "$CARGO_CFG")

cargo_flags=(--lib -p "$CRATE")
[ "$PROFILE" = "release" ] && cargo_flags+=(--release)

for abi in $ABIS; do
  target="$(abi_to_target "$abi")"
  if ! rustup target list --installed | grep -qx "$target"; then
    echo ">> rustup target add $target"
    rustup target add "$target"
  fi
done

# Wipe rather than overlay. jniLibs/ is generated and git-ignored, and every
# file in it is reinstalled below, so nothing is lost — but a *stale* .so left
# behind is packaged into the APK all the same. The rename from
# libbb_receipt_ffi.so to libbb_mobile_ffi.so made that concrete: without this,
# any existing checkout ships both, ~450 MB of native library for an app that
# needs one. Same reasoning as the `rm -rf .../kotlin/uniffi` before codegen.
rm -rf "$JNI"
mkdir -p "$JNI"
find_ort_so() {
  local target="$1" abi="$2"
  if [ -n "${ORT_ANDROID_LIB_DIR:-}" ]; then
    for candidate in \
      "$ORT_ANDROID_LIB_DIR/$abi/libonnxruntime.so" \
      "$ORT_ANDROID_LIB_DIR/$target/libonnxruntime.so" \
      "$ORT_ANDROID_LIB_DIR/libonnxruntime.so"; do
      if [ -f "$candidate" ]; then echo "$candidate"; return; fi
    done
  fi
  local ort_cache="${ORT_CACHE:-$HOME/Library/Caches/ort.pyke.io}"
  [ -d "$ort_cache" ] || ort_cache="${XDG_CACHE_HOME:-$HOME/.cache}/ort.pyke.io"
  if [ -d "$ort_cache" ]; then
    local key; key="$(target_ort_key "$target")"
    local found
    found="$(find "$ort_cache" -type f -name 'libonnxruntime.so' \( -path "*/$key/*" -o -path "*/$target/*" -o -path "*/$abi/*" \) 2>/dev/null | head -1 || true)"
    if [ -n "$found" ]; then echo "$found"; return; fi
  fi
  return 1
}

for abi in $ABIS; do
  target="$(abi_to_target "$abi")"
  echo ">> building $CRATE for $target ($PROFILE) [abi=$abi]"
  # `env VAR=… cargo` rather than an exported VAR: see the header note on why
  # ORT_LIB_LOCATION must not survive into the host bindgen build below.
  ort_env=()
  if [ -n "${ORT_ANDROID_LIB_LOCATION:-}" ]; then
    if [ ! -f "$ORT_ANDROID_LIB_LOCATION/libonnxruntime_common.a" ]; then
      echo "error: no libonnxruntime_common.a in $ORT_ANDROID_LIB_LOCATION" >&2
      echo "  This must be the CMake binary dir (…/build-$abi/Release), not its parent." >&2
      exit 1
    fi
    echo "   ONNX Runtime from source: $ORT_ANDROID_LIB_LOCATION"
    ort_env=(env "ORT_LIB_LOCATION=$ORT_ANDROID_LIB_LOCATION")
  fi
  # ${a[@]+"${a[@]}"} — expanding an empty array as "${a[@]}" is an unbound
  # variable under `set -u` in bash 3.2, which is what /usr/bin/env bash still
  # resolves to on a stock macOS.
  ${ort_env[@]+"${ort_env[@]}"} \
    cargo build "${cargo_config_args[@]}" "${cargo_flags[@]}" --target "$target"

  so_src=""
  for cand in \
    "$CARGO_TARGET_DIR/$target/$profile_dir/lib${LIB_NAME}.so" \
    "$CARGO_TARGET_DIR/$target/$profile_dir/lib${LIB_NAME}.dylib"; do
    [ -f "$cand" ] && so_src="$cand" && break
  done
  if [ -z "$so_src" ]; then
    echo "error: missing lib${LIB_NAME}.so for $target under $CARGO_TARGET_DIR/$target/$profile_dir" >&2
    ls -la "$CARGO_TARGET_DIR/$target/$profile_dir" 2>/dev/null || true
    exit 1
  fi

  abi_dir="$JNI/$abi"
  mkdir -p "$abi_dir"
  cp "$so_src" "$abi_dir/lib${LIB_NAME}.so"
  echo "   installed lib${LIB_NAME}.so → $abi_dir/"

  case "$abi" in
    arm64-v8a)   ndk_abi=aarch64-linux-android ;;
    armeabi-v7a) ndk_abi=arm-linux-androideabi ;;
    x86_64)      ndk_abi=x86_64-linux-android ;;
    x86)         ndk_abi=i686-linux-android ;;
  esac
  cxx_shared="$LLVM/sysroot/usr/lib/$ndk_abi/libc++_shared.so"
  if [ ! -f "$cxx_shared" ]; then
    cxx_shared="$NDK/sources/cxx-stl/llvm-libc++/libs/$abi/libc++_shared.so"
  fi
  if [ -f "$cxx_shared" ]; then
    cp "$cxx_shared" "$abi_dir/libc++_shared.so"
    echo "   installed libc++_shared.so from $cxx_shared"
  else
    echo "warning: libc++_shared.so not found for $abi" >&2
  fi

  if command -v readelf >/dev/null 2>&1; then
    needs_ort="$(readelf -d "$so_src" 2>/dev/null | grep -F 'libonnxruntime' || true)"
  else
    needs_ort=""
  fi
  if [ -n "$needs_ort" ]; then
    if ort_so="$(find_ort_so "$target" "$abi")"; then
      cp "$ort_so" "$abi_dir/libonnxruntime.so"
      echo "   installed libonnxruntime.so from $ort_so"
    else
      echo "warning: $so_src needs libonnxruntime.so but none found for $abi" >&2
    fi
  else
    echo "   onnxruntime appears statically linked into lib${LIB_NAME}.so (no DT_NEEDED)"
  fi
done

echo ">> generating Kotlin bindings (host)"
PATH="$HOST_PATH" cargo build --lib -p "$CRATE" >/dev/null
HOST_LIB=""
for cand in \
  "$CARGO_TARGET_DIR/debug/lib${LIB_NAME}.dylib" \
  "$CARGO_TARGET_DIR/debug/lib${LIB_NAME}.so" \
  "$CARGO_TARGET_DIR/release/lib${LIB_NAME}.dylib" \
  "$CARGO_TARGET_DIR/release/lib${LIB_NAME}.so"; do
  [ -f "$cand" ] && HOST_LIB="$cand" && break
done
if [ -z "$HOST_LIB" ]; then
  echo "error: host lib${LIB_NAME} not found for uniffi-bindgen" >&2
  exit 1
fi

GEN="$WORK/gen"; mkdir -p "$GEN"
PATH="$HOST_PATH" cargo run -q -p "$PACKAGE" --bin uniffi-bindgen -- \
  generate --library "$HOST_LIB" --language kotlin --out-dir "$GEN"

rm -rf "$PKG/src/main/kotlin/uniffi"
mkdir -p "$PKG/src/main/kotlin"
if [ -d "$GEN/uniffi" ]; then
  cp -R "$GEN/uniffi" "$PKG/src/main/kotlin/"
else
  mkdir -p "$GEN_OUT"
  cp "$GEN"/*.kt "$GEN_OUT/" 2>/dev/null || {
    echo "error: no Kotlin sources in $GEN" >&2
    find "$GEN" -type f | head
    exit 1
  }
fi

mkdir -p "$PKG/src/main/kotlin/com/beanbeaver/bbreceiptkit"
cat > "$PKG/src/main/kotlin/com/beanbeaver/bbreceiptkit/BuildInfo.kt" <<EOF
package com.beanbeaver.bbreceiptkit

/** Generated by build-android.sh — do not edit by hand. */
object BuildInfo {
    const val PROFILE: String = "$PROFILE"
    const val ABIS: String = "$ABIS"
    const val ANDROID_API: Int = $ANDROID_API
}
EOF

rm -rf "$WORK"

cat <<EOF

✅ Android native + UniFFI Kotlin installed into bbreceiptkit/
   jniLibs:     $JNI/{$(echo $ABIS | tr ' ' ',')}/
   kotlin glue: $PKG/src/main/kotlin/uniffi/

Next:
  ./gradlew :app:assembleFullDebug    # Play build (GMS document scanner)
  ./gradlew :app:assembleFossDebug    # F-Droid build (no Play services)
EOF
