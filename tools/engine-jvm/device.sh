#!/bin/sh
# Run the engine bench (Bench.kt) on a connected device or emulator through app_process (ART, JIT, no
# baseline profile), the way reference/bench/det-phone.sh ran NonoDet. Release-like: no debuggable flag.
# Usage: tools/engine-jvm/device.sh <serial> <mode> [n] [sizes] [tiers]
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
SDK="$HOME/Library/Android/sdk"
D8="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/d8"
ADB="$SDK/platform-tools/adb"
SERIAL=$1; shift
../../gradlew -q -p . benchJar
rm -rf build/dex && mkdir -p build/dex
"$D8" --release --min-api 31 --output build/dex/bench-dex.zip build/libs/bench.jar
$ADB -s "$SERIAL" push build/dex/bench-dex.zip /data/local/tmp/nono-bench.zip >/dev/null
$ADB -s "$SERIAL" shell "CLASSPATH=/data/local/tmp/nono-bench.zip app_process /system/bin app.materialnonogram.engine.tools.BenchKt $*"
$ADB -s "$SERIAL" shell rm /data/local/tmp/nono-bench.zip
