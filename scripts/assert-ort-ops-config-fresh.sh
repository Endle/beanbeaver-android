#!/usr/bin/env bash
#
# Fail if models/ no longer matches the models scripts/ort-required-ops.config
# was generated from.
#
# ONNX Runtime is compiled with `--include_ops_by_config`, so the shipped engine
# only implements the 27 operators the current PP-OCRv5 graphs use. Swap a model
# for one that needs a 28th and nothing complains until the OCR call runs on a
# device, where session creation dies with:
#
#     Could not find an implementation for <Op>(<n>) node with name '...'
#
# A host build cannot catch it: the host links pyke's stock prebuilt, which has
# every operator. Neither can `cargo test` or the host E2E. So the guard is this
# -- comparing the model bytes against the hashes recorded in the config -- which
# needs nothing but shasum and runs in milliseconds.
#
# It deliberately checks HASHES, not operator coverage. Parsing an .onnx to
# recover its op set needs the `onnx` python module, which is the dependency the
# committed config exists to avoid. Hashes catch the event that invalidates the
# list (the models moved) without needing to understand the file format.
#
# If this fires and the model change is intended, regenerate the config -- the
# command is in its header -- and update the hashes.
set -euo pipefail

ANDROID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="$ANDROID_ROOT/scripts/ort-required-ops.config"
MODELS_DIR="${MODELS_DIR:-$ANDROID_ROOT/models}"

[ -f "$CONFIG" ] || { echo "error: missing $CONFIG" >&2; exit 1; }

# The recorded hashes are the commented block of `<sha256>  <filename>` lines.
recorded="$(grep -oE '^#   [0-9a-f]{64}  .+\.onnx$' "$CONFIG" | sed 's/^#   //' || true)"
if [ -z "$recorded" ]; then
  echo "error: $CONFIG records no model hashes -- see its header." >&2
  exit 1
fi

status=0
while read -r want name; do
  file="$MODELS_DIR/$name"
  if [ ! -f "$file" ]; then
    # Not this script's job to police a missing model: build-android.sh and
    # Gradle both already fail clearly on that, and failing here first would
    # send someone to the wrong file.
    continue
  fi
  got="$(shasum -a 256 "$file" | awk '{print $1}')"
  if [ "$got" != "$want" ]; then
    echo "error: $name does not match scripts/ort-required-ops.config" >&2
    echo "  recorded: $want" >&2
    echo "  actual:   $got" >&2
    status=1
  fi
done <<< "$recorded"

if [ "$status" -ne 0 ]; then
  cat >&2 <<'EOF'

ONNX Runtime is built with a reduced operator set derived from those models.
A changed model may need an operator the shipped engine no longer implements,
and that failure would only appear at OCR time on a device.

Regenerate the config (command in its header), update the hashes, and rebuild:

    rm -rf target/ort/build-*        # force ORT to recompile
    ./build-android.sh

Or, to build a full-operator ORT and skip all of this:

    BB_ORT_REDUCED_OPS=0 ./build-android.sh
EOF
  exit 1
fi

echo ">> ORT operator config matches models/ ($(printf '%s\n' "$recorded" | wc -l | tr -d ' ') models)"
