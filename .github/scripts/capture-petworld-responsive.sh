#!/usr/bin/env bash
set -uo pipefail

overall=0

MODE="${1:-compact}"
BASE_DIR="qa/responsive"
mkdir -p "$BASE_DIR"

adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1

capture_state() {
  local state="$1"
  local output="$BASE_DIR/$state"
  mkdir -p "$output"

  adb shell wm size > "$output/display-size.txt" 2>&1 || true
  adb shell wm density > "$output/display-density.txt" 2>&1 || true
  adb shell settings get system font_scale > "$output/font-scale.txt" 2>&1 || true
  adb shell cmd device_state state > "$output/device-state.txt" 2>&1 || true

  adb shell am force-stop com.haebi.petworld || true
  adb shell am instrument -w -e class com.haebi.petworld.PetWorldScreenCaptureTest com.haebi.petworld.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$output/instrumentation.log"
  local status=${PIPESTATUS[0]}
  local capture_status=0

  adb logcat -d -t 2500 > "$output/logcat.txt" || true
  adb pull /sdcard/Android/data/com.haebi.petworld/files/ui-captures "$output/screenshots" || true

  for theme in light dark; do
    local count
    count=$(find "$output/screenshots/$theme" -maxdepth 1 -type f -name 'S*.png' 2>/dev/null | wc -l | awk '{print $1}')
    echo "$state/$theme screenshot count: $count"
    if [ "$count" -lt 24 ]; then
      echo "Incomplete $theme set for $state: expected 24 implementation states"
      capture_status=1
    fi
  done

  if [ "$status" -ne 0 ] || ! grep -q '^OK (1 test)' "$output/instrumentation.log"; then
    echo "Responsive instrumentation test failed for $state."
    capture_status=1
  fi
  return "$capture_status"
}

case "$MODE" in
  compact)
    capture_state "compact-360dp-font160" || overall=1
    ;;
  foldable)
    adb shell settings put system font_scale 1.0
    adb emu unfold
    sleep 4
    capture_state "foldable-unfolded" || overall=1

    states=$(adb shell cmd device_state print-states 2>&1 || true)
    printf '%s\n' "$states" > "$BASE_DIR/foldable-device-states.txt"
    if printf '%s' "$states" | grep -q "name='HALF_OPENED'"; then
      adb shell cmd device_state state 1 || overall=1
      sleep 4
      capture_state "foldable-half-opened" || overall=1
      adb shell cmd device_state state reset || overall=1
      sleep 3
      adb emu unfold || overall=1
      sleep 3
    else
      echo "HALF_OPENED posture is not exposed by this AVD."
      overall=1
    fi

    adb emu fold || overall=1
    sleep 4
    capture_state "foldable-folded" || overall=1
    adb emu unfold || overall=1
    ;;
  *)
    echo "Usage: $0 compact|foldable"
    exit 2
    ;;
esac

exit "$overall"
