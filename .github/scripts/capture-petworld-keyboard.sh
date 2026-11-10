#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-normal}"
OUT="qa/keyboard/$MODE"
mkdir -p "$OUT"

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell settings put secure show_ime_with_hard_keyboard 1

adb shell wm size > "$OUT/display-size.txt"
adb shell wm density > "$OUT/display-density.txt"
adb shell settings get system font_scale > "$OUT/font-scale.txt"

set +e
adb shell am instrument -w -e class com.haebi.petworld.PetWorldKeyboardSaveTest \
    com.haebi.petworld.test/androidx.test.runner.AndroidJUnitRunner \
    2>&1 | tee "$OUT/instrumentation.log"
status=${PIPESTATUS[0]}
set -e

adb logcat -d -t 2500 > "$OUT/logcat.txt" || true
adb pull /sdcard/Android/data/com.haebi.petworld/files/ui06e-keyboard "$OUT/screenshots" || true

screenshots=$(find "$OUT/screenshots" -type f -name '*.png' 2>/dev/null | wc -l | tr -d ' ')
echo "UI-06-E $MODE screenshots: $screenshots"
test "$status" -eq 0
grep -q '^OK (1 test)' "$OUT/instrumentation.log"
test "$screenshots" -ge 22
