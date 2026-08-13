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
WITH_JAVA=0
for arg in "$@"; do
  case "$arg" in
    --print-lib-location) PRINT_ONLY=1 ;;
    --with-java)          WITH_JAVA=1 ;;
  esac
done
[ -n "${ORT_BUILD_JAVA:-}" ] && WITH_JAVA=1

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
# --with-java is a different CMake configuration, so it gets its own tree rather
# than invalidating the Rust-only one every time the flag is toggled.
[ "$WITH_JAVA" = 1 ] && ORT_OUT="$ORT_BUILD_DIR/build-java-$ANDROID_ABI"

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
# --with-java additionally emits ONNX Runtime's Java bindings, which the foss
# flavour's vendored DocQuad corner detector calls through `ai.onnxruntime`.
#
# It is the SAME compile, not a second one: --build_shared_lib still produces
# the ten libonnxruntime_*.a that ort-sys links, and pointing
# ORT_ANDROID_LIB_LOCATION at this tree was measured to keep onnxruntime
# statically linked into libbb_mobile_ffi.so (no DT_NEEDED) even with a
# libonnxruntime.so sitting beside them. So F-Droid builds ORT once, not twice.
#
# It also makes the re2 workaround below moot -- with a shared library to link,
# CMake finally has a reason to build re2 -- but that step is left in place
# because it is a no-op once re2 exists and still load-bearing without --with-java.
# XNNPACK rides with --with-java, and only there. The vendored DocQuad runner
# asks for it explicitly, and that path uses this shared build.
#
# It must NOT go on the static (Rust-only) build: with
# onnxruntime_BUILD_SHARED_LIB=OFF, onnxruntime_USE_XNNPACK=ON fails at CMake
# generate with a wall of `install(EXPORT "onnxruntimeTargets") includes target
# "onnxruntime" which requires target "absl_*" that is not in any export set`.
# Adding it unconditionally broke the previously-green fdroid-ort-from-source
# job, which builds exactly that configuration.
#
# Nothing is lost: ocr-paddle CAN now register XNNPACK (core v0.9.1), but
# enabling that feature fails at link with undefined hidden xnn_* microkernel
# symbols -- ort-sys does not add XNNPACK's kernel archives -- so the Rust side
# has no use for it yet either way. NNAPI is deliberately never built; the
# DocQuad runner catches its absence and falls back to CPU.
JAVA_BUILD_FLAGS=""
if [ "$WITH_JAVA" = 1 ]; then
  JAVA_BUILD_FLAGS="--build_shared_lib --build_java --use_xnnpack"
  command -v javac >/dev/null 2>&1 || {
    echo "error: --with-java needs a JDK on PATH (JAVA_HOME/bin)." >&2; exit 1; }
fi

echo ">> building (several minutes; ~3.5 min on a 10-core M-series, longer on CI)"
set +e
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
  $JAVA_BUILD_FLAGS \
  --skip_tests \
  --skip_submodule_sync \
  --compile_no_warning_as_error \
  --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF

BUILD_RC=$?
set -e

# --with-java's last step packages an Android AAR using ONNX Runtime's own
# java/build-android.gradle, which is pinned to AGP 7.4.2 and dies under JDK 21
# in AGP's JdkImageTransform (jlink). We do not consume the AAR -- the jar plus
# the two .so files below is the whole integration -- so a failure *there* is
# not a failure here. Rather than ignore the exit code blindly, everything we
# actually need is asserted immediately after.
if [ "$BUILD_RC" != 0 ] && [ "$WITH_JAVA" != 1 ]; then
  echo "error: ONNX Runtime build failed (exit $BUILD_RC)" >&2
  exit "$BUILD_RC"
fi

if [ ! -f "$LIB_LOCATION/libonnxruntime_common.a" ]; then
  echo "error: no libonnxruntime_common.a in $LIB_LOCATION" >&2
  exit 1
fi

if [ "$WITH_JAVA" = 1 ]; then
  ORT_JAR="$LIB_LOCATION/java/build/libs/onnxruntime.jar"
  for artifact in \
    "$LIB_LOCATION/libonnxruntime.so" \
    "$LIB_LOCATION/libonnxruntime4j_jni.so" \
    "$ORT_JAR"; do
    if [ ! -f "$artifact" ]; then
      echo "error: --with-java produced no $(basename "$artifact")" >&2
      echo "  (build.py exited $BUILD_RC; that is tolerated only when the" >&2
      echo "   jar and both .so files exist -- see the note above)" >&2
      exit 1
    fi
  done
  echo ">> java bindings:"
  echo "     $ORT_JAR"
  echo "     $LIB_LOCATION/libonnxruntime.so"
  echo "     $LIB_LOCATION/libonnxruntime4j_jni.so"
  echo "   install into app/src/foss/libs/ and app/src/foss/jniLibs/$ANDROID_ABI/"
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
