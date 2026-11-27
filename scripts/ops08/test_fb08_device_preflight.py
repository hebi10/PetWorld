"""Execute the actual embedded preflight against synthetic GitHub metadata.
No network, credentials, signing or deployment. The exact candidate is pinned.
"""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/fb08-device-apk.yml"
BASE = "8e67ce4d3fa143524556e65076d92a6134d245a5"
CANDIDATE = "e9f8f988731123065f87a81940a0ca8041dcb549"
TRUSTED_MAIN = "f" * 40
CHANGES = [
    {"filename": ".github/workflows/fb08-device-apk.yml", "status": "modified"},
    {"filename": "docs/qa/FB-08-B_보호개발APK_검증경로.md", "status": "modified"},
    {"filename": "scripts/ops08/test_fb08_device_preflight.py", "status": "added"},
]

def program():
    raw = WORKFLOW.read_text(encoding="utf-8")
    marker = "          python3 - <<'PY'\n"
    begin = raw.index(marker) + len(marker)
    end = raw.index("\n          PY", begin)
    return compile(textwrap.dedent(raw[begin:end]), str(WORKFLOW), "exec")

class ReviewedPreflight(unittest.TestCase):
    def run_gate(self, *, requested=CANDIDATE, ref="refs/heads/main",
                 confirmation="FB08-DEVICE-APK-ONLY", merge_base=BASE,
                 changes=None, bad_ci=False, bad_isolation=False, bad_main=False):
        changes = copy.deepcopy(CHANGES if changes is None else changes)
        raw = WORKFLOW.read_text(encoding="utf-8")
        block = raw.split("          required = [", 1)[1].split("          ]", 1)[0]
        names = re.findall(r"'([^']+)'", block)
        self.assertGreaterEqual(len(names), 16)
        runs = [
            {"name": name, "head_sha": requested, "event": "pull_request",
             "id": index + 1, "run_number": 1, "run_attempt": 1,
             "status": "completed", "conclusion": "failure" if bad_ci and index == 0 else "success"}
            for index, name in enumerate(names)
        ]
        main_runs = [
            {"name": name, "head_sha": TRUSTED_MAIN, "event": "push", "id": 999,
             "run_number": 1, "run_attempt": 1, "status": "completed",
             "conclusion": "failure" if bad_main else "success"}
            for name in ("Android CI", "OPS-08 final main source and release policy verification")
        ]
        def github(args, **kwargs):
            self.assertEqual(args[:2], ["gh", "api"])
            endpoint = args[2]
            if endpoint.endswith("/git/ref/heads/main"):
                payload = {"object": {"sha": TRUSTED_MAIN}}
            elif endpoint.endswith("/pulls/86"):
                payload = {"state": "open", "draft": True,
                           "head": {"repo": {"full_name": "hebi10/PetWorld"}, "sha": requested,
                                    "ref": "feature/fb08-integrated-qa-20261125"},
                           "base": {"repo": {"full_name": "hebi10/PetWorld"}, "ref": "main"}}
            elif "/compare/" in endpoint:
                payload = {"merge_base_commit": {"sha": merge_base}, "files": changes}
            elif "/attempts/" in endpoint and "/jobs" in endpoint:
                payload = {"jobs": [
                    {"name": f"Room guest/A/B isolated - API {api}",
                     "conclusion": "failure" if bad_isolation and api == 35 else "success"}
                    for api in (29, 35, 36)]}
            elif "/actions/runs?" in endpoint and "event=pull_request" in endpoint:
                payload = {"workflow_runs": runs}
            elif "/actions/runs?" in endpoint and "event=push" in endpoint:
                payload = {"workflow_runs": main_runs}
            else:
                raise AssertionError("Unexpected API: " + endpoint)
            return subprocess.CompletedProcess(args, 0, stdout=json.dumps(payload), stderr="")
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "output"
            env = {"GITHUB_REPOSITORY": "hebi10/PetWorld", "GITHUB_REF": ref,
                   "GITHUB_SHA": TRUSTED_MAIN, "CONFIRMATION": confirmation,
                   "REVIEWED_SHA": requested, "GITHUB_OUTPUT": str(output)}
            with patch.dict(os.environ, env, clear=True), patch("subprocess.run", side_effect=github), contextlib.redirect_stdout(io.StringIO()):
                exec(program(), {})
            self.assertEqual(output.read_text(), "reviewed_sha=" + CANDIDATE + "\n")

    def test_reviewed_synchronized_candidate(self):
        self.run_gate()

    def test_old_merge_base_rejected(self):
        with self.assertRaises(SystemExit):
            self.run_gate(merge_base="45ba20d7601dde5338b82a5f2b153d3a06c359bd")

    def test_other_candidate_rejected_even_with_matching_pr(self):
        with self.assertRaises(SystemExit):
            self.run_gate(requested="a" * 40)

    def test_unknown_main_change_rejected(self):
        with self.assertRaises(SystemExit):
            self.run_gate(changes=CHANGES + [{"filename": "app/build.gradle.kts", "status": "modified"}])

    def test_missing_review_change_rejected(self):
        with self.assertRaises(SystemExit):
            self.run_gate(changes=CHANGES[:2])

    def test_wrong_status_rejected(self):
        changes = copy.deepcopy(CHANGES)
        changes[0]["status"] = "added"
        with self.assertRaises(SystemExit):
            self.run_gate(changes=changes)

    def test_wrong_confirmation_rejected(self):
        with self.assertRaises(SystemExit):
            self.run_gate(confirmation="")

    def test_non_main_rejected(self):
        with self.assertRaises(SystemExit):
            self.run_gate(ref="refs/heads/feature")

    def test_failed_candidate_ci_rejected(self):
        with self.assertRaises(SystemExit):
            self.run_gate(bad_ci=True)

    def test_failed_isolation_api_rejected(self):
        with self.assertRaises(SystemExit):
            self.run_gate(bad_isolation=True)

    def test_failed_main_ci_rejected(self):
        with self.assertRaises(SystemExit):
            self.run_gate(bad_main=True)

if __name__ == "__main__":
    unittest.main()
