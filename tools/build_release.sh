#!/bin/sh
# Builds the release APK of app.materialnonogram (R8-minified; a personal build for two phones), aligned and
# signed with ~/.android/debug.keystore so it updates an installed copy in place, data intact (CLAUDE.md).
#
# Gradle does the zipalign and the signing itself (buildTypes.release.signingConfig = debug, which is that
# keystore), so this script builds, then *checks* both rather than redoing them: re-aligning a signed APK
# would break its v2 signature.
#
#   tools/build_release.sh              build + verify; print lastUpdateTime of app.materialnonogram on every device
#   tools/build_release.sh -i SERIAL    ... and install it on SERIAL first (Phase 6 does this for the phone)
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
SDK="$HOME/Library/Android/sdk"
ADB="$SDK/platform-tools/adb"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
PKG=app.materialnonogram

./gradlew -q :app:assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
OUT=app/build/outputs/apk/release/material-nonogram.apk
cp "$APK" "$OUT"

"$BT/zipalign" -c -P 16 4 "$OUT" && echo "aligned: yes (4-byte, 16 KB pages for .so)"
CERT=$("$BT/apksigner" verify --print-certs "$OUT" | awk -F': ' '/certificate SHA-256 digest/ {print $2; exit}')
KEY=$(keytool -list -v -keystore "$HOME/.android/debug.keystore" -storepass android -alias androiddebugkey 2>/dev/null | awk '/SHA256:/ {gsub(":", "", $2); print tolower($2); exit}')
if [ "$CERT" = "$KEY" ]; then echo "signed: debug keystore ($CERT)"; else echo "signed with $CERT, not the debug keystore ($KEY)" >&2; exit 1; fi
ls -l "$OUT" | awk '{printf "apk: %s (%.1f MB)\n", $9, $5 / 1048576}'

if [ "$1" = "-i" ]; then
  "$ADB" -s "$2" install -r "$OUT" >/dev/null && echo "installed on $2"
fi
for s in $("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'); do
  T=$("$ADB" -s "$s" shell dumpsys package $PKG | grep -m1 lastUpdateTime | tr -d ' ')
  echo "$s: ${T:-$PKG not installed}"
done
