#!/bin/sh
# Screenshot the emulator into nonogram-mockups/shots/$SHOTS/<name>.png (SHOTS defaults to phase5).
# Refuses if the foreground app is not the nonogram (a stray back press once left the emulator in Notes).
# Usage: tools/shot.sh <name> [serial]
ADB=~/Library/Android/sdk/platform-tools/adb
S=${2:-emulator-5554}
DIR="$(cd "$(dirname "$0")/../../nonogram-mockups/shots" && pwd)/${SHOTS:-phase5}"
mkdir -p "$DIR"
FG=$($ADB -s "$S" shell dumpsys activity activities | grep -m1 'topResumedActivity' )
case "$FG" in *app.materialnonogram*) ;; *) echo "foreground is not the nonogram: $FG" >&2; exit 1;; esac
$ADB -s "$S" exec-out screencap -p > "$DIR/$1.png" && echo "$DIR/$1.png"
