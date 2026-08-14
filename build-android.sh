#!/usr/bin/env bash
#
# Build bb-mobile-ffi for Android and install it into bbreceiptkit/ (this tree):
#   - jniLibs/<abi>/libbb_mobile_ffi.so
#   - jniLibs/<abi>/libc++_shared.so
#   - src/main/kotlin/uniffi/…  (UniFFI Kotlin glue)
#
# Run from anywhere; paths are rooted at this file.
#   ./build-android.sh
#   PROFILE=debug ./build-android.sh
#
# Prerequisites: rustup + cargo, and the pinned Android NDK.
#
# WHAT MAKES THIS LONGER THAN "cargo build": three toolchains have to be talked
# into agreeing, and none of them discovers the others.
#
#   1. cargo cannot find the NDK linker      -> step_write_cargo_config
#   2. the host build must NOT see the NDK   -> step_resolve_host_toolchain
#   3. the NDK must match the one AGP uses   -> step_resolve_ndk
#
# Each is a numbered step below, in the order main() runs them. Everything else
# is a few lines of copying.
#
# ONNX Runtime is built FROM SOURCE by default — scripts/build-ort-android.sh is
# invoked automatically, for every flavour and every distribution channel.
#
# It is not an F-Droid-only concern, which is how it started. Letting the Play
# build link pyke's prebuilt while only the fdroid build compiled ORT meant the two
# store artifacts carried *different* OCR engines, so a scan bug could reproduce
# on one channel and not the other; and because only fdroid-touching PRs built from
# source, a broken `ort` bump stayed invisible until an F-Droid submission, with
# no recent known-good state to bisect against. One engine everywhere fixes both:
# a bad bump now fails on the PR that causes it.
#
# The first build is slow (~4-6 min to clone and compile); after that the tree in
# target/ort makes it a no-op. Two escape hatches, in order of preference:
#
#   ORT_ANDROID_LIB_LOCATION=<dir>   # link this tree; what CI's cache restores
#   BB_ORT=prebuilt                  # fall back to pyke's CDN download
#
# BB_ORT=prebuilt exists for emergencies — an upstream break that would otherwise
# block a release. It is a divergence, so it should not survive past the fix.
#
# It deliberately is NOT spelled ORT_LIB_LOCATION, the variable ort-sys actually
# reads. That one would apply to every cargo invocation below, including the
# *host* uniffi-bindgen build, which would then try to link Android .a files
# into a macOS dylib. We pass it inline to the target build only.
set -euo pipefail

# --------------------------------------------------------------------------
# Configuration. These four env vars are the whole control surface.
# --------------------------------------------------------------------------
ANDROID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ANDROID_ROOT"

PROFILE="${PROFILE:-release}"          # release | debug
ANDROID_API="${ANDROID_API:-34}"       # app minSdk
ABIS="${ABIS:-arm64-v8a}"              # arm64 only: ort ships no x86_64-android prebuilt
BB_ORT="${BB_ORT:-source}"             # source | prebuilt

export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ANDROID_ROOT/target}"
export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}"

# The umbrella library: spend-core's UniFFI seam, carrying the parse core's
# namespace in the same artifact. See beanbeaver-mobile-util's CLAUDE.md.
CRATE=bb-mobile-ffi
LIB_NAME=bb_mobile_ffi
PACKAGE=beanbeaver-android-ffi-build

PKG="$ANDROID_ROOT/bbreceiptkit"
JNI="$PKG/src/main/jniLibs"
# Fallback only — codegen emits a uniffi/<namespace>/ tree (two of them now)
# and the whole tree is copied wholesale.
GEN_OUT="$PKG/src/main/kotlin/uniffi/$LIB_NAME"
WORK="$CARGO_TARGET_DIR/android-work"
CARGO_CFG="$WORK/cargo-config.toml"

profile_dir="$PROFILE"; [ "$PROFILE" = "debug" ] && profile_dir=debug

# Set by the steps below, read by later ones.
NDK=""          # step_resolve_ndk
LLVM=""         # step_resolve_host_toolchain
HOST_PATH=""    # step_resolve_host_toolchain

abi_to_target() {
  case "$1" in
    arm64-v8a)     echo aarch64-linux-android ;;
    armeabi-v7a)   echo armv7-linux-androideabi ;;
    x86_64)        echo x86_64-linux-android ;;
    x86)           echo i686-linux-android ;;
    *) echo "unknown ABI: $1" >&2; return 1 ;;
  esac
}

