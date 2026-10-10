#!/usr/bin/env bash
set -euo pipefail
serial=${1:?Usage: tools/verify-device.sh DEVICE_IP:ADB_PORT}
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
mkdir -p verification-local
adb connect "$serial"
adb -s "$serial" get-state
adb -s "$serial" shell 'getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk' > verification-local/device.txt
adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$serial" shell am instrument -w net.fuyumori.stellarss.test/androidx.test.runner.AndroidJUnitRunner | tee verification-local/instrumentation.txt
if ! grep -Eq '^OK \([0-9]+ tests?\)' verification-local/instrumentation.txt; then
    echo 'Device checks failed or did not complete. See verification-local/instrumentation.txt.' >&2
    exit 1
fi
sha256sum app/build/outputs/apk/debug/app-debug.apk > verification-local/apk.sha256
