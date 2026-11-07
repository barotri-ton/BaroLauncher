#!/usr/bin/env bash
# Non-destructive Android 9 profiling. Run: bash tools/profiling/profile.sh --kind emulator --serial emulator-5554
set -euo pipefail
PKG=com.rampey.materialtv
COMP="$PKG/.HomeActivity"
KIND=""; SERIAL=""; OUT=""; RUNS=5; SETTLE=120; IDLE_SHORT=60; IDLE_LONG=300
usage() { echo "Usage: bash $0 --kind emulator|hardware [--serial SERIAL] [--out DIR] [--runs 5] [--settle 120] [--quick]"; }
while (( $# )); do
  case "$1" in
    --kind) KIND="${2:?}"; shift 2;;
    --serial) SERIAL="${2:?}"; shift 2;;
    --out) OUT="${2:?}"; shift 2;;
    --runs) RUNS="${2:?}"; shift 2;;
    --settle) SETTLE="${2:?}"; shift 2;;
    --quick) SETTLE=0; IDLE_SHORT=2; IDLE_LONG=5; shift;;
    -h|--help) usage; exit 0;;
    *) usage >&2; exit 2;;
  esac
done
[[ "$KIND" == emulator || "$KIND" == hardware ]] || { usage >&2; exit 2; }
[[ "$RUNS" =~ ^[1-9][0-9]*$ && "$SETTLE" =~ ^[0-9]+$ ]] || exit 2
command -v adb >/dev/null || { echo "adb required" >&2; exit 1; }
if [[ -z "$SERIAL" ]]; then
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  [[ ${#DEVICES[@]} -eq 1 ]] || { echo "Specify --serial; exactly one authorized device required" >&2; exit 1; }
  SERIAL="${DEVICES[0]}"
fi
ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state | grep -qx device
API=$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$API" == 28 ]] || { echo "Expected Android API 28; got $API" >&2; exit 1; }
if [[ "$KIND" == emulator && "$SERIAL" != emulator-* ]]; then echo "Emulator kind requires emulator-* serial" >&2; exit 1; fi
if [[ "$KIND" == hardware && "$SERIAL" == emulator-* ]]; then echo "Hardware kind cannot use emulator serial" >&2; exit 1; fi
"${ADB[@]}" shell pm path "$PKG" | grep -q '^package:' || { echo "Install $PKG first" >&2; exit 1; }
OUT="${OUT:-evidence/profiling/$KIND-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$OUT"/{environment,startup,memory,frames,logs}
printf 'kind=%s\nserial=%s\napi=%s\nstart_utc=%s\nruns=%s\nsettle_seconds=%s\nshort_idle_seconds=%s\nlong_idle_seconds=%s\n' "$KIND" "$SERIAL" "$API" "$(date -u +%FT%TZ)" "$RUNS" "$SETTLE" "$IDLE_SHORT" "$IDLE_LONG" > "$OUT/manifest.txt"
for prop in ro.product.model ro.build.fingerprint ro.product.cpu.abi ro.config.low_ram; do
  printf '%s=%s\n' "$prop" "$("${ADB[@]}" shell getprop "$prop" | tr -d '\r')" >> "$OUT/manifest.txt"
done
"${ADB[@]}" shell wm size > "$OUT/environment/display.txt"
"${ADB[@]}" shell wm density >> "$OUT/environment/display.txt"
"${ADB[@]}" shell dumpsys package "$PKG" > "$OUT/environment/package.txt"
"${ADB[@]}" shell cat /proc/meminfo > "$OUT/environment/system-meminfo.txt"
"${ADB[@]}" shell pm list packages > "$OUT/environment/packages.txt"
"${ADB[@]}" shell settings get secure enabled_accessibility_services > "$OUT/environment/accessibility-before.txt"
"${ADB[@]}" shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/environment/home-before.txt" || true
printf 'network_state=RECORD_MANUALLY\napk_sha256=RECORD_FROM_INSTALLED_APK_OR_BUILD_ARTIFACT\n' >> "$OUT/manifest.txt"
echo "Settling for $SETTLE seconds. Do not interact with device."
sleep "$SETTLE"
"${ADB[@]}" logcat -c
printf 'mode,run,utc,output_file\n' > "$OUT/startup/index.csv"
capture_memory() {
  local label="$1" sample="$2"
  "${ADB[@]}" shell dumpsys meminfo "$PKG" > "$OUT/memory/${label}-${sample}.txt"
  "${ADB[@]}" shell dumpsys gfxinfo "$PKG" framestats > "$OUT/frames/${label}-${sample}.txt"
  "${ADB[@]}" shell cat /proc/meminfo > "$OUT/memory/system-${label}-${sample}.txt"
  printf '%s,%s,%s\n' "$label" "$sample" "$(date -u +%FT%TZ)" >> "$OUT/memory/index.csv"
}
printf 'phase,sample,utc\n' > "$OUT/memory/index.csv"
for ((i=1;i<=RUNS;i++)); do
  "${ADB[@]}" shell am force-stop "$PKG"
  sleep 2
  "${ADB[@]}" shell am start -W -n "$COMP" > "$OUT/startup/cold-$i.txt"
  printf 'cold,%s,%s,startup/cold-%s.txt\n' "$i" "$(date -u +%FT%TZ)" "$i" >> "$OUT/startup/index.csv"
  sleep "$IDLE_SHORT"
  capture_memory idle60 "$i"
  if (( i == 1 )); then
    sleep "$((IDLE_LONG > IDLE_SHORT ? IDLE_LONG-IDLE_SHORT : 0))"
    capture_memory idle300 "$i"
  fi
  # Warm path uses Activity launch without force-stop; HOME key is intentionally NOT injected.
  # Avoid changing the default HOME handler and accessibility services.
  "${ADB[@]}" shell am start -W -n "$COMP" > "$OUT/startup/warm-$i.txt"
  printf 'warm,%s,%s,startup/warm-%s.txt\n' "$i" "$(date -u +%FT%TZ)" "$i" >> "$OUT/startup/index.csv"
done
# Five idle samples at both intervals without forcing the app; preserve temporal comparability.
for ((i=2;i<=RUNS;i++)); do
  sleep "$IDLE_LONG"
  capture_memory idle300 "$i"
done
"${ADB[@]}" shell dumpsys activity processes > "$OUT/environment/activity-processes.txt"
"${ADB[@]}" logcat -d -v threadtime > "$OUT/logs/logcat.txt"
"${ADB[@]}" shell settings get secure enabled_accessibility_services > "$OUT/environment/accessibility-after.txt"
"${ADB[@]}" shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/environment/home-after.txt" || true
echo "Raw evidence: $OUT"
python3 "$(dirname "$0")/summarize.py" "$OUT" || echo "Summary failed; raw evidence retained."
echo "Review manifest placeholders and redact logs before sharing. Do not call emulator data hardware evidence."
