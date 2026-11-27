#!/usr/bin/env bash
set -euo pipefail

API_LEVEL="${1:?Android API level is required}"
OUTPUT="qa/ui10g/api-${API_LEVEL}"
PACKAGE="com.haebi.petworld"
RUNNER="${PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"

mkdir -p "$OUTPUT"
git rev-parse HEAD > "$OUTPUT/source-sha.txt"
adb shell getprop ro.build.version.sdk | tr -d '\r' > "$OUTPUT/device-api.txt"
adb shell getprop ro.build.version.release | tr -d '\r' > "$OUTPUT/android-version.txt"
adb shell wm size > "$OUTPUT/display-size.txt"
adb shell wm density > "$OUTPUT/display-density.txt"
adb shell settings get system font_scale > "$OUTPUT/font-scale.txt"

if [ "$(tr -d '\r' < "$OUTPUT/device-api.txt")" != "$API_LEVEL" ]; then
  echo "Unexpected Android API level: $(cat "$OUTPUT/device-api.txt") (expected $API_LEVEL)" >&2
  exit 1
fi

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

run_instrumentation() {
  local case_name="$1"
  local class_name="$2"
  local expected_tests="$3"
  local output_log="$OUTPUT/${case_name}.log"

  adb shell am force-stop "$PACKAGE" || true
  adb shell am instrument -w -e class "$class_name" "$RUNNER" 2>&1 | tee "$output_log"

  if ! grep -Eq "^OK \(${expected_tests} tests?\)" "$output_log"; then
    echo "$case_name instrumentation did not pass ${expected_tests} expected tests" >&2
    return 1
  fi
  if grep -Eq "^FAILURES!!!|^INSTRUMENTATION_FAILED" "$output_log"; then
    echo "$case_name instrumentation reports failure" >&2
    return 1
  fi
}

status=0
run_instrumentation "record-list" "${PACKAGE}.PetWorldRecordListCaptureTest" 1 || status=1
adb pull "/sdcard/Android/data/${PACKAGE}/files/record-list-qa" "$OUTPUT/record-list-captures" || status=1

expected_record_screens=(
  S11-record-list S13-range-picker S13-type-picker S11-water-filtered
  S11-filter-reset S12-record-detail S12-record-edit S33-record-delete-dialog
)
shopt -s nullglob
for mode in light dark; do
  capture_dir="$OUTPUT/record-list-captures/$mode"
  captures=("$capture_dir"/*.png)
  echo "$mode record-list PNG count: ${#captures[@]}"
  if [ "${#captures[@]}" -ne "${#expected_record_screens[@]}" ]; then
    echo "Expected ${#expected_record_screens[@]} record-list screenshots in $mode on API $API_LEVEL" >&2
    status=1
  fi
  for screen in "${expected_record_screens[@]}"; do
    if [ ! -s "$capture_dir/$screen.png" ]; then
      echo "Missing or empty record-list screenshot in $mode on API $API_LEVEL: $screen.png" >&2
      status=1
    fi
  done
done

run_instrumentation "backup-restore" "${PACKAGE}.BackupRestoreDeviceTest" 3 || status=1
adb logcat -d -t 2000 > "$OUTPUT/logcat.txt" || true

if [ "$status" -eq 0 ]; then
  echo "PASS: API $API_LEVEL, 2 themes, record filtering and 3 encrypted backup tests"
else
  echo "FAIL: API $API_LEVEL, check uploaded logs, diagnostics and PNGs"
fi
exit "$status"
