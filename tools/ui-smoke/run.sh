#!/usr/bin/env bash
set -euo pipefail
OUT="${1:-evidence/ui-smoke}"
PKG="com.rampey.materialtv"
COMP="$PKG/.HomeActivity"
mkdir -p "$OUT"/{screenshots,logs}
printf 'step,result,details\n' > "$OUT/results.csv"
record() { printf '%s,%s,%s\n' "$1" "$2" "$3" >> "$OUT/results.csv"; }
adb shell getprop ro.build.version.sdk | tr -d '\r' | grep -qx 28
adb shell pm path "$PKG" | grep -q '^package:'
adb shell settings get secure enabled_accessibility_services > "$OUT/accessibility-before.txt"
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/home-before.txt" || true
adb shell dumpsys package "$PKG" > "$OUT/package.txt"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$COMP" > "$OUT/startup.txt"
if grep -Eq 'Error:|Exception|Status: timeout' "$OUT/startup.txt"; then record startup FAIL launch_error; exit 1; fi
sleep 8
adb shell dumpsys activity activities > "$OUT/activity-after-launch.txt"
adb shell pidof "$PKG" > "$OUT/pid.txt" || true
if [[ ! -s "$OUT/pid.txt" ]]; then record startup FAIL process_not_running; exit 1; fi
record startup PASS process_running
adb exec-out screencap -p > "$OUT/screenshots/01-launch.png"
for pair in 'DOWN 20' 'RIGHT 22' 'UP 19' 'LEFT 21' 'CENTER 23' 'BACK 4'; do
  read -r label code <<< "$pair"
  adb shell input keyevent "$code"
  sleep 2
  adb exec-out screencap -p > "$OUT/screenshots/02-${label}.png"
  record "key_${label}" OBSERVED screenshot_saved
done
adb shell am start -W -a android.settings.SETTINGS > "$OUT/settings-away.txt" 2>&1
sleep 2
adb shell am start -W -n "$COMP" > "$OUT/warm-return.txt"
sleep 3
adb exec-out screencap -p > "$OUT/screenshots/03-settings-return.png"
record settings_return OBSERVED screenshot_saved
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt"
adb shell dumpsys gfxinfo "$PKG" framestats > "$OUT/gfxinfo.txt"
adb shell logcat -d -v threadtime > "$OUT/logs/logcat.txt"
adb shell settings get secure enabled_accessibility_services > "$OUT/accessibility-after.txt"
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME > "$OUT/home-after.txt" || true
if ! cmp -s "$OUT/accessibility-before.txt" "$OUT/accessibility-after.txt"; then record accessibility FAIL changed; exit 1; fi
if ! cmp -s "$OUT/home-before.txt" "$OUT/home-after.txt"; then record home_handler FAIL changed; exit 1; fi
record safety PASS home_and_accessibility_unchanged
echo "Smoke complete. D-pad screenshot capture is observational, not proof of correct focus."