# --------------------------------------------------------------------------
# Step 1 — find the NDK, and refuse any but the pinned one.
#
# AGP strips the .so and extracts Play debug symbols with the NDK named in
# gradle.properties. If cargo compiles with a different revision, AGP silently
# stops stripping and stops producing symbols — that is how v0.4.0 reached Play
# as a 224 MB bundle. So a mismatch is an error here, not a warning.
# --------------------------------------------------------------------------
step_resolve_ndk() {
  local pinned sdk
  pinned="$(awk -F= '/^bb\.ndkVersion=/{gsub(/[[:space:]]/,"",$2); print $2}' \
    "$ANDROID_ROOT/gradle.properties" 2>/dev/null || true)"

  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK="$ANDROID_NDK_HOME"
  elif [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    NDK="$ANDROID_NDK_ROOT"
  else
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    # Prefer the pin over "newest installed", so auto-discovery agrees with Gradle.
    if [ -n "$pinned" ] && [ -d "$sdk/ndk/$pinned" ]; then
      NDK="$sdk/ndk/$pinned"
    elif [ -d "$sdk/ndk" ]; then
      NDK="$(ls -1d "$sdk/ndk"/* 2>/dev/null | sort -V | tail -1)"
    fi
  fi

  if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    cat >&2 <<EOF
error: Android NDK not found.
  Install the pinned NDK via Android Studio's SDK Manager, or:
    sdkmanager "ndk;${pinned:-<version>}"
  build-android.sh auto-discovers \$ANDROID_HOME/ndk/${pinned:-<version>}
  when ANDROID_NDK_HOME is unset.
EOF
    exit 1
  fi
  if [ -n "$pinned" ] && [ "$(basename "$NDK")" != "$pinned" ]; then
    cat >&2 <<EOF
error: NDK version mismatch.
  using:  $NDK
  pinned: $pinned  (bb.ndkVersion in gradle.properties)

AGP strips the .so and extracts Play debug symbols with the pinned NDK, so the
library has to be built with that same one. Either install it:
    sdkmanager "ndk;$pinned"
or unset ANDROID_NDK_HOME so auto-discovery picks the pinned directory.
EOF
    exit 1
  fi
  echo ">> NDK: $NDK"
}

# --------------------------------------------------------------------------
# Step 2 — locate the NDK's clang, and remember the host PATH without it.
#
# This script runs cargo twice: once cross-compiling for Android, once for the
# host to produce a dylib for uniffi-bindgen. The host build must NOT see the
# NDK's llvm/bin — NDK 30 ships only a darwin-x86_64 host toolchain with no
# macOS compiler-rt, so a host link through it fails on libclang_rt.osx. Hence
# HOST_PATH, captured before the NDK is prepended.
# --------------------------------------------------------------------------
step_resolve_host_toolchain() {
  local ndk_host
  case "$(uname -s)" in
    Darwin) ndk_host=darwin-x86_64 ;;
    Linux)  ndk_host=linux-x86_64 ;;
    *) echo "unsupported host $(uname -s)" >&2; exit 1 ;;
  esac
  if [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" = arm64 ] \
     && [ -d "$NDK/toolchains/llvm/prebuilt/darwin-arm64" ]; then
    ndk_host=darwin-arm64
  fi

  LLVM="$NDK/toolchains/llvm/prebuilt/$ndk_host"
  if [ ! -d "$LLVM" ]; then
    echo "error: NDK llvm toolchain not at $LLVM" >&2
    exit 1
  fi
  HOST_PATH="$PATH"
  export PATH="$LLVM/bin:$PATH"
}

# --------------------------------------------------------------------------
# Step 3 — write the cargo config that points each target at its NDK linker.
#
# cargo has no idea where the NDK is, so every Android target needs an explicit
# linker path. Generated rather than committed because the path contains the
# NDK revision and the host tag.
# --------------------------------------------------------------------------
step_write_cargo_config() {
  local abi target clang_trip linker
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
}

# --------------------------------------------------------------------------
# Step 4 — make sure rustup has every target we are about to build.
# --------------------------------------------------------------------------
step_ensure_rust_targets() {
  local abi target
  for abi in $ABIS; do
    target="$(abi_to_target "$abi")"
    if ! rustup target list --installed | grep -qx "$target"; then
      echo ">> rustup target add $target"
      rustup target add "$target"
    fi
  done
}

