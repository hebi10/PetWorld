#!/usr/bin/env bash
set -uo pipefail
mkdir -p qa/captures
adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
adb shell am instrument -w -e class com.haebi.petworld.PetWorldScreenCaptureTest com.haebi.petworld.test/androidx.test.runner.AndroidJUnitRunner | tee qa/captures/instrumentation.log
status=${PIPESTATUS[0]}
adb logcat -d -t 1500 > qa/captures/logcat.txt || true
adb pull /sdcard/Android/data/com.haebi.petworld/files/ui-captures qa/captures/screenshots || true
for mode in light dark; do
  folder="qa/captures/screenshots/ui-captures/$mode"
  files=$(find "$folder" -maxdepth 1 -type f -name 'S*.png' 2>/dev/null | wc -l)
  echo "$mode screenshot count: $files"
  if [ "$files" -lt 22 ]; then
    echo "Incomplete $mode set: expected 22 implementation states"
    exit 1
  fi
done
if ! grep -q '^OK (1 test)' qa/captures/instrumentation.log; then
  echo "Screenshot instrumented test did not pass."
  exit 1
fi
exit "$status"
