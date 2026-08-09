#!/usr/bin/env bash
#
# Build ONNX Runtime from source for Android, so nothing prebuilt is downloaded.
#
# `ort`'s default `download-binaries` feature fetches a precompiled static lib
# from pyke's CDN. F-Droid does not accept that: shared libraries must be built
# from source, always. This script produces the same thing locally, and prints
# the directory that makes ort-sys link against it instead of the CDN.
#
#   ./scripts/build-ort-android.sh
#   ORT_BUILD_DIR=/somewhere ./scripts/build-ort-android.sh
#
# Then, to build the app against it:
#
#   ORT_ANDROID_LIB_LOCATION="$(./scripts/build-ort-android.sh --print-lib-location)" \
#     ./build-android.sh
#
# Prerequisites: git, cmake >= 3.28, ninja, python3, and the pinned Android NDK
# (the same one build-android.sh uses -- see bb.ndkVersion in gradle.properties).
set -euo pipefail

ANDROID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PRINT_ONLY=0
[ "${1:-}" = "--print-lib-location" ] && PRINT_ONLY=1

# In --print-lib-location mode the only thing on real stdout is the final path,
# so `$(...)` is usable even on a cold build. fd 3 keeps a handle on the real
# stdout; everything else -- including cmake's and ninja's own chatter, which
# does not go through this script -- is pushed to stderr for the whole run.
if [ "$PRINT_ONLY" = 1 ]; then
  exec 3>&1 1>&2
else
  exec 3>&1
fi
emit() { echo "$1" >&3; }

ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
# Must match build-android.sh's ANDROID_API (app minSdk / Android 14).
ANDROID_API="${ANDROID_API:-34}"
ORT_BUILD_DIR="${ORT_BUILD_DIR:-$ANDROID_ROOT/target/ort}"
ORT_SRC="$ORT_BUILD_DIR/src"
ORT_OUT="$ORT_BUILD_DIR/build-$ANDROID_ABI"

# --- which ONNX Runtime version -------------------------------------------
#
# Derived, not guessed. `ort-sys` records the exact upstream build its prebuilt
# binaries came from in build/download/dist.txt, as `ms@<version>`. Building a
# different version would link a Rust binding against a C++ ABI it was not
# generated for -- which links cleanly and then misbehaves at runtime, so it is
# worth the few lines to read the pin rather than hardcode it.
ORT_VERSION_FALLBACK=1.24.2
find_ort_version() {
  local dist
  dist="$(ls -d "${CARGO_HOME:-$HOME/.cargo}"/registry/src/*/ort-sys-*/build/download/dist.txt 2>/dev/null | sort -V | tail -1)"
  [ -n "$dist" ] || return 1
  # e.g. "none<TAB>aarch64-linux-android<TAB>https://cdn.pyke.io/0/pyke:ort-rs/ms@1.24.2/…"
  sed -n 's|.*/ms@\([0-9][0-9.]*\)/.*|\1|p' "$dist" | sort -u | head -1
}
ORT_VERSION="${ORT_VERSION:-$(find_ort_version || true)}"
if [ -z "$ORT_VERSION" ]; then
  ORT_VERSION="$ORT_VERSION_FALLBACK"
  echo ">> warning: no ort-sys in the cargo registry; assuming ONNX Runtime $ORT_VERSION."
  echo "   Run a normal ./build-android.sh once so the pin can be read from dist.txt."
fi
echo ">> ONNX Runtime v$ORT_VERSION ($ANDROID_ABI, API $ANDROID_API)"

# --- the NDK, agreeing with build-android.sh -------------------------------
#
# ORT's objects end up inside libbb_receipt_ffi.so, so they have to be compiled
# with the same NDK as the Rust side. The mismatch check matters most on CI: the
# runner images preset ANDROID_NDK_HOME to whatever NDK they happen to ship, and
# silently building ORT with that one is exactly the kind of thing that only
# shows up as a strange runtime failure much later.
NDK_VERSION="$(awk -F= '/^bb\.ndkVersion=/{gsub(/[[:space:]]/,"",$2); print $2}' \
  "$ANDROID_ROOT/gradle.properties" 2>/dev/null || true)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[ -d "$SDK" ] || SDK="$HOME/Android/Sdk"
NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ] && [ -n "$NDK_VERSION" ] && [ -d "$SDK/ndk/$NDK_VERSION" ]; then
  NDK="$SDK/ndk/$NDK_VERSION"
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "error: Android NDK not found (wanted ${NDK_VERSION:-any} under $SDK/ndk)." >&2
  echo "  sdkmanager \"ndk;${NDK_VERSION:-<version>}\"" >&2
  exit 1
fi
if [ -n "$NDK_VERSION" ] && [ "$(basename "$NDK")" != "$NDK_VERSION" ]; then
  cat >&2 <<EOF
error: NDK version mismatch.
  using:  $NDK
  pinned: $NDK_VERSION  (bb.ndkVersion in gradle.properties)

ONNX Runtime is compiled into libbb_receipt_ffi.so, so it must use the NDK the
Rust side uses. Either install the pinned one, or unset ANDROID_NDK_HOME so
auto-discovery picks it up.
EOF
  exit 1