# --------------------------------------------------------------------------
# Step 5 — resolve the ONNX Runtime to link, echoing its directory.
#
# Empty output means "let ort download pyke's prebuilt" (BB_ORT=prebuilt).
# --------------------------------------------------------------------------
resolve_ort_lib() {
  local abi="$1" dir="${ORT_ANDROID_LIB_LOCATION:-}"
  if [ -z "$dir" ] && [ "$BB_ORT" != "prebuilt" ]; then
    # --print-lib-location builds the tree when cold and just reprints the path
    # when warm, so this is a no-op on a repeat build. ANDROID_ABI is forwarded
    # because the tree is per-ABI, and the NDK because both scripts must agree
    # on it — build-ort-android.sh refuses a mismatch rather than quietly
    # compiling ORT against a different one than cargo uses.
    echo ">> ONNX Runtime from source (BB_ORT=prebuilt to opt out)" >&2
    dir="$(ANDROID_NDK_HOME="$NDK" ANDROID_ABI="$abi" \
      "$ANDROID_ROOT/scripts/build-ort-android.sh" --print-lib-location)"
  fi
  if [ -n "$dir" ] && [ ! -f "$dir/libonnxruntime_common.a" ]; then
    echo "error: no libonnxruntime_common.a in $dir" >&2
    echo "  This must be the CMake binary dir (…/build-<abi>/Release), not its parent." >&2
    exit 1
  fi
  echo "$dir"
}

# ORT is linked statically in both the source and the prebuilt path, so nothing
# here ships a separate libonnxruntime.so. Assert that rather than assume it: a
# dynamic link would produce an APK that installs fine and dies with
# UnsatisfiedLinkError on the first scan.
#
# The NDK's llvm-readelf, not the system one — macOS has no readelf at all, so
# the check this replaces silently evaluated to "looks static" on every dev
# machine and could never have failed.
assert_ort_static() {
  local so="$1" readelf="$LLVM/bin/llvm-readelf" dynamic
  # No `2>/dev/null`, and the tool's absence is an error rather than a pass.
  # Swallowing that is precisely how the old check managed to never fail: it ran
  # `command -v readelf`, found nothing on macOS, and reported "looks static".
  # A check that cannot fail is worse than no check, because it reads as one.
  if [ ! -x "$readelf" ]; then
    echo "error: $readelf not found; cannot verify how ONNX Runtime was linked" >&2
    exit 1
  fi
  if ! dynamic="$("$readelf" -d "$so")"; then
    echo "error: llvm-readelf failed to read $so" >&2
    exit 1
  fi
  if printf '%s\n' "$dynamic" | grep -q 'libonnxruntime'; then
    echo "error: $so has a DT_NEEDED on libonnxruntime — ORT was linked dynamically." >&2
    echo "  This script ships no separate libonnxruntime.so, so the APK would" >&2
    echo "  install and then die with UnsatisfiedLinkError on the first scan." >&2
    exit 1
  fi
}

