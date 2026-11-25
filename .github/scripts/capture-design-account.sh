#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa/design-account
adb install -r app/build/outputs/apk/firebaseDev/app-firebaseDev.apk
adb install -r app/build/outputs/apk/androidTest/firebaseDev/app-firebaseDev-androidTest.apk
for mode in light dark; do
  if [[ "$mode" == light ]]; then adb shell cmd uimode night no; else adb shell cmd uimode night yes; fi
  adb shell am force-stop com.haebi.petworld.dev
  adb shell am instrument -w -e class com.haebi.petworld.DesignAccountCaptureTest -e designTheme "$mode" com.haebi.petworld.dev.test/androidx.test.runner.AndroidJUnitRunner | tee "/tmp/design-account-$mode.log"
  grep -q 'OK (1 test)' "/tmp/design-account-$mode.log"
done
adb pull /sdcard/Android/data/com.haebi.petworld.dev/files/design-account/. qa/design-account/
for mode in light dark; do
  test -s "qa/design-account/$mode/A01-account-guest-top.png"
  test -s "qa/design-account/$mode/A01-account-guest-actions.png"
done
