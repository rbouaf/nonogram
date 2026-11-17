#!/bin/sh
# Copies a demo history (written by app/src/test/.../tools/DemoHistory.kt) into the debug build's game
# store on the emulator, alongside the games already there. Debug builds only (run-as).
#   tools/push_demo_history.sh /tmp/nono-demo [serial]
set -e
ADB=~/Library/Android/sdk/platform-tools/adb
S=${2:-emulator-5554}
PKG=app.materialnonogram.debug
$ADB -s "$S" shell am force-stop $PKG
for f in "$1"/*.json; do
  n=$(basename "$f")
  $ADB -s "$S" shell "run-as $PKG sh -c 'cat > files/nonograms/$n'" < "$f"
done
echo "pushed $(ls "$1"/*.json | wc -l | tr -d ' ') games"
