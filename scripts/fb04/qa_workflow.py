#!/usr/bin/env python3
"""Offline, secret-free negative QA for the manual FB-04 development APK workflow."""

from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/fb04-device-apk.yml"


def job(text: str, name: str) -> str:
    match = re.search(r"(?m)^  " + re.escape(name) + r":\n", text)
    if not match:
        raise ValueError("Missing job: " + name)
    next_job = re.search(r"(?m)^  [a-z][a-z-]*:\n", text[match.end():])
    return text[match.start():match.end() + next_job.start() if next_job else len(text)]


def require(value: bool, reason: str) -> None:
    if not value:
        raise ValueError(reason)


def validate_safety(text: str) -> None:
    header = text.split("\njobs:\n", 1)[0]
    require("\non:\n  workflow_dispatch:\n" in header, "Must be manual dispatch only")
    require(not re.search(r"(?m)^  (?:push|pull_request|workflow_run|schedule):", header),
            "Automatic or scheduled invocation is prohibited")
    require("  contents: read\n" in header, "Global content permissions must remain read-only")
    require("github.ref == 'refs/heads/main'" in text, "Main-only execution gate missing")

    preflight = job(text, "preflight")
    require("    permissions:\n      contents: read\n      actions: read\n      pull-requests: read\n"
            in preflight, "Missing least-privilege GitHub metadata read scopes")
    require("github.event_name == 'workflow_dispatch'" in preflight,
            "Manual confirmation gate missing")
    require("FB04-DEVICE-APK-ONLY" in preflight, "Manual confirmation string missing")
    require("repos/' + repo + '/pulls/55" in preflight,
            "Exact PR #55 source verification missing")
    require("'head_sha') == sha" in preflight,
            "Exact-source workflow run filter missing")
    for item in (
        "Android CI", "Firebase Rules CI", "Android Backup Restore Emulator QA",
        "PetWorld Firebase account UI smoke QA", "PetWorld FB-03 scoped data isolation QA",
        "Play exact-source verification", "PetWorld firebaseDev signing fingerprints",
        "Android Quick Record Sheet UI QA", "Android Settings and Profile Manager UI QA",
        "OPS-07 integrated offline Play release policy QA",
        "OPS-09 offline single-approval internal release safety",
    ):
        require("'" + item + "'" in preflight, "Required QA missing: " + item)

    require("pr['base']['ref'] == 'main'" in preflight and
            "pr['base']['sha'] == current_main" in preflight and
            "feature/fb04-integrate-pw-fixes-20261124" in preflight,
            "Integrated candidate must target exact main")
    require("allowed_main_only" in preflight and "comparison['files']" in preflight and
            "Trusted main contains untested application or policy changes" in preflight,
            "Untested main changes must fail closed")
    require("for api in (29, 35, 36)" in preflight and
            "Missing successful account isolation matrix" in preflight,
            "All three account isolation jobs must pass")
    require("OPS-08 final main source and release policy verification" in preflight and
            "Latest main CI not successful" in preflight,
            "Exact main Android and release-policy CI required")

    public = job(text, "public-config")
    require("environment: petworld-dev-signing" in public,
            "Configuration must come from a protected environment")
    require("secrets.PETWORLD_DEV_GOOGLE_SERVICES_JSON_BASE64" in public,
            "Protected console configuration is missing")
    require("project.get('storage_bucket') != 'petworld-dev.firebasestorage.app'" in public,
            "Wrong or missing Firebase bucket must be rejected")
    require("len(oauth) != 1" in public, "Ambiguous OAuth client must be rejected")
    require("path: public-config/google-services.json" in public,
            "Expected public-only config artifact is missing")

    build = job(text, "build")
    require("permissions:\n      contents: read" in build,
            "Untrusted builder must have read-only source rights")
    require("actions/checkout@v4" in build and
            "ref: ${{ needs.preflight.outputs.reviewed_sha }}" in build,
            "Builder must checkout reviewed immutable SHA")
    require('test "$(git rev-parse HEAD)" = "$REVIEWED_SHA"' in build,
            "Builder must independently verify its Git SHA")
    require("secrets." not in build and "petworld-dev-signing" not in build and
            "GH_TOKEN" not in build,
            "Untrusted builder must not have secrets or metadata token")
    require(":app:assembleFirebaseDev" in build and
            "petworld-dev.firebasestorage.app" in build,
            "Builder must use the isolated Firebase development variant")
    require("input_apk_sha256" in build and "provenance.json" in build,
            "Build provenance must be recorded")

    sign = job(text, "sign")
    require("environment: petworld-dev-signing" in sign,
            "Signing must use protected main-only environment")
    require("    permissions:\n      contents: read\n      pull-requests: read\n" in sign,
            "Signer must recheck PR with read-only permission")
    for forbidden in ("actions/checkout@", "./gradlew", "gradle/actions/",
                      "npm ", "pip install", "curl ", "wget "):
        require(forbidden not in sign,
                "Signer must not execute PR code: " + forbidden)
    require("GITHUB_SHA" in sign and "REVIEWED_SHA" in sign and
            "input_apk_sha256" in sign and "pulls/55" in sign and
            "pr['base']['sha'] == os.environ['GITHUB_SHA']" in sign and
            "pr['base']['ref'] == 'main'" in sign,
            "Signer must recheck source and input APK")
    require("secrets.PETWORLD_DEV_KEYSTORE_BASE64" in sign and
            "secrets.PETWORLD_DEV_STORE_PASSWORD" in sign,
            "Existing dedicated developer key required")
    require('"$APK_SIGNER" sign' in sign and '"$APK_SIGNER" verify' in sign and
            "certificate_sha256" in sign,
            "Protected signing and certificate checks missing")
    require("qa-signed/" in sign and "retention-days: 7" in sign,
            "Only temporary QA APK evidence should be retained")


