#!/usr/bin/env bash
set -euo pipefail
out=qa/audit-e-restore
mkdir -p "$out"
# This workflow is QA-only, so the product source is the exact parent of this commit.
test "$(git rev-parse HEAD^)" = "dc21b258f596e212d897c145f4c0fbc0ce6b386a"
printf '%s\n' 'dc21b258f596e212d897c145f4c0fbc0ce6b386a' > "$out/product-source-sha.txt"
git rev-parse HEAD > "$out/qa-source-sha.txt"
printf '%s\n' 'Debug API35; synthetic backup, distinct local and backup IDs; restore never confirmed' > "$out/scope.txt"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
set +e
adb shell am instrument -w -e class com.haebi.petworld.PetWorldRestoreConfirmCaptureTest com.haebi.petworld.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrumentation.log"
status=${PIPESTATUS[0]}
set -e
adb logcat -d -t 1500 > "$out/logcat.txt" || true
adb pull /sdcard/Android/data/com.haebi.petworld/files/audit-e-restore/. "$out/" || true
test "$status" -eq 0
grep -Eq '^OK \(1 test\)\s*$' "$out/instrumentation.log"
for theme in light dark; do
  test -s "$out/$theme/D22-restore-confirm.png"
  test -s "$out/$theme/D22-restore-confirm.xml"
done
(cd "$out" && sha256sum light/D22-restore-confirm.png dark/D22-restore-confirm.png > png-sha256.txt)
