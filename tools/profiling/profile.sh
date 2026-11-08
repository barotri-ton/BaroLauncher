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
mkdir -p "$OUT"/{environment,startup,memory,frames,logs,screenshots,timeline,host}
printf 'filename,utc,context\n' > "$OUT/screenshots/index.csv"
printf 'cycle,phase,utc,host_loadavg,host_mem_available_kb\n' > "$OUT/host/index.csv"
record_host() {
  local cycle="$1" phase="$2" load available
  load=$(cut -d " " -f 1-3 /proc/loadavg | tr " " ";")
  available=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
  printf '%s,%s,%s,%s,%s\n' "$cycle" "$phase" "$(date -u +%FT%TZ)" "$load" "$available" >> "$OUT/host/index.csv"
  cat /proc/stat > "$OUT/host/cpu-${cycle}-${phase}.txt"
  cat /proc/pressure/cpu > "$OUT/host/pressure-${cycle}-${phase}.txt" 2>/dev/null || printf 'unavailable\n' > "$OUT/host/pressure-${cycle}-${phase}.txt"
}
printf 'kind=%s\nserial=%s\napi=%s\nstart_utc=%s\nruns=%s\nsettle_seconds=%s\nshort_idle_seconds=%s\nlong_idle_seconds=%s\n' "$KIND" "$SERIAL" "$API" "$(date -u +%FT%TZ)" "$RUNS" "$SETTLE" "$IDLE_SHORT" "$IDLE_LONG" > "$OUT/manifest.txt"
for prop in ro.product.model ro.build.fingerprint ro.product.cpu.abi ro.config.low_ram; do
  printf '%s=%s\n' "$prop" "$("${ADB[@]}" shell getprop "$prop" | tr -d '\r')" >> "$OUT/manifest.txt"
