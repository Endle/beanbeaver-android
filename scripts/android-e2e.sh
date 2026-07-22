#!/usr/bin/env bash
#
# On-device E2E for Android: run receipt fixtures through the app's real scan
# pipeline on a connected emulator/device, then diff against expected.json via
# compare-e2e.py.
#
#   ./scripts/android-e2e.sh <receipts_e2e-dir>          # all cases
#   ./scripts/android-e2e.sh <receipts_e2e-dir> --all
#   ./scripts/android-e2e.sh <receipts_e2e-dir> --pilot  # smoke
#
# Requires: adb device, arm64-v8a debug APK (or BUILD=1 to assemble).
set -euo pipefail

FIXTURES="${1:?usage: android-e2e.sh <receipts_e2e-dir> [--all|--pilot]}"
MODE="${2:---all}"
PKG="com.beanbeaver.app"
ACTIVITY="com.beanbeaver.app/.MainActivity"
HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_ROOT="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-${TMPDIR:-/tmp}/bb-android-e2e}"
SEL="$WORK/selected"
ADB="${ADB:-adb}"
TIMEOUT_SEC="${TIMEOUT_SEC:-900}"

rm -rf "$WORK"; mkdir -p "$SEL"
: > "$WORK/manifest.txt"

select_case() {
  local jpg="$1" dir stem exp
  dir="$(dirname "$jpg")"; stem="$(basename "$jpg" .jpg)"; exp="$dir/$stem.expected.json"
  [ -f "$exp" ] || return 1
  [ -f "$jpg" ] || return 1
  cp "$jpg" "$SEL/$stem.jpg"
  printf '%s|%s\n' "$stem" "$exp" >> "$WORK/manifest.txt"
}

if [ "$MODE" = "--pilot" ] || [ "$MODE" = "pilot" ]; then
  if [ -f "$FIXTURES/costco_20260301_redact.jpg" ]; then
    select_case "$FIXTURES/costco_20260301_redact.jpg" || true
  fi
  if [ ! -s "$WORK/manifest.txt" ]; then
    while IFS= read -r jpg; do select_case "$jpg" && break; done \
      < <(find "$FIXTURES" -name '*.jpg' | sort)
  fi
else
  while IFS= read -r jpg; do select_case "$jpg" || true; done \
    < <(find "$FIXTURES" -name '*.jpg' | sort)
fi

count=$(find "$SEL" -name '*.jpg' | wc -l | tr -d ' ')
echo "selected $count case(s) [$MODE]"
[ "$count" -gt 0 ] || { echo "no cases with expected.json found under $FIXTURES"; exit 1; }

python3 - "$WORK/manifest.txt" "$WORK/manifest.json" <<'PY'
import json, sys
m = {}
for line in open(sys.argv[1]):
    line = line.rstrip("\n")
    if line:
        stem, exp = line.split("|", 1); m[stem] = exp
json.dump(m, open(sys.argv[2], "w"), indent=2)
PY

if ! "$ADB" devices | awk 'NR>1 && $2=="device"{ok=1} END{exit !ok}'; then
  echo "error: no adb device/emulator in 'device' state" >&2
  "$ADB" devices -l >&2 || true
  exit 1
fi

if [ "${BUILD:-0}" = "1" ]; then
  echo "── build & install ──"
  ( cd "$ANDROID_ROOT" && ./gradlew :app:installDebug )
else
  echo "── install debug APK (BUILD=1 to rebuild) ──"
  APK="$ANDROID_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [ ! -f "$APK" ]; then
    echo "missing $APK — building…"
    ( cd "$ANDROID_ROOT" && ./gradlew :app:installDebug )
  else
    "$ADB" install -r "$APK"
  fi
fi

REMOTE_BASE="/sdcard/Android/data/$PKG/files"
REMOTE_OUT="$REMOTE_BASE/batch_out.json"

# Deliver fixtures into the app's INTERNAL files/batch_in via run-as, not into
# external storage. Files that adb (the `shell` user) pushes into
# Android/data/<pkg>/files/ are NOT readable by the app process (scoped-storage /
# SELinux: the app can write its own files there but can't enumerate a
# shell-created subdir), so BatchRunner would see 0 images. run-as writes as the
# app uid, so BatchRunner's internal-dir fallback (File(filesDir,"batch_in"))
# picks them up. batch_out.json still lands in external files (getExternalFilesDir
# is first there) and we pull it below. Requires a debuggable (debug) APK.
echo "── deliver fixtures → app-internal files/batch_in (run-as) ──"
"$ADB" shell "run-as $PKG sh -c 'rm -rf files/batch_in; mkdir -p files/batch_in'"
"$ADB" shell "rm -rf '$REMOTE_BASE/batch_in' '$REMOTE_OUT'" 2>/dev/null || true
for jpg in "$SEL"/*.jpg; do
  name="$(basename "$jpg")"
  cat "$jpg" | "$ADB" shell "run-as $PKG sh -c 'cat > files/batch_in/$name'"
done

echo "── launch autoRunBatch ($count scans) ──"
"$ADB" shell am force-stop "$PKG" 2>/dev/null || true
"$ADB" shell am start -n "$ACTIVITY" --ez autoRunBatch true >/dev/null

echo "── wait for batch_out.json (timeout ${TIMEOUT_SEC}s) ──"
deadline=$(( $(date +%s) + TIMEOUT_SEC ))
while true; do
  if "$ADB" shell "test -f '$REMOTE_OUT'" 2>/dev/null; then
    s1=$("$ADB" shell "stat -c%s '$REMOTE_OUT' 2>/dev/null || wc -c < '$REMOTE_OUT'" | tr -d '\r')
    sleep 1
    s2=$("$ADB" shell "stat -c%s '$REMOTE_OUT' 2>/dev/null || wc -c < '$REMOTE_OUT'" | tr -d '\r')
    if [ -n "$s1" ] && [ "$s1" = "$s2" ] && [ "$s1" -gt 20 ]; then
      break
    fi
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "timed out waiting for $REMOTE_OUT" >&2
    "$ADB" logcat -d -s BatchRunner:I BatchRunner:E MainActivity:I MainActivity:E AndroidRuntime:E | tail -80 >&2 || true
    exit 1
  fi
  sleep 2
done

"$ADB" pull "$REMOTE_OUT" "$WORK/batch_out.json" >/dev/null
echo "── compare ──"
python3 "$HERE/compare-e2e.py" \
  --results "$WORK/batch_out.json" \
  --manifest "$WORK/manifest.json" \
  ${PRIVATE_RULES:+--private-rules "$PRIVATE_RULES"}
