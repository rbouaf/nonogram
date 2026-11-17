#!/bin/sh
# Screenshot the emulator into nonogram-mockups/shots/phase4/<name>.png (half size for reading).
# Usage: tools/shot.sh <name> [serial]
ADB=~/Library/Android/sdk/platform-tools/adb
S=${2:-emulator-5554}
OUT="$(cd "$(dirname "$0")/../../nonogram-mockups/shots/phase4" && pwd)/$1.png"
$ADB -s "$S" exec-out screencap -p > "$OUT" && echo "$OUT"