fi
echo ">> NDK: $NDK"

# The libs ort-sys will link. Also what --print-lib-location returns: it must be
# the CMake binary dir itself, NOT $ORT_OUT. build.py puts the binary dir at
# <build_dir>/Release, so _deps sits at <build_dir>/Release/_deps. Pointing
# ort-sys one level up makes it detect profile="Release", find the ten
# libonnxruntime_*.a, then append /Release to every dependency path -- the build
# script still reports success and the link then fails on unresolved protobuf
# and onnx symbols. See ort-sys build/static_link/mod.rs.
LIB_LOCATION="$ORT_OUT/Release"
RE2_LIB="$LIB_LOCATION/_deps/re2-build/libre2.a"

# Both files, not just the ORT one: a tree missing libre2.a is not yet usable
# (see the re2 note below), and short-circuiting here would hide that. This is
# also what lets CI restore just the .a files from a cache and skip the build.
if [ "$PRINT_ONLY" = 1 ] && [ -f "$LIB_LOCATION/libonnxruntime_common.a" ] \
   && [ -f "$RE2_LIB" ]; then
  emit "$LIB_LOCATION"
  exit 0
fi

for tool in git cmake ninja python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "error: $tool not on PATH" >&2; exit 1; }
done

# --- source ----------------------------------------------------------------
if [ ! -d "$ORT_SRC/.git" ]; then
  echo ">> cloning microsoft/onnxruntime v$ORT_VERSION"
  mkdir -p "$ORT_BUILD_DIR"
  git clone --depth 1 --branch "v$ORT_VERSION" \
    https://github.com/microsoft/onnxruntime.git "$ORT_SRC"
else
  have="$(git -C "$ORT_SRC" describe --tags --exact-match 2>/dev/null || true)"
  if [ "$have" != "v$ORT_VERSION" ]; then
    echo ">> refetching onnxruntime v$ORT_VERSION (have: ${have:-unknown})"
    git -C "$ORT_SRC" fetch --depth 1 origin "refs/tags/v$ORT_VERSION:refs/tags/v$ORT_VERSION"
    git -C "$ORT_SRC" checkout -q "v$ORT_VERSION"
  fi
fi

# --- build -----------------------------------------------------------------
#
# Deliberately NOT --build_shared_lib: ort-sys links the raw CMake tree of ten
# libonnxruntime_*.a plus _deps, so there is nothing to merge into one archive.
#
# --skip_tests only skips *running* the tests; without the extra define you also
# compile ~136 MB of test binaries you are about to not run.
#
# --android_cpp_shared matches ort-sys, which emits `-l c++_shared` for Android
# targets (see static_link_prerequisites). Letting ORT default to the static
# libc++ here would put two C++ runtimes in one process.
echo ">> building (several minutes; ~3.5 min on a 10-core M-series, longer on CI)"
python3 "$ORT_SRC/tools/ci_build/build.py" \
  --build_dir "$ORT_OUT" \
  --config Release \
  --parallel \
  --cmake_generator Ninja \
  --android \
  --android_sdk_path "$SDK" \
  --android_ndk_path "$NDK" \
  --android_abi "$ANDROID_ABI" \
  --android_api "$ANDROID_API" \
  --android_cpp_shared \
  --skip_tests \
  --skip_submodule_sync \
  --compile_no_warning_as_error \
  --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF

if [ ! -f "$LIB_LOCATION/libonnxruntime_common.a" ]; then
  echo "error: no libonnxruntime_common.a in $LIB_LOCATION" >&2
  exit 1
fi

# re2 is EXCLUDE_FROM_ALL, and with --build_shared_lib off and unit tests off
# nothing in ORT's default target ever *links* a binary, so CMake has no reason
# to build it -- ORT's own static libs only record a usage requirement on it.
# ort-sys links `-l static=re2` unconditionally, so without this the ONLY
# symptom is a Rust build that dies with "could not find native static library
# `re2`" long after this script reported success.
if [ ! -f "$RE2_LIB" ]; then
  echo ">> building re2 (excluded from ORT's default target)"
  cmake --build "$LIB_LOCATION" --target re2
fi
[ -f "$RE2_LIB" ] || { echo "error: re2 target produced no $RE2_LIB" >&2; exit 1; }

if [ "$PRINT_ONLY" = 1 ]; then
  emit "$LIB_LOCATION"
  exit 0
fi

cat <<EOF

✅ ONNX Runtime v$ORT_VERSION built from source
   $(du -ch "$LIB_LOCATION"/libonnxruntime_*.a "$RE2_LIB" | tail -1 | cut -f1) of static libs in $LIB_LOCATION

Build the app against it. Use ORT_ANDROID_LIB_LOCATION, not ORT_LIB_LOCATION:
build-android.sh also runs a *host* cargo build for uniffi-bindgen, which must
not see the Android .a, so it forwards this to the target build only.

  ORT_ANDROID_LIB_LOCATION="$LIB_LOCATION" ./build-android.sh
EOF
