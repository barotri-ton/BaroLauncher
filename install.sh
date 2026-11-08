#!/usr/bin/env bash
# Install the launcher on an Android TV over ADB and grant what it needs.
#   ./install.sh <tv-ip> [path/to/BaroLauncher.apk]
# Uses BaroLauncher.apk next to this script (the release download), else build/BaroLauncher.apk.
# Before: on the TV, turn on Developer options > USB debugging (and Network debugging if listed), then accept the
# "Allow USB debugging?" prompt the first time this connects.
set -euo pipefail
IP="${1:?usage: ./install.sh <tv-ip> [apk] [--enable-home-key]}"
# Accessibility interception is optional and must be explicitly requested.
ENABLE_HOME_KEY=false
for arg in "${@:2}"; do
  if [[ "$arg" == "--enable-home-key" ]]; then ENABLE_HOME_KEY=true; fi
done
HERE="$(cd "$(dirname "$0")" && pwd)"
APK=""
if [[ "${2:-}" != "--enable-home-key" ]]; then APK="${2:-}"; fi
if [[ -z "$APK" ]]; then
  for c in "$HERE/BaroLauncher.apk" "$HERE/build/BaroLauncher.apk"; do
    [[ -f "$c" ]] && { APK="$c"; break; }
  done
fi
[[ -n "$APK" && -f "$APK" ]] || { echo "No APK found. Put BaroLauncher.apk next to install.sh, or pass its path." >&2; exit 1; }
PKG=com.rampey.materialtv
T="$IP:5555"
S() { adb -s "$T" shell "$@" | tr -d '\r'; }

if ! adb connect "$T" | grep -q "connected"; then
  echo "Can't reach the TV at $IP. Check the IP and that USB debugging (or Network debugging) is on." >&2
  exit 1
fi
echo "If the TV asks \"Allow USB debugging?\", accept it. Waiting for the TV ..."
adb -s "$T" wait-for-device
echo "Installing $APK ..."
adb -s "$T" install -r "$APK"

# Now Playing card: see which app is playing (reads no notifications).
S cmd notification allow_listener "$PKG/$PKG.MediaListener"
# Low-RAM TVs (ro.config.low_ram=true) ignore the line above: Now Playing reads the media session dump instead.
if [[ "$(S getprop ro.config.low_ram)" == "true" ]]; then
  S pm grant "$PKG" android.permission.DUMP || true
fi
# Dock order: per-app screen time, read on the TV only.
S cmd appops set "$PKG" GET_USAGE_STATS allow

# Weather: the TV's rough location, when it has one (otherwise the city is guessed from the home's IP).
S pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION || true

# Continue tab: read what apps offer under Continue watching (only used if you turn the tab on).
S pm grant "$PKG" android.permission.READ_TV_LISTINGS || true

# Optional Home-key accessibility interception. Disabled by default to preserve
# stock launcher behavior and prevent unexpected remote HOME redirection.
if [[ "$ENABLE_HOME_KEY" == true ]]; then
  SVC="$PKG/$PKG.HomeKeyService"
  CUR="$(S settings get secure enabled_accessibility_services)"
  if [[ "$CUR" != *"$SVC"* ]]; then
    if [[ -z "$CUR" || "$CUR" == "null" ]]; then NEW="$SVC"; else NEW="$CUR:$SVC"; fi
    S settings put secure enabled_accessibility_services "$NEW"
  fi
  S settings put secure accessibility_enabled 1
  echo "Optional Home-key interception enabled. Undo with ./uninstall.sh $IP"
else
  echo "Home-key interception NOT enabled. Stock launcher behavior preserved."
fi

S am start -n "$PKG/.HomeActivity" >/dev/null
for _ in 1 2 3 4 5 6 7 8 9 10; do
  # Read the whole dump first: grep -q/-m1 closing a pipe early makes it fail under pipefail.
  ACT="$(S dumpsys activity activities)"
  if grep ResumedActivity <<<"$ACT" | grep "$PKG" >/dev/null; then
    echo "Done: Material TV Launcher is on screen. Use Android Home app settings to select launcher. Undo with ./uninstall.sh $IP"
    exit 0
  fi
  sleep 1
done
echo "Installed, but the launcher isn't on screen yet. Use Android Home app settings to select launcher. Undo with ./uninstall.sh $IP"
