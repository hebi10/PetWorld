#!/usr/bin/env bash
set -uo pipefail
mkdir -p qa/record-list
adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
adb shell am instrument -w -e class com.haebi.petworld.PetWorldRecordListCaptureTest com.haebi.petworld.test/androidx.test.runner.AndroidJUnitRunner | tee qa/record-list/instrumentation.log
status=${PIPESTATUS[0]}
adb logcat -d -t 1000 > qa/record-list/logcat.txt || true
adb pull /sdcard/Android/data/com.haebi.petworld/files/record-list-qa qa/record-list/screenshots || true
for mode in light dark; do
    count=$(find "qa/record-list/screenshots/$mode" -maxdepth 1 -type f -name '*.png' 2>/dev/null | wc -l)
    echo "$mode record list PNG: $count"
    if [ "$count" -ne 8 ]; then
        echo "Expected 8 record-list/detail/edit states in $mode"
        exit 1
    fi
    for state in S11-record-list S13-range-picker S13-type-picker S11-water-filtered S11-filter-reset S12-record-detail S12-record-edit S33-record-delete-dialog; do
        test -s "qa/record-list/screenshots/$mode/$state.png" || { echo "Missing $mode/$state.png"; exit 1; }
    done
done
grep -q '^OK (1 test)' qa/record-list/instrumentation.log || exit 1
mkdir -p qa/record-list/provenance
(cd qa/record-list && find screenshots -name '*.png' -type f -print0 | sort -z | xargs -0 sha256sum > provenance/png-sha256.txt) || exit 1
exit "$status"
