#!/usr/bin/env bash
set -euo pipefail
mkdir -p evidence/tv-ui
PKG=com.rampey.materialtv
COMP="$PKG/.HomeActivity"
adb wait-for-device
adb shell getprop ro.build.version.sdk | tr -d '\r' | grep -qx 28
adb shell pm list features > evidence/tv-ui/features.txt
grep -Eq 'android.software.leanback|android.hardware.type.television' evidence/tv-ui/features.txt || { echo "Not a TV system image" >&2; exit 1; }
adb shell wm size > evidence/tv-ui/display.txt
adb shell wm density >> evidence/tv-ui/display.txt
adb shell cat /proc/meminfo > evidence/tv-ui/system-meminfo.txt
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME > evidence/tv-ui/home-before.txt || true
adb shell settings get secure enabled_accessibility_services > evidence/tv-ui/accessibility-before.txt
adb install -r build/material-tv-launcher.apk
adb shell am start -W -n "$COMP" | tee evidence/tv-ui/launch.txt
sleep 5
adb exec-out screencap -p > evidence/tv-ui/01-launch.png
for key in 22 20 21 19 23 4; do
  adb shell input keyevent "$key"
  sleep 1
done
adb exec-out screencap -p > evidence/tv-ui/02-dpad-back.png
adb shell dumpsys meminfo "$PKG" > evidence/tv-ui/meminfo.txt
adb shell dumpsys gfxinfo "$PKG" framestats > evidence/tv-ui/frames.txt
adb shell am start -W -a android.settings.SETTINGS > evidence/tv-ui/settings.txt || true
sleep 2
adb shell am start -W -n "$COMP" > evidence/tv-ui/return.txt
adb exec-out screencap -p > evidence/tv-ui/03-return.png
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME > evidence/tv-ui/home-after.txt || true
adb shell settings get secure enabled_accessibility_services > evidence/tv-ui/accessibility-after.txt
adb logcat -d -v threadtime > evidence/tv-ui/logcat.txt
diff -u evidence/tv-ui/accessibility-before.txt evidence/tv-ui/accessibility-after.txt
diff -u evidence/tv-ui/home-before.txt evidence/tv-ui/home-after.txt
echo "Android TV smoke evidence collected; screenshots require human QA review."