# --------------------------------------------------------------------------
# Step 6 — cross-compile the library and install it into jniLibs/.
# --------------------------------------------------------------------------
step_build_native_libs() {
  local abi target ort_lib so_src abi_dir ndk_abi cxx_shared
  local cargo_flags=(--lib -p "$CRATE")
  [ "$PROFILE" = "release" ] && cargo_flags+=(--release)

  # Wipe rather than overlay. jniLibs/ is generated and git-ignored, and every
  # file in it is reinstalled below, so nothing is lost — but a *stale* .so left
  # behind is packaged into the APK all the same. The rename from
  # libbb_receipt_ffi.so to libbb_mobile_ffi.so made that concrete: without this,
  # any existing checkout ships both, ~450 MB of native library for an app that
  # needs one. Same reasoning as the `rm -rf .../kotlin/uniffi` before codegen.
  rm -rf "$JNI"; mkdir -p "$JNI"

  for abi in $ABIS; do
    target="$(abi_to_target "$abi")"
    echo ">> building $CRATE for $target ($PROFILE) [abi=$abi]"

    ort_lib="$(resolve_ort_lib "$abi")"
    # `env VAR=… cargo` rather than an exported VAR: see the header on why
    # ORT_LIB_LOCATION must not survive into the host bindgen build.
    local ort_env=()
    if [ -n "$ort_lib" ]; then
      echo "   ONNX Runtime from source: $ort_lib"
      ort_env=(env "ORT_LIB_LOCATION=$ort_lib")
    else
      echo "   ONNX Runtime: prebuilt from pyke's CDN (BB_ORT=prebuilt)"
    fi

    # ${a[@]+"${a[@]}"} — expanding an empty array as "${a[@]}" is an unbound
    # variable under `set -u` in bash 3.2, which is what /usr/bin/env bash still
    # resolves to on a stock macOS.
    ${ort_env[@]+"${ort_env[@]}"} \
      cargo build --config "$CARGO_CFG" "${cargo_flags[@]}" --target "$target"

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
    assert_ort_static "$so_src"

    abi_dir="$JNI/$abi"
    mkdir -p "$abi_dir"
    cp "$so_src" "$abi_dir/lib${LIB_NAME}.so"
    echo "   installed lib${LIB_NAME}.so (ONNX Runtime statically linked) → $abi_dir/"

    # libc++_shared is a real DT_NEEDED (ort-sys emits -l c++_shared for Android),
    # so it has to travel with the library.
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
  done
}

# --------------------------------------------------------------------------
# Step 7 — generate the UniFFI Kotlin from a HOST build of the same crate.
#
# uniffi-bindgen reads a native library built for *this* machine, not for
# Android, which is why this is a second cargo invocation — and why it runs with
# the NDK off PATH (see step 2).
# --------------------------------------------------------------------------
step_generate_kotlin_bindings() {
  local host_lib="" gen="$WORK/gen"
  echo ">> generating Kotlin bindings (host)"
  PATH="$HOST_PATH" cargo build --lib -p "$CRATE" >/dev/null
  for cand in \
    "$CARGO_TARGET_DIR/debug/lib${LIB_NAME}.dylib" \
    "$CARGO_TARGET_DIR/debug/lib${LIB_NAME}.so" \
    "$CARGO_TARGET_DIR/release/lib${LIB_NAME}.dylib" \
    "$CARGO_TARGET_DIR/release/lib${LIB_NAME}.so"; do
    [ -f "$cand" ] && host_lib="$cand" && break
  done
  if [ -z "$host_lib" ]; then
    echo "error: host lib${LIB_NAME} not found for uniffi-bindgen" >&2
    exit 1
  fi

  mkdir -p "$gen"
  PATH="$HOST_PATH" cargo run -q -p "$PACKAGE" --bin uniffi-bindgen -- \
    generate --library "$host_lib" --language kotlin --out-dir "$gen"

  # Wipe first: a namespace that stops being generated would otherwise linger.
  rm -rf "$PKG/src/main/kotlin/uniffi"
  mkdir -p "$PKG/src/main/kotlin"
  if [ -d "$gen/uniffi" ]; then
    cp -R "$gen/uniffi" "$PKG/src/main/kotlin/"
  else
    mkdir -p "$GEN_OUT"
    cp "$gen"/*.kt "$GEN_OUT/" 2>/dev/null || {
      echo "error: no Kotlin sources in $gen" >&2
      find "$gen" -type f | head
      exit 1
    }
  fi
}

# --------------------------------------------------------------------------
# Step 8 — record what this build was, for the About screen.
# --------------------------------------------------------------------------
step_write_build_info() {
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
}

step_print_summary() {
  rm -rf "$WORK"
  cat <<EOF

✅ Android native + UniFFI Kotlin installed into bbreceiptkit/
   jniLibs:     $JNI/{$(echo $ABIS | tr ' ' ',')}/
   kotlin glue: $PKG/src/main/kotlin/uniffi/

Next:
  ./gradlew :app:assemblePlayDebug      # Play build (GMS document scanner)
  ./gradlew :app:assembleSafehavenDebug # SafeHaven build (same scanner)
  ./gradlew :app:assembleFdroidDebug    # F-Droid build (no Play services)
EOF
}

main() {
  step_resolve_ndk
  step_resolve_host_toolchain
  step_write_cargo_config
  step_ensure_rust_targets
  step_build_native_libs
  step_generate_kotlin_bindings
  step_write_build_info
  step_print_summary
}

main "$@"