done
"${ADB[@]}" shell wm size > "$OUT/environment/display.txt"
"${ADB[@]}" shell wm density >> "$OUT/environment/display.txt"
"${ADB[@]}" shell dumpsys package "$PKG" > "$OUT/environment/package.txt"
"${ADB[@]}" shell cat /proc/meminfo > "$OUT/environment/system-meminfo.txt"
"${ADB[@]}" shell pm list packages > "$OUT/environment/packages.txt"
"${ADB[@]}" shell pm list packages -d > "$OUT/environment/disabled-packages-before.txt"
"${ADB[@]}" shell pm list packages -e > "$OUT/environment/enabled-packages-before.txt"
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
  printf "cycle_%s_cold_begin_utc=%s\n" "$i" "$(date -u +%FT%TZ)" >> "$OUT/manifest.txt"
  record_host "$i" before-cold
  "${ADB[@]}" shell log -t BaroProfile "cycle=$i phase=cold_begin" || true
  printf "force_stop_begin_cycle_%s_utc=%s\n" "$i" "$(date -u +%FT%TZ)" >> "$OUT/timeline/force-stop-events.txt"
  "${ADB[@]}" shell am force-stop "$PKG" > "$OUT/timeline/force-stop-$i-stdout.txt" 2> "$OUT/timeline/force-stop-$i-stderr.txt"
  printf "force_stop_end_cycle_%s_utc=%s\n" "$i" "$(date -u +%FT%TZ)" >> "$OUT/timeline/force-stop-events.txt"
  "${ADB[@]}" shell dumpsys gfxinfo "$PKG" reset > "$OUT/timeline/gfx-reset-cold-$i.txt" 2>&1 || true
  sleep 2
  # API28 emulator can lose its ActivityManager binder after ADB install/settle.
  # Retry only the launch; retain all attempts for diagnosis and never claim a cold start on failure.
  launched=0
  for attempt in 1 2 3; do
    if timeout 90s "${ADB[@]}" shell am start -W -n "$COMP" > "$OUT/startup/cold-$i.txt" 2> "$OUT/startup/cold-$i-stderr.txt" && ! grep -Eq 'Failure calling service|Broken pipe|Error:' "$OUT/startup/cold-$i.txt"; then
      launched=1
      break
    fi
    cp "$OUT/startup/cold-$i.txt" "$OUT/startup/cold-$i-attempt-$attempt.txt"
    echo "Cold launch $i attempt $attempt failed; waiting for emulator ADB/ActivityManager recovery" >&2
    timeout 90s "${ADB[@]}" wait-for-device || true
    sleep 15
  done
  if (( launched != 1 )); then
    printf 'cycle_%s_failure=cold_launch_activity_manager_unavailable\n' "$i" >> "$OUT/manifest.txt"
    "${ADB[@]}" logcat -d -v threadtime > "$OUT/logs/logcat-failure.txt" 2>&1 || true
    "${ADB[@]}" shell dumpsys activity activities > "$OUT/logs/activity-failure.txt" 2>&1 || true
    echo "ERROR: cycle $i could not cold-start after three attempts; see raw diagnostics" >&2
    exit 1
  fi
  printf 'cold,%s,%s,startup/cold-%s.txt\n' "$i" "$(date -u +%FT%TZ)" "$i" >> "$OUT/startup/index.csv"
  record_host "$i" after-cold
  "${ADB[@]}" shell log -t BaroProfile "cycle=$i phase=cold_end" || true
  "${ADB[@]}" shell logcat -d -v epoch -s BaroStartup:I BaroProfile:I ActivityManager:I "*:S" > "$OUT/timeline/startup-logcat-$i.txt" || true
  "${ADB[@]}" shell dumpsys activity "$PKG" > "$OUT/timeline/activity-after-cold-$i.txt" 2>&1 || true
  "${ADB[@]}" shell dumpsys gfxinfo "$PKG" framestats > "$OUT/timeline/cold-launch-frames-$i.txt" || true
  "${ADB[@]}" shell dumpsys activity activities > "$OUT/timeline/cold-launch-foreground-$i.txt" || true
  if (( i == 1 )); then
    sleep 3
    if "${ADB[@]}" exec-out screencap -p > "$OUT/screenshots/launcher-home.png" && [[ -s "$OUT/screenshots/launcher-home.png" ]]; then
      printf 'launcher-home.png,%s,cycle=1;phase=cold-launch\n' "$(date -u +%FT%TZ)" >> "$OUT/screenshots/index.csv"
      echo "Captured launcher HOME screenshot"
    else
      rm -f "$OUT/screenshots/launcher-home.png"
      echo "WARNING: launcher screenshot unavailable" >&2
    fi
  fi
  sleep "$IDLE_SHORT"
  capture_memory idle60 "$i"
  sleep "$((IDLE_LONG > IDLE_SHORT ? IDLE_LONG-IDLE_SHORT : 0))"
  capture_memory idle300 "$i"
  printf "cycle_%s_idle300_end_utc=%s\n" "$i" "$(date -u +%FT%TZ)" >> "$OUT/manifest.txt"
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
    if (( i == 1 )); then
      if "${ADB[@]}" exec-out screencap -p > "$OUT/screenshots/android-settings.png" && [[ -s "$OUT/screenshots/android-settings.png" ]]; then
        printf 'android-settings.png,%s,cycle=1;phase=settings\n' "$(date -u +%FT%TZ)" >> "$OUT/screenshots/index.csv"
        echo "Captured Android Settings screenshot"
      else
        rm -f "$OUT/screenshots/android-settings.png"
        echo "WARNING: Settings screenshot unavailable" >&2
      fi
    fi
    "${ADB[@]}" shell pidof "$PKG" > "$OUT/startup/pid-before-settings-return-$i.txt" || true
    "${ADB[@]}" shell am start -W -n "$COMP" > "$OUT/startup/warm-return-$i.txt"
    printf 'warm_return_from_settings,%s,%s,startup/warm-return-%s.txt\n' "$i" "$(date -u +%FT%TZ)" "$i" >> "$OUT/startup/index.csv"
  else
    printf 'warm_return_unavailable,%s,%s,startup/away-%s.txt\n' "$i" "$(date -u +%FT%TZ)" "$i" >> "$OUT/startup/index.csv"
  fi
done
"${ADB[@]}" shell dumpsys activity processes > "$OUT/environment/activity-processes.txt"
"${ADB[@]}" shell pm list packages -d > "$OUT/environment/disabled-packages-after.txt"
"${ADB[@]}" shell pm list packages -e > "$OUT/environment/enabled-packages-after.txt"
diff -u "$OUT/environment/disabled-packages-before.txt" "$OUT/environment/disabled-packages-after.txt" > "$OUT/environment/disabled-packages-diff.txt" || true
diff -u "$OUT/environment/enabled-packages-before.txt" "$OUT/environment/enabled-packages-after.txt" > "$OUT/environment/enabled-packages-diff.txt" || true
"${ADB[@]}" logcat -d -v threadtime > "$OUT/logs/logcat.txt"
"${ADB[@]}" shell settings get secure enabled_accessibility_services > "$OUT/environment/accessibility-after.txt"
"${ADB[@]}" shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/environment/home-after.txt" || true
grep -Ei "Displayed com.rampey.materialtv|Fully drawn com.rampey.materialtv|Fatal signal|SIGSEGV|crash_dump|tombstone" "$OUT/logs/logcat.txt" > "$OUT/timeline/displayed-and-crash-events.txt" || true
printf "displayed_event_source=logcat_observational_not_independent_first_frame\nfully_drawn_source=logcat_if_emitted_not_guaranteed\n" >> "$OUT/manifest.txt"
echo "Raw evidence: $OUT"
python3 "$(dirname "$0")/summarize.py" "$OUT"
echo "Review metadata provenance and redact logs before sharing. Do not call emulator data hardware evidence."
