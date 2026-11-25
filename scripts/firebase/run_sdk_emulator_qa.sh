#!/usr/bin/env bash
set -euo pipefail

# Only the fixed demo emulator project; no real-project credentials or live calls.
if [[ "${FIREBASE_AUTH_EMULATOR_HOST:-}" != "127.0.0.1:9099" ||
      "${FIRESTORE_EMULATOR_HOST:-}" != "127.0.0.1:8080" ]]; then
    echo "Local Auth and Firestore emulator addresses required" >&2
    exit 1
fi
./gradlew :app:connectedFirebaseDevAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.haebi.petworld.FirebaseDevIsolatedSdkEmulatorTest \
  -Pandroid.testInstrumentationRunnerArguments.petworldLocalSdkQa=true \
  --no-daemon --stacktrace

python3 - <<'PY'
import pathlib
import xml.etree.ElementTree as ET
expected = {
    "sameAccountBindingAndRealRulesRejectOtherAccountAndGuest",
    "pendingWritesMustBeAcknowledgedBeforeMemorySessionClosesAndLegacyDataStays",
}
results = {}
for path in pathlib.Path("app/build/outputs/androidTest-results/connected").rglob("*.xml"):
    for case in ET.parse(path).getroot().iter("testcase"):
        if case.get("classname") != "com.haebi.petworld.FirebaseDevIsolatedSdkEmulatorTest":
            continue
        name = case.get("name")
        if name in expected:
            results[name] = results.get(name, []) + [
                not any(case.find(kind) is not None for kind in ("failure", "error", "skipped"))
            ]
if set(results) != expected or any(not all(runs) for runs in results.values()):
    raise SystemExit("Required two actual SDK tests did not both pass")
print("Verified both isolated Android SDK scenarios executed and passed.")
PY
