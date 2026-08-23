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
# Prerequisites: git, cmake >= 3.28, ninja, python3 with the `flatbuffers`
# module, and the pinned Android NDK (the same one build-android.sh uses -- see
# bb.ndkVersion in gradle.properties).
#
# `flatbuffers` is required: the reduced-operator build (BB_ORT_REDUCED_OPS=1,
# now the DEFAULT) needs it for ORT's util.parse_config. Without it build.py
# dies with a bare import error. There is a preflight check below so that
# failure arrives in a second rather than partway through a long build.
# BB_ORT_REDUCED_OPS=0 builds the full operator set and needs none of it.
#
#   python3 -m pip install flatbuffers
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
# The committed operator list the reduced build is cut to. See its header, and
# the "operator reduction" note further down.
OPS_CONFIG="${OPS_CONFIG:-$ANDROID_ROOT/scripts/ort-required-ops.config}"

# --- which ONNX Runtime version -------------------------------------------
#
# Derived, not guessed. `ort-sys` records the exact upstream build its prebuilt
# binaries came from in build/download/dist.txt, as `ms@<version>`. Building a
# different version would link a Rust binding against a C++ ABI it was not
# generated for -- which links cleanly and then misbehaves at runtime, so it is
# worth the few lines to read the pin rather than hardcode it.
#
# Everything below writes to stderr, not stdout: this runs inside $(...) and only
# the version may come back on stdout.
find_dist_txt() {
  ls -d "${CARGO_HOME:-$HOME/.cargo}"/registry/src/*/ort-sys-*/build/download/dist.txt \
    2>/dev/null | sort -V | tail -1
}
find_ort_version() {
  local dist
  dist="$(find_dist_txt)"
  if [ -z "$dist" ]; then
    # CI hits this every time: this script runs *before* anything has built the
    # crate, so registry/src is empty and there is no pin to read. `cargo fetch`
    # extracts into registry/src, which is enough — and notably does not build
    # anything, so it does not pull a prebuilt ONNX Runtime down in the process.
    echo ">> fetching crates to read the ort-sys pin" >&2
    ( cd "$ANDROID_ROOT" && cargo fetch -q >&2 ) || true
    dist="$(find_dist_txt)"
  fi
  [ -n "$dist" ] || return 1
  # e.g. "none<TAB>aarch64-linux-android<TAB>https://cdn.pyke.io/0/pyke:ort-rs/ms@1.24.2/…"
  sed -n 's|.*/ms@\([0-9][0-9.]*\)/.*|\1|p' "$dist" | sort -u | head -1
}
ORT_VERSION="${ORT_VERSION:-$(find_ort_version || true)}"
if [ -z "$ORT_VERSION" ]; then
  # Deliberately fatal. Falling back to a hardcoded default is worse than
  # stopping: it links a Rust binding against a C++ ABI it was not generated
  # for, which builds clean and misbehaves at runtime — and CI, the one place
  # meant to catch that drift, would be the quietest about it.
  cat >&2 <<EOF
