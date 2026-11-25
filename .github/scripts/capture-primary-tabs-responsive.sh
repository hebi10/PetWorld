#!/usr/bin/env bash
set -euo pipefail
mode="${1:?compact or foldable required}"
package=com.haebi.petworld
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p qa/primary-responsive/provenance
git rev-parse HEAD > qa/primary-responsive/provenance/source-sha.txt
adb shell getprop ro.build.version.sdk | tr -d '\r' > qa/primary-responsive/provenance/api.txt
test "$(cat qa/primary-responsive/provenance/api.txt)" = 35
capture() {
  local state="$1"
  local dir="qa/primary-responsive/screenshots/$state"
  mkdir -p "$dir"
  adb shell wm size > "$dir/display-size.txt"
  adb shell wm density > "$dir/display-density.txt"
  adb shell settings get system font_scale > "$dir/font-scale.txt"
  adb shell cmd device_state state > "$dir/device-state.txt" 2>&1 || true
  adb shell am force-stop "$package"
  set +e
  adb shell am instrument -w -e class com.haebi.petworld.PetWorldPrimaryResponsiveDesignTest -e layoutCase "$state" "$runner" | tee "/tmp/pw-responsive-$state.log"
  local status=${PIPESTATUS[0]}
  set -e
  adb pull "/sdcard/Android/data/$package/files/design-responsive/$state/." "$dir/" || true
  test "$status" -eq 0
  grep -q '^OK (1 test)' "/tmp/pw-responsive-$state.log"
  for theme in light dark; do
    for screen in D01-home D01-quick-record D02-records D02-record-detail D03-health D04-statistics D05-more D05-theme-picker D05-data; do
      test -s "$dir/$theme/$screen.png"
    done
    test -s "$dir/$theme/configuration.txt"
  done
}
case "$mode" in
  compact)
    adb shell wm size 720x1600
    adb shell wm density 320
    adb shell settings put system font_scale 1.6
    capture compact160
    ;;
  foldable)
    adb shell settings put system font_scale 1.0
    adb emu unfold
    sleep 4
    capture fold-unfolded
    adb emu fold
    sleep 4
    capture fold-folded
    ;;
  *) exit 2 ;;
esac
