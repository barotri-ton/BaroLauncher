#!/bin/bash
# Build the launcher APK with the Android SDK tools only (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Needs: Android SDK (platform 34, build-tools 34.0.0) and JDK 17+.
#   ANDROID_HOME   SDK location (default: ~/Library/Android/sdk on macOS, ~/Android/Sdk on Linux)
#   KEYSTORE       signing keystore (default: the standard Android debug keystore, created if missing)
#   KEYSTORE_PASS  its password (default: "android", the debug keystore's)
#   KEY_ALIAS      key alias (default: androiddebugkey)
#   MIN_SDK        lowest Android API the APK installs on (default 28 = Android 9)
#   VERSION_CODE / VERSION_NAME
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME="$HOME/Library/Android/sdk"; else ANDROID_HOME="$HOME/Android/Sdk"; fi
fi
BT="$ANDROID_HOME/build-tools/${BUILD_TOOLS:-34.0.0}"
JAR="$ANDROID_HOME/platforms/android-34/android.jar"
for f in "$BT/aapt2" "$BT/d8" "$JAR"; do [ -e "$f" ] || { echo "missing $f (set ANDROID_HOME / BUILD_TOOLS)"; exit 1; }; done
# VERSION is the single source of truth; environment overrides remain supported.
if [ -f VERSION ]; then
  FILE_VERSION_NAME=$(sed -n 's/^VERSION_NAME=//p' VERSION | head -n 1)
  FILE_VERSION_CODE=$(sed -n 's/^VERSION_CODE=//p' VERSION | head -n 1)
fi
VERSION_NAME="${VERSION_NAME:-${FILE_VERSION_NAME:-0.2.1}}"
VERSION_CODE="${VERSION_CODE:-${FILE_VERSION_CODE:-184}}"
case "$VERSION_CODE" in ''|*[!0-9]*) echo "Invalid VERSION_CODE: $VERSION_CODE"; exit 1;; esac
echo "Building BaroLauncher $VERSION_NAME (Android versionCode $VERSION_CODE)"
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res/compiled.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o "$OUT/base.apk" \
  -A assets -0 ttf --java "$OUT/gen" --min-sdk-version "${MIN_SDK:-28}" --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/res/compiled.zip"

# JDK core classes (for lambdas) plus android.jar for the Android API; d8 desugars to the TV's level.
if ! javac -nowarn --release 17 -cp "$JAR" -d "$OUT/classes" $(find src third_party "$OUT/gen" -name '*.java') 2> "$OUT/javac.log"; then
  cat "$OUT/javac.log"; echo "javac failed"; exit 1
fi

"$BT/d8" --min-api "${MIN_SDK:-28}" --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS="${KEYSTORE:-$HOME/.android/debug.keystore}"
PASS="${KEYSTORE_PASS:-android}"
ALIAS="${KEY_ALIAS:-androiddebugkey}"
if [ ! -f "$KS" ] && [ -z "${KEYSTORE:-}" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
# v1 + v2 only. On a projector running Android 9, the build signed with the default v3 block failed to install ("problem
# parsing the package") while the same code signed with v1 + v2 installed, so v3 is the likely cause (not confirmed on the
# device). v3 only matters for key rotation, which this app does not use.
"$BT/apksigner" sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" --out "$OUT/BaroLauncher.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/BaroLauncher.apk" && ls -la "$OUT/BaroLauncher.apk"
