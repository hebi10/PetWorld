#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa/primary-tabs/screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
set +e
adb shell am instrument -w -e class com.haebi.petworld.PetWorldPrimaryTabsDesignTest com.haebi.petworld.test/androidx.test.runner.AndroidJUnitRunner | tee /tmp/petworld-primary-tabs.log
status=${PIPESTATUS[0]}
set -e
adb pull /sdcard/Android/data/com.haebi.petworld/files/design-primary-tabs/. qa/primary-tabs/screenshots/ || true
test "$status" -eq 0
grep -q '^OK (1 test)' /tmp/petworld-primary-tabs.log
for theme in light dark; do
  for screen in D01-home D02-records D03-health D04-statistics D04-statistics-summary D04-statistics-more D05-more D05-more-data; do
    test -s "qa/primary-tabs/screenshots/$theme/$screen.png"
  done
done
