#!/usr/bin/env bash
# Download PP-OCRv5 mobile ONNX weights into android/models/ (or MODELS_DIR).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="${MODELS_DIR:-$ROOT/models}"
mkdir -p "$MODELS_DIR"
base="https://github.com/Endle/beanbeaver/releases/download/ocr-models-v1"
for m in PP-OCRv5_mobile_det.onnx PP-OCRv5_mobile_rec.onnx PP-LCNet_x1_0_textline_ori.onnx; do
  out="$MODELS_DIR/$m"
  if [ -f "$out" ]; then
    echo "have $out"
    continue
  fi
  echo "fetch $m → $out"
  curl -sSfL -o "$out" "$base/$m"
done
echo "models in $MODELS_DIR"
