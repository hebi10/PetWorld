#!/usr/bin/env bash
set -euo pipefail
out=qa/audit-d-account
mkdir -p "$out"
git rev-parse HEAD > "$out/source-sha.txt"
printf '%s\n' 'firebaseDev; API35 Pixel 6; synthetic development config; no real Google login or server access' > "$out/scope.txt"
# Install the exact already-compiled app and test APKs. Gradle connected tests
# remove app-owned files on teardown before the screenshot can be collected.
app_apk="$(find app/build/outputs/apk/firebaseDev -type f -name '*.apk' -print -quit)"
test_apk="$(find app/build/outputs/apk/androidTest/firebaseDev -type f -name '*.apk' -print -quit)"
test -n "$app_apk" && test -n "$test_apk"
aapt="$ANDROID_HOME/build-tools/36.0.0/aapt2"
"$aapt" dump badging "$app_apk" | grep -F "package: name='com.haebi.petworld.dev'"
"$aapt" dump badging "$test_apk" | grep -F "package: name='com.haebi.petworld.dev.test'"
adb install -r "$app_apk"
adb install -r "$test_apk"
set +e
adb shell am instrument -w -e class com.haebi.petworld.FirebaseDevAccountSmokeTest com.haebi.petworld.dev.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrumentation.log"
status=${PIPESTATUS[0]}
set -e
adb logcat -d -t 1500 > "$out/logcat.txt" || true
adb pull /sdcard/Android/data/com.haebi.petworld.dev/files/audit-d-account/. "$out/" || true
test "$status" -eq 0
grep -Eq '^OK \(6 tests\)\s*$' "$out/instrumentation.log"
for screen in D23-guest-account D23-guest-account-dark; do
  test -s "$out/$screen.png"
  test -s "$out/$screen.xml"
done
(cd "$out" && sha256sum D23-guest-account.png D23-guest-account-dark.png > png-sha256.txt)
