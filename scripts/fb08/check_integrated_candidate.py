#!/usr/bin/env python3
"""Read-only FB-08 source and activation gate. Never builds, signs or deploys."""
import argparse
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SHA = re.compile(r"[0-9a-f]{40}")
MANIFEST = ROOT / "docs/qa/fb08-integrated-source-manifest.json"


def require(condition, message):
    if not condition:
        raise SystemExit("FB-08 DENIED: " + message)


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)


def ancestor(parent, head):
    require(isinstance(parent, str) and SHA.fullmatch(parent), "invalid recorded source")
    require(git("merge-base", "--is-ancestor", parent, head).returncode == 0,
            "recorded source is not in candidate ancestry: " + parent)


class DisabledReview(HTMLParser):
    def __init__(self):
        super().__init__()
        self.controls = {}
        self.scripts = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "script":
            self.scripts += 1
        if tag in ("button", "input"):
            self.controls[attrs.get("id")] = "disabled" in attrs


def verify(head, base):
    require(SHA.fullmatch(head) and SHA.fullmatch(base), "exact head and base SHA required")
    require(git("rev-parse", "HEAD").stdout.strip() == head, "checkout differs from PR head")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    require(manifest["schemaVersion"] == 1 and manifest["repository"] == "hebi10/PetWorld",
            "unexpected manifest")
    require(manifest["baselineMain"] == base, "main changed; reconcile candidate and update evidence")
    ancestor(base, head)
    ancestor(manifest["integratedTreeCommit"], head)
    ancestor(manifest["featureTip"], head)
    sources = manifest["sourcePrs"]
    require([row["number"] for row in sources] == [n for n in range(55,86) if n not in (67,70)],
            "incomplete feature PR manifest")
    require(sources[-1]["head"] == manifest["featureTip"], "feature tip mismatch")
    for row in [*sources, *manifest["earlierReviewedSources"]]:
        ancestor(row["head"], head)
    for path in manifest["protectedMainPaths"]:
        require(git("diff", "--exit-code", base, head, "--", path).returncode == 0,
                "protected main policy changed: " + path)
    require(all(value is False for value in manifest["liveGates"].values()),
            "offline QA must not assert live readiness")
    firebase = json.loads((ROOT / "firebase.json").read_text(encoding="utf-8"))
    require(set(firebase) == {"firestore", "storage", "emulators"},
            "unexpected Firebase deployment or hosting configuration")
    require(firebase["emulators"]["firestore"]["host"] == "127.0.0.1" and
            firebase["emulators"]["storage"]["host"] == "127.0.0.1", "nonlocal emulator config")
    policy = (ROOT / "app/src/firebaseDev/java/com/haebi/petworld/auth/FirebaseDevDeletionCachePolicy.kt").read_text(encoding="utf-8")
    require("fun disposition(): SharedSdkCacheDisposition = SharedSdkCacheDisposition.UNVERIFIED_SHARED" in policy and
            "fun permitsRequest(): Boolean = disposition() != SharedSdkCacheDisposition.UNVERIFIED_SHARED" in policy,
            "actual account deletion cache gate no longer demonstrably disabled")
    review = DisabledReview()
    review.feed((ROOT / "firebase/web/account-deletion-preview.html").read_text(encoding="utf-8"))
    required = {"connect-existing", "prepare-recovery", "saved-recovery", "confirm-deletion",
                "submit-deletion", "recovery-file", "restore-password", "restore-recovery", "refresh-status"}
    require(review.scripts == 0 and required <= review.controls.keys() and
            all(review.controls.values()), "default web review is not inert")
    subprocess.run(["node", "scripts/firebase/check-connection-readiness.mjs"], cwd=ROOT, check=True)
    subprocess.run(["python3", "scripts/fb04/qa_workflow.py"], cwd=ROOT, check=True)
    print("PASS: exact head, main ancestry, 29 stacked source PRs, earlier source ancestry, protected policies and offline activation guards.")
    print("NOT VERIFIED: live Firebase, signed QA APK, external deletion URL, real device credentials, final main QA, deployment approval.")
    print("This check is not a release or merge authorization.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--head", required=True)
    parser.add_argument("--base", required=True)
    args = parser.parse_args()
    verify(args.head, args.base)
