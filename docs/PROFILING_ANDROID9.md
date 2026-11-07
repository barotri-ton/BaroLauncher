# BaroLauncher — Android 9 Profiling Guide

Target: Movie Legend A7 Ultra, Android 9 / API 28, 1 GB RAM. This guide defines measurements; no measurements are claimed yet.

## Preconditions
- Keep the factory launcher installed and enabled. Do not run `pm uninstall`, `pm disable-user`, or `pm clear` on the stock HOME app.
- Enable developer options and ADB debugging. Confirm `adb devices` shows the target.
- Ensure the launcher APK is installed; current package is `com.rampey.materialtv`.
- Record firmware/build fingerprint, free memory, versionName, versionCode and whether network is connected.

## Identify app and OS
```bash
adb devices
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb shell dumpsys package com.rampey.materialtv | grep -E 'versionCode|versionName'
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
```

## Startup timing (10 repetitions, cold and warm separately)
```bash
# Force-stop only BaroLauncher, never the factory launcher.
adb shell am force-stop com.rampey.materialtv
adb shell am start -W -n com.rampey.materialtv/.HomeActivity
# Record TotalTime, WaitTime and any warnings.
```
Warm start: press HOME / switch away and return without force-stopping; record the same measurements. Do not compare cold and warm runs as if equivalent.

## Memory (PSS)
```bash
adb shell dumpsys meminfo com.rampey.materialtv
adb shell dumpsys activity processes | grep -i rampey
adb shell cat /proc/meminfo | head -n 8
```
Capture Total PSS after a consistent 60-second idle interval; repeat five times. Record other active apps and background services. Prefer median and range over a single sample.

## D-pad navigation
Manual matrix: initial focus on HOME, LEFT/RIGHT/UP/DOWN boundaries, OK launches selected app, BACK returns safely, repeated navigation, returning from external app. Film at 60 fps where available to estimate input-to-focus latency; ADB shell keyevent injection alone does not prove visible frame latency.

```bash
adb shell input keyevent KEYCODE_DPAD_LEFT
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_DPAD_UP
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_CENTER
adb shell input keyevent KEYCODE_BACK
```

## Crash/ANR and system diagnostics
```bash
adb logcat -c
# Reproduce the scenario, then capture:
adb logcat -d -v time > barolauncher-logcat.txt
adb shell dumpsys meminfo com.rampey.materialtv > barolauncher-meminfo.txt
```
Check for `FATAL EXCEPTION`, `ANR`, skipped frames and `OutOfMemoryError`. Redact credentials and personal data before sharing logs.

## Evidence format
Record: firmware, Android API, RAM, APK versionName/versionCode, build commit, test scenario, sample count, cold/warm, network state, individual values, median, and any errors.

## Safety/recovery
Before selecting BaroLauncher as default HOME, verify Android Settings provides a way to change the default Home app. Test the recovery path without removing the stock launcher. Never issue ADB commands that disable or uninstall the factory launcher.

## Exit criteria for BL-P0-02
- Commands execute on a real target or compatible Android 9 device.
- Reproducible startup and PSS baseline with sample counts and raw evidence.
- D-pad navigation matrix and observed latency documented.
- Any optimization references before/after evidence.
