#!/usr/bin/env bash
set -euo pipefail
APK="${1:-build/BaroLauncher.apk}"
echo "Waiting for Android package manager readiness"
adb wait-for-device
ready=0
for attempt in $(seq 1 30); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && timeout 15 adb shell cmd package list packages >/dev/null 2>&1; then
    ready=1
    break
  fi
  echo "Package manager not ready ($attempt/30)"
  sleep 10
done
if [ "$ready" -ne 1 ]; then
  echo "::error::Android package manager did not become ready"
  adb devices -l || true
  adb shell getprop sys.boot_completed || true
  timeout 20 adb logcat -d -t 300 | tail -100 || true
  exit 1
fi
installed=0
for attempt in 1 2 3; do
  echo "Installing $APK (attempt $attempt/3)"
  if timeout 180 adb install --no-streaming -r "$APK"; then
    installed=1
    break
  fi
  adb devices -l || true
  timeout 15 adb shell cmd package list packages >/dev/null 2>&1 || true
  timeout 20 adb logcat -d -t 200 | tail -60 || true
  adb reconnect offline || true
  sleep 15
done
if [ "$installed" -ne 1 ]; then
  echo "::error::APK installation failed after 3 attempts"
  exit 1
fi
