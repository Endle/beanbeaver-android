#!/usr/bin/env bash
#
# Real-device launch-latency measurement: build the app in a given profile
# (debug or release), install it on a connected device, cold-launch it N times
# with `--ez logLaunchTiming true`, and pull the per-launch
# process-start→first-frame timings the app wrote to
# `filesDir/launch_timing.json` (see LaunchTiming.kt). Android twin of the iOS
# `scripts/launch-timing.sh`.
#
# This is the "why is the launch screen up for a few seconds" measurement — it
# captures the whole pre-onCreate window (zygote fork + class loading + ONNX
# static initializers), which does NOT transfer from the host, so the phone is
# the only valid signal. Run it for both profiles to compare:
#
#   scripts/launch-timing.sh release        # 5 cold launches, release
#   scripts/launch-timing.sh debug 8        # 8 cold launches, debug
#   RUNS=6 scripts/launch-timing.sh release
#
# The first launch after install is reported separately: it pays one-time
# first-launch costs (dexopt/verification of a fresh APK) that later cold
# launches don't, and it's the worst case a user hits right after updating.
#
# Requires: a connected, unlocked, USB-debugging-enabled arm64 device.
set -euo pipefail

PROFILE="${1:-release}"
RUNS="${2:-${RUNS:-5}}"
PKG="com.zhenbo.beanbeaver"
ACTIVITY="$PKG/.MainActivity"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-${TMPDIR:-/tmp}/bb-launch-timing}"
mkdir -p "$WORK"
OUT="$WORK/launch_timing.$PROFILE.json"
rm -f "$OUT"

case "$PROFILE" in debug|release) ;; *) echo "profile must be debug or release"; exit 2 ;; esac

: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || { echo "adb not found at $ADB (set ANDROID_HOME)"; exit 1; }

DEVICE_COUNT=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
[ "$DEVICE_COUNT" -ge 1 ] || { echo "no connected device ($ADB devices)"; exit 1; }
echo "profile: $PROFILE   runs: $RUNS"

# Capitalized variant name for the Gradle task (assembleDebug / assembleRelease).
VARIANT="$(printf '%s' "${PROFILE:0:1}" | tr '[:lower:]' '[:upper:]')${PROFILE:1}"

echo "── build & install ($PROFILE) ──"
( cd "$ROOT" && ./gradlew ":app:assemble$VARIANT" -q )
APK="$ROOT/app/build/outputs/apk/$PROFILE/app-$PROFILE.apk"
[ -f "$APK" ] || { echo "no APK at $APK"; exit 1; }
"$ADB" install -r "$APK" >/dev/null
echo "installed: $APK"

# Clear any prior run's timing file so we only collect this session's launches.
"$ADB" shell run-as "$PKG" rm -f files/launch_timing.json 2>/dev/null || true

echo "── $RUNS cold launches ──"
for i in $(seq 1 "$RUNS"); do
  "$ADB" shell am force-stop "$PKG"
  sleep 1
  "$ADB" shell am start -n "$ACTIVITY" --ez logLaunchTiming true >/dev/null 2>&1
  sleep 6                     # let it reach first frame + write the record
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 2                     # settle before the next cold launch
  printf '  launch %d/%d done\n' "$i" "$RUNS"
done

echo "── pull results ──"
# run-as reads the app-private file without root; `exec-out` keeps bytes intact.
"$ADB" exec-out run-as "$PKG" cat files/launch_timing.json > "$OUT"
echo "wrote $OUT"

python3 - "$OUT" "$PROFILE" <<'PY'
import json, sys, statistics as st
recs = json.load(open(sys.argv[1]))
ms = [r["ms"] for r in recs]
cfg = sys.argv[2]
print(f"\n=== {cfg}: process-start → first-frame (ms) ===")
print(f"  launches:   {len(ms)}")
if not ms: sys.exit(0)
print(f"  first (cold-after-install): {ms[0]:.0f}")
rest = ms[1:] or ms
print(f"  steady cold  min / median / max: {min(rest):.0f} / {st.median(rest):.0f} / {max(rest):.0f}")
print(f"  all: " + ", ".join(f"{x:.0f}" for x in ms))
PY
