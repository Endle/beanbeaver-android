#!/usr/bin/env bash
#
# Assemble a receipt E2E fixture directory: <stem>.jpg next to
# <stem>.expected.json, the layout android-e2e.sh and compare-e2e.py want.
#
# The ground truth lives in tests/receipts_e2e/ but the images do not — the only
# public fixture is the 2 MB redacted Costco receipt the app already ships as its
# bundled sample, and one copy of it in the repo is enough. This stitches the two
# halves together in a scratch dir and also writes the compare-e2e.py manifest.
#
#   ./scripts/e2e-fixtures.sh [outdir]      # prints the dir on stdout
#
#   FIX=$(./scripts/e2e-fixtures.sh)
#   cargo run --release --bin batch_e2e -- --models models --in-dir "$FIX" \
#       --out "$FIX/batch_out.json"                       # host, no device
#   python3 scripts/compare-e2e.py --results "$FIX/batch_out.json" \
#       --manifest "$FIX/manifest.json"
#   ./scripts/android-e2e.sh "$FIX" --pilot               # on an emulator
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="${1:-${TMPDIR:-/tmp}/bb-e2e-fixtures}"
TRUTH="$ROOT/tests/receipts_e2e"
IMAGES="$ROOT/app/src/main/assets/samples"

rm -rf "$OUT"; mkdir -p "$OUT"

n=0
manifest="{"
for exp in "$TRUTH"/*.expected.json; do
  [ -e "$exp" ] || continue
  stem="$(basename "$exp" .expected.json)"
  jpg="$IMAGES/$stem.jpg"
  if [ ! -f "$jpg" ]; then
    echo "skip $stem: no image at $jpg" >&2
    continue
  fi
  cp "$jpg" "$OUT/$stem.jpg"
  cp "$exp" "$OUT/$stem.expected.json"
  [ "$n" -eq 0 ] || manifest="$manifest,"
  manifest="$manifest
  \"$stem\": \"$OUT/$stem.expected.json\""
  n=$((n + 1))
done
manifest="$manifest
}"

if [ "$n" -eq 0 ]; then
  echo "error: no fixtures assembled (looked in $TRUTH + $IMAGES)" >&2
  exit 1
fi

printf '%s\n' "$manifest" > "$OUT/manifest.json"
echo "assembled $n fixture(s) in $OUT" >&2
echo "$OUT"
