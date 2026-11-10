#!/usr/bin/env bash
set -uo pipefail
mkdir -p qa/settings
adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
adb shell am instrument -w -e class com.haebi.petworld.PetWorldSettingsCaptureTest com.haebi.petworld.test/androidx.test.runner.AndroidJUnitRunner | tee qa/settings/instrumentation.log
status=${PIPESTATUS[0]}
adb logcat -d -t 1000 > qa/settings/logcat.txt || true
adb pull /sdcard/Android/data/com.haebi.petworld/files/ui04-captures qa/settings/screenshots || true
for mode in light dark; do
    count=$(find "qa/settings/screenshots/$mode" -maxdepth 1 -type f -name '*.png' 2>/dev/null | wc -l)
    echo "$mode settings PNG: $count"
    if [ "$count" -ne 5 ]; then
        echo "Expected 5 settings states in $mode"
        exit 1
    fi
done
grep -q '^OK (1 test)' qa/settings/instrumentation.log || exit 1
exit "$status"
