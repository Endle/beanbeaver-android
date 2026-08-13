#!/usr/bin/env bash
#
# Fetch DocQuadNet, the document-corner model the `foss` flavour's in-app camera
# uses to find the receipt and crop to it.
#
#   ./scripts/fetch-docquad-model.sh
#
# Not committed, for the same reason the PP-OCRv5 weights are not: this repo
# keeps binary blobs out of git, and F-Droid builds from source. Unlike those,
# this one is not in the `shared/` submodule -- iOS gets guided capture free from
# VisionKit and would never load it, so it lives here with the rest of the
# Android-only pipeline (same reasoning as build-ort-android.sh).
#
# The model is Apache-2.0, from MakeACopy; the Java that runs it is vendored
# under app/src/foss/java/de/schliweb/makeacopy/. See THIRD_PARTY_NOTICES.md for
# attribution, including the CC BY 4.0 datasets it was trained on.
set -euo pipefail

ANDROID_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Pinned to a commit, not a branch: the weights are an input to what the app
# actually detects, so "whatever is on main today" would make two builds of the
# same tag crop differently.
MAKEACOPY_COMMIT="f4aaf8fc3a9a96422446600a139f117240d3843b"
MODEL_NAME="docquadnet256_trained_opset17.ort"
MODEL_SHA256="aaef348eb81709d26f7e8974401795b141d70ba88bc69792c779fbae102eadaa"

DEST_DIR="$ANDROID_ROOT/app/src/foss/assets/docquad"
DEST="$DEST_DIR/$MODEL_NAME"
URL="https://raw.githubusercontent.com/egdels/makeacopy/$MAKEACOPY_COMMIT/app/src/main/assets/docquad/$MODEL_NAME"

sha_of() { shasum -a 256 "$1" 2>/dev/null | awk '{print $1}'; }

if [ -f "$DEST" ] && [ "$(sha_of "$DEST")" = "$MODEL_SHA256" ]; then
  echo "✓ $MODEL_NAME already present and matches"
  exit 0
fi

mkdir -p "$DEST_DIR"
echo ">> fetching $MODEL_NAME (~13 MB)"
curl -fsSL "$URL" -o "$DEST.tmp"

got="$(sha_of "$DEST.tmp")"
if [ "$got" != "$MODEL_SHA256" ]; then
  rm -f "$DEST.tmp"
  echo "error: sha256 mismatch for $MODEL_NAME" >&2
  echo "  expected $MODEL_SHA256" >&2
  echo "  got      $got" >&2
  exit 1
fi
mv "$DEST.tmp" "$DEST"
echo "✓ $DEST"

# The op list the model needs, used when building a reduced ONNX Runtime. Not
# required by the current full build, but it is the thing to hand
# build-ort-android.sh if the second runtime ever needs trimming down.
OPS="docquadnet256_trained_opset17.required_operators.config"
curl -fsSL \
  "https://raw.githubusercontent.com/egdels/makeacopy/$MAKEACOPY_COMMIT/app/src/main/assets/docquad/$OPS" \
  -o "$DEST_DIR/$OPS" && echo "✓ $DEST_DIR/$OPS"