def shell_blocks(text: str):
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if line == "        run: |":
            body = []
            for item in lines[index + 1:]:
                if item.strip() and not item.startswith("          "):
                    break
                body.append(item[10:] if item.startswith("          ") else "")
            yield "\n".join(body) + "\n"


def python_heredocs(shell: str):
    lines = shell.splitlines()
    for index, item in enumerate(lines):
        if item.strip() == "python3 - <<'PY'":
            end = index + 1
            while end < len(lines) and lines[end] != "PY":
                end += 1
            require(end < len(lines), "Unterminated Python heredoc")
            yield "\n".join(lines[index + 1:end]) + "\n"


class WorkflowSecurityRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_manual_workflow_requires_correct_read_permissions(self):
        validate_safety(self.workflow)

    def test_embedded_bash_and_python_syntax(self):
        scripts = list(shell_blocks(self.workflow))
        self.assertGreaterEqual(len(scripts), 10)
        for number, shell in enumerate(scripts, start=1):
            check = subprocess.run(["bash", "-n"], input=shell,
                                   capture_output=True, text=True, check=False)
            self.assertEqual(check.returncode, 0,
                             f"Bash block {number}: {check.stderr}")
            for python in python_heredocs(shell):
                compile(python, f"fb04-workflow-block-{number}", "exec")

    def test_no_automatic_trigger(self):
        with self.assertRaises(ValueError):
            validate_safety(self.workflow.replace(
                "on:\n  workflow_dispatch:", "on:\n  push:", 1))

    def test_missing_actions_metadata_scope_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_safety(self.workflow.replace("      actions: read\n", "", 1))

    def test_missing_pr_metadata_scope_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_safety(self.workflow.replace("      pull-requests: read\n", "", 1))

    def test_untrusted_builder_cannot_receive_secret(self):
        with self.assertRaises(ValueError):
            validate_safety(self.workflow.replace(
                "  build:\n", "  build:\n    env:\n      LEAK: ${{ secrets.BAD }}\n", 1))

    def test_protected_signer_cannot_checkout_source(self):
        with self.assertRaises(ValueError):
            validate_safety(self.workflow.replace(
                "  sign:\n", "  sign:\n    steps:\n      - uses: actions/checkout@v4\n", 1))

    def test_wrong_firebase_bucket_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_safety(self.workflow.replace(
                "petworld-dev.firebasestorage.app",
                "petworld-prod-3b5c5.firebasestorage.app"))

    def test_untested_main_application_changes_are_rejected(self):
        with self.assertRaises(ValueError):
            validate_safety(self.workflow.replace("allowed_main_only", "allowed_everything"))

    def test_missing_account_matrix_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_safety(self.workflow.replace("for api in (29, 35, 36)", "for api in (35,)"))

    def test_missing_main_ci_gate_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_safety(self.workflow.replace("Latest main CI not successful", "Ignoring main CI"))

    def test_unreviewed_source_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_safety(self.workflow.replace(
                "repos/' + repo + '/pulls/55", "repos/' + repo + '/pulls/1", 1))


if __name__ == "__main__":
    unittest.main(verbosity=2)