error: could not determine which ONNX Runtime version to build.

  The version is read from ort-sys's build/download/dist.txt (the \`ms@<ver>\`
  in its download URLs), so it can never drift from the \`ort\` the Rust side
  links. Neither the cargo registry nor \`cargo fetch\` produced it.

  Fix the cargo setup, or state it explicitly if you know it matches:
    ORT_VERSION=1.24.2 $0
EOF
  exit 1
fi
echo ">> ONNX Runtime v$ORT_VERSION ($ANDROID_ABI, API $ANDROID_API)"

# --- the NDK, agreeing with build-android.sh -------------------------------
#
# ORT's objects end up inside libbb_mobile_ffi.so, so they have to be compiled
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

ONNX Runtime is compiled into libbb_mobile_ffi.so, so it must use the NDK the
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

# --- what those .a files were built FROM ------------------------------------
#
# The short-circuit below used to test only that the archives exist, which is
# not the same question. Static libs carry no record of the flags that produced
# them, so an existing tree built with a different operator set looked identical
# to a correct one and was silently linked. That actually happened: after the
# reduced-operator build landed, every checkout with a warm target/ort kept
# linking its old full-operator archives, producing a 21.4 MB .so instead of
# 13.7 MB, with the build reporting complete success. Nothing downstream could
# notice -- the app runs fine, it is just 7.6 MB bigger than it should be.
#
# So the tree records its own recipe, and a mismatch rebuilds. The stamp covers
# the ONNX Runtime version, whether operator reduction was on, and the contents
# of the operator list.
ORT_STAMP="$LIB_LOCATION/.bb-ort-recipe"
ort_recipe_id() {
  local ops_hash="none"
  if [ "${BB_ORT_REDUCED_OPS:-1}" = "1" ] && [ -f "$OPS_CONFIG" ]; then
    # Only the operator rows, not the whole file. Most of that config is
    # long-form documentation, and hashing it meant a prose edit invalidated the
    # tree and cost a full rebuild -- which is friction pushing toward not
    # writing the documentation down. Comments cannot change what ORT compiles.
    ops_hash="$(grep -v '^[[:space:]]*#' "$OPS_CONFIG" | grep -v '^[[:space:]]*$' \
                | shasum -a 256 | awk '{print $1}')"
  fi
  echo "ort=$ORT_VERSION abi=$ANDROID_ABI api=$ANDROID_API reduced=${BB_ORT_REDUCED_OPS:-1} ops=$ops_hash"
}
WANT_RECIPE="$(ort_recipe_id)"

# A tree whose recipe does not match is WIPED, not rebuilt in place, and that is
# the whole point rather than caution.
#
# ONNX Runtime applies --include_ops_by_config at CMake *configure* time: it runs
# reduce_op_kernels.py once and writes generated registration headers into the
# build tree. CMake has no dependency edge from those headers back to the config
# file, so building again into a configured tree happily reuses the PREVIOUS
# operator set. Measured: correcting the operator list and rebuilding took 31 s
# (a relink), produced a byte-identical .so, and still failed on device with
# `Could not find an implementation for Gemm(13)` -- an operator the corrected
# list contains. Only a from-scratch configure picks it up.
#
# So a recipe change costs a full ~4 minute rebuild. That is the honest price of
# changing what the engine contains, and it is far cheaper than the alternative,
# which is a green build that dies on the first scan.
# Preflight the reduced build's one host dependency BEFORE the recipe check
# below, which WIPES a tree built to a different recipe. Discovering a missing
# module after that has already cost a usable ONNX Runtime and a full rebuild.
# ORT's util.parse_config only exists when flatbuffers is importable, and
# build.py does not reach it until well into the build.
if [ "${BB_ORT_REDUCED_OPS:-1}" = "1" ] && [ "$PRINT_ONLY" != 1 ]; then
  python3 -c 'import flatbuffers' 2>/dev/null || {
    cat >&2 <<EOF
error: the reduced-operator build needs the python 'flatbuffers' module, and
       $(command -v python3 || echo python3) does not have it.

  python3 -m pip install --upgrade flatbuffers

       Or build the full operator set instead:  BB_ORT_REDUCED_OPS=0 $0
EOF
    exit 1
  }
fi

if [ -f "$LIB_LOCATION/libonnxruntime_common.a" ] || [ -d "$ORT_OUT" ]; then
  if [ -f "$ORT_STAMP" ] && [ "$(cat "$ORT_STAMP")" = "$WANT_RECIPE" ]; then
    # Matching tree. In --print-lib-location mode there is nothing to do; this is
    # also what lets CI restore just the .a files plus the stamp and skip the
    # compile. libre2.a is checked too: a tree missing it is not yet usable (see
    # the re2 note below) and short-circuiting would hide that.
    if [ "$PRINT_ONLY" = 1 ] && [ -f "$RE2_LIB" ]; then
      emit "$LIB_LOCATION"
      exit 0
    fi
  elif [ -d "$ORT_OUT" ]; then
    echo ">> ONNX Runtime in $ORT_OUT was built to a different recipe -- rebuilding from scratch" >&2
    echo "   have: $( [ -f "$ORT_STAMP" ] && cat "$ORT_STAMP" || echo '(no stamp: predates this check)')" >&2
    echo "   want: $WANT_RECIPE" >&2
    echo "   (a configured tree reuses its generated operator registrations, so" >&2
    echo "    an in-place rebuild would silently keep the old operator set)" >&2
    rm -rf "$ORT_OUT"
  fi
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
#
# --- operator reduction ----------------------------------------------------
#
# A stock ORT registers every operator in every domain it supports, plus a kernel
# instantiation per input type. We run exactly three fixed graphs, and they use
# 27 operators from one domain. `--include_ops_by_config` compiles out the
# registrations nothing in that list needs, and `--disable_ml_ops` drops the
# ai.onnx.ml domain (tree ensembles, SVMs, label encoders) that PP-OCRv5 never
# touches.
#
# ON BY DEFAULT since 2026-08-23, worth ~7.2 MB of the shipped .so. It was off
# before that because #32 shipped an engine that could not open a session: the
# operator list was derived from a single tool, and no single tool describes the
# graph the runtime actually builds. The list is now the UNION of three, which is
# closed under whether a fusion fires -- see scripts/ort-required-ops.config, and
# regenerate it with scripts/gen-ort-ops-config.py, never by hand.
#
# Verified on an arm64 AVD before this default changed: 1/1 cases fully pass.
# CI cannot verify it (no hosted runner can run an arm64 AVD), so the standing
# rule holds -- a model change or an ORT bump is not verified until a phone
# runtime has scanned a receipt.
#
# The failure mode if it goes stale is a *runtime* one: session creation fails
# with "Could not find an implementation for <Op>", on a device, in the OCR
# call. A host build cannot catch it -- the host links pyke's stock prebuilt,
# which has every operator. The guard is therefore
# scripts/assert-ort-ops-config-fresh.sh, which compares the model hashes
# recorded in the config against models/ and needs no python at all.
#
# BB_ORT_REDUCED_OPS=0 builds the full operator set, for bisecting a suspected
# missing-operator failure against an otherwise identical engine.
ops_flags=()
if [ "${BB_ORT_REDUCED_OPS:-1}" = "1" ]; then
  [ -f "$OPS_CONFIG" ] || { echo "error: missing $OPS_CONFIG" >&2; exit 1; }
  ops_flags+=(--include_ops_by_config "$OPS_CONFIG" --disable_ml_ops)
  echo ">> reduced operator build (--include_ops_by_config --disable_ml_ops)" >&2
else
  echo ">> full operator build"
fi

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
  "${ops_flags[@]}" \
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

# Record what this tree was built from, so the short-circuit above can tell a
# reusable tree from one built to a different recipe. Written last, after every
# check that could still fail, so a half-built tree is never stamped as good.
printf '%s\n' "$WANT_RECIPE" > "$ORT_STAMP"

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
