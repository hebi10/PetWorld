#!/usr/bin/env bash
set -uo pipefail
mkdir -p qa/quick-record
adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
adb shell am instrument -w -e class com.haebi.petworld.PetWorldQuickRecordCaptureTest com.haebi.petworld.test/androidx.test.runner.AndroidJUnitRunner | tee qa/quick-record/instrumentation.log
status=${PIPESTATUS[0]}
adb logcat -d -t 1000 > qa/quick-record/logcat.txt || true
adb pull /sdcard/Android/data/com.haebi.petworld/files/ui05-captures qa/quick-record/screenshots || true
for mode in light dark; do
    count=$(find "qa/quick-record/screenshots/$mode" -maxdepth 1 -type f -name '*.png' 2>/dev/null | wc -l)
    echo "$mode quick-record PNG: $count"
    if [ "$count" -ne 3 ]; then
        echo "Expected 3 quick-record states in $mode"
        exit 1
    fi
done
grep -q '^OK (1 test)' qa/quick-record/instrumentation.log || exit 1
exit "$status"
