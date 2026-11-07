#!/usr/bin/env bash
# Non-destructive Android 9 profiling. Run: bash tools/profiling/profile.sh --kind emulator --serial emulator-5554
set -euo pipefail
PKG=com.rampey.materialtv
COMP="$PKG/.HomeActivity"
KIND=""; SERIAL=""; OUT=""; APK=""; NETWORK=""; TOGGLES=""; RUNS=5; SETTLE=120; IDLE_SHORT=60; IDLE_LONG=300
usage() { echo "Usage: bash $0 --kind emulator|hardware --apk FILE --network on|off --toggles TEXT [--serial SERIAL] [--runs 5] [--settle 120] [--quick]"; }
while (( $# )); do
  case "$1" in
    --kind) KIND="${2:?}"; shift 2;;
    --serial) SERIAL="${2:?}"; shift 2;;
    --out) OUT="${2:?}"; shift 2;;
    --apk) APK="${2:?}"; shift 2;;
    --network) NETWORK="${2:?}"; shift 2;;
    --toggles) TOGGLES="${2:?}"; shift 2;;
    --runs) RUNS="${2:?}"; shift 2;;
    --settle) SETTLE="${2:?}"; shift 2;;
    --quick) SETTLE=0; IDLE_SHORT=2; IDLE_LONG=5; shift;;
    -h|--help) usage; exit 0;;
    *) usage >&2; exit 2;;
  esac
done
[[ "$KIND" == emulator || "$KIND" == hardware ]] || { usage >&2; exit 2; }
[[ "$RUNS" =~ ^[1-9][0-9]*$ && "$SETTLE" =~ ^[0-9]+$ ]] || exit 2
[[ -n "$APK" && -f "$APK" && -n "$NETWORK" && -n "$TOGGLES" ]] || { echo "Required: --apk PATH --network on|off --toggles DESCRIPTION" >&2; exit 2; }
[[ "$NETWORK" == on || "$NETWORK" == off ]] || exit 2
command -v sha256sum >/dev/null || { echo "sha256sum required" >&2; exit 1; }
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
printf 'network_state=%s\nfeature_toggles=%s\napk_path=%s\napk_sha256=%s\napk_provenance=local_build_artifact_not_verified_installed\ninstalled_app_count=%s\n' "$NETWORK" "$TOGGLES" "$APK" "$(sha256sum "$APK" | awk '{print $1}')" "$("${ADB[@]}" shell pm list packages -3 | wc -l | tr -d ' ')" >> "$OUT/manifest.txt"
grep -E 'versionCode=|versionName=' "$OUT/environment/package.txt" | head -n 4 >> "$OUT/manifest.txt" || true
# Verify actual connectivity and installed APK identity without changing system settings.
"${ADB[@]}" shell dumpsys connectivity > "$OUT/environment/connectivity.txt"
"${ADB[@]}" shell settings get global airplane_mode_on > "$OUT/environment/airplane-mode.txt"
INSTALLED_PATH=$("${ADB[@]}" shell pm path "$PKG" | sed -n 's/^package://p' | head -n 1 | tr -d '\r')
if [[ -n "$INSTALLED_PATH" ]]; then
  if "${ADB[@]}" exec-out cat "$INSTALLED_PATH" > "$OUT/environment/installed-base.apk"; then
    printf 'installed_apk_sha256=%s\n' "$(sha256sum "$OUT/environment/installed-base.apk" | awk '{print $1}')" >> "$OUT/manifest.txt"
    rm -f "$OUT/environment/installed-base.apk"
  else
    rm -f "$OUT/environment/installed-base.apk"
    printf 'installed_apk_sha256=UNAVAILABLE\n' >> "$OUT/manifest.txt"
  fi
fi
printf 'network_verification=see_environment_connectivity_and_airplane_mode\ntoggle_verification=operator_declared_only\n' >> "$OUT/manifest.txt"
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
  # Independent cold launch for every 60s/300s sample pair.
  printf "cycle_%s_cold_begin_utc=%s\\n" "$i" "$(date -u +%FT%TZ)" >> "$OUT/manifest.txt"
  "${ADB[@]}" shell am force-stop "$PKG"
  sleep 2
  "${ADB[@]}" shell am start -W -n "$COMP" > "$OUT/startup/cold-$i.txt"
  printf 'cold,%s,%s,startup/cold-%s.txt\n' "$i" "$(date -u +%FT%TZ)" "$i" >> "$OUT/startup/index.csv"
  sleep "$IDLE_SHORT"
  capture_memory idle60 "$i"
  sleep "$((IDLE_LONG > IDLE_SHORT ? IDLE_LONG-IDLE_SHORT : 0))"
  capture_memory idle300 "$i"
  printf "cycle_%s_idle300_end_utc=%s\\n" "$i" "$(date -u +%FT%TZ)" >> "$OUT/manifest.txt"
  "${ADB[@]}" shell pidof "$PKG" > "$OUT/startup/pid-before-$i.txt" || true
  # HOME-switch evidence is observational; HOME may route to this same launcher.
  "${ADB[@]}" shell input keyevent KEYCODE_HOME
  sleep 2
  "${ADB[@]}" shell dumpsys activity activities > "$OUT/startup/home-foreground-$i.txt"
  "${ADB[@]}" shell pidof "$PKG" > "$OUT/startup/pid-after-home-$i.txt" || true
  "${ADB[@]}" shell am start -W -n "$COMP" > "$OUT/startup/home-return-$i.txt"
  "${ADB[@]}" shell dumpsys activity activities > "$OUT/startup/home-return-foreground-$i.txt"
  # Navigate to system Settings rather than sending HOME (which may resolve to BaroLauncher).
  # If Settings cannot launch, mark this warm-return sample unavailable.
  if "${ADB[@]}" shell am start -W -a android.settings.SETTINGS > "$OUT/startup/away-$i.txt" 2>&1; then
    sleep 2
    "${ADB[@]}" shell dumpsys activity activities > "$OUT/startup/settings-foreground-$i.txt"
    "${ADB[@]}" shell pidof "$PKG" > "$OUT/startup/pid-before-settings-return-$i.txt" || true
    "${ADB[@]}" shell am start -W -n "$COMP" > "$OUT/startup/warm-return-$i.txt"
    printf 'warm_return_from_settings,%s,%s,startup/warm-return-%s.txt\n' "$i" "$(date -u +%FT%TZ)" "$i" >> "$OUT/startup/index.csv"
  else
    printf 'warm_return_unavailable,%s,%s,startup/away-%s.txt\n' "$i" "$(date -u +%FT%TZ)" "$i" >> "$OUT/startup/index.csv"
  fi
done
"${ADB[@]}" shell dumpsys activity processes > "$OUT/environment/activity-processes.txt"
"${ADB[@]}" logcat -d -v threadtime > "$OUT/logs/logcat.txt"
"${ADB[@]}" shell settings get secure enabled_accessibility_services > "$OUT/environment/accessibility-after.txt"
"${ADB[@]}" shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/environment/home-after.txt" || true
echo "Raw evidence: $OUT"
python3 "$(dirname "$0")/summarize.py" "$OUT"
echo "Review metadata provenance and redact logs before sharing. Do not call emulator data hardware evidence."
