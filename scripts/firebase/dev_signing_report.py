"""Verify that a Firebase development APK uses one persistent certificate.

Only validated public SHA fingerprints are written to the GitHub job summary.
Private keystore bytes and passwords must never be printed or uploaded.
"""

import argparse
import os
import re
from pathlib import Path


def fingerprint(value: str, size: int) -> str:
    compact = value.replace(":", "").strip().upper()
    if not re.fullmatch(rf"[0-9A-F]{{{size}}}", compact):
        raise ValueError(f"Invalid SHA fingerprint (expected {size} hex characters)")
    return compact


def gradle_variant(text: str, variant: str) -> tuple[str, dict[str, str]]:
    block = re.search(
        rf"(?ms)^Variant:\s*{re.escape(variant)}\s*$\n(.*?)(?=^Variant:|\Z)",
        text,
    )
    if block is None:
        raise ValueError(f"Gradle signingReport has no {variant} variant")
    section = block.group(1)

    config = re.search(r"(?m)^Config:\s*(\S+)\s*$", section)
    sha1 = re.search(r"(?m)^SHA1:\s*([0-9a-fA-F:]+)\s*$", section)
    sha256 = re.search(r"(?m)^SHA-256:\s*([0-9a-fA-F:]+)\s*$", section)
    if not config or not sha1 or not sha256:
        raise ValueError(f"Signing report is missing certificate details for {variant}")

    return config.group(1), {
        "sha1": fingerprint(sha1.group(1), 40),
        "sha256": fingerprint(sha256.group(1), 64),
    }


def keytool_cert(text: str) -> dict[str, str]:
    sha1 = re.search(r"(?m)^\s*SHA1:\s*([0-9a-fA-F:]+)\s*$", text)
    sha256 = re.search(r"(?m)^\s*SHA256:\s*([0-9a-fA-F:]+)\s*$", text)
    if not sha1 or not sha256:
        raise ValueError("keytool did not report SHA1 and SHA256")
    return {
        "sha1": fingerprint(sha1.group(1), 40),
        "sha256": fingerprint(sha256.group(1), 64),
    }


def apksigner_cert(text: str) -> dict[str, str]:
    sha1 = re.search(r"(?mi)^Signer #1 certificate SHA-1 digest:\s*([0-9a-f:]+)\s*$", text)
    sha256 = re.search(r"(?mi)^Signer #1 certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$", text)
    if not sha1 or not sha256:
        raise ValueError("apksigner did not report SHA-1 and SHA-256")
    return {
        "sha1": fingerprint(sha1.group(1), 40),
        "sha256": fingerprint(sha256.group(1), 64),
    }


def colonized(value: str) -> str:
    return ":".join(value[i:i + 2] for i in range(0, len(value), 2))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--mode", choices=["ephemeral", "stable"], required=True)
    parser.add_argument("--keytool", type=Path)
    parser.add_argument("--apksigner", type=Path)
    args = parser.parse_args()

    config, gradle_cert = gradle_variant(args.report.read_text(encoding="utf-8"), "firebaseDev")
    if args.mode == "ephemeral":
        if config == "firebaseDevFixed":
            raise ValueError("A supposedly ephemeral test used a fixed development signer")
        summary = (
            "### PetWorld firebaseDev signingReport\n"
            "**EPHEMERAL RUNNER KEY — DO NOT REGISTER THIS SHA IN FIREBASE.**\n\n"
            "Gradle firebaseDev certificate report executed and parsed. "
            "A fixed development keystore was not supplied.\n"
        )
        print("PASS: signingReport parsed; transient runner SHA intentionally withheld")
    else:
        if config != "firebaseDevFixed":
            raise ValueError("firebaseDev build was not configured with the fixed keystore")
        if not args.keytool or not args.apksigner:
            raise ValueError("Stable mode requires keytool and signed APK evidence")
        key_cert = keytool_cert(args.keytool.read_text(encoding="utf-8"))
        apk_cert = apksigner_cert(args.apksigner.read_text(encoding="utf-8"))
        if gradle_cert != key_cert or key_cert != apk_cert:
            raise ValueError("Signing report, configured keystore and APK certificate differ")
        summary = (
            "### PetWorld fixed firebaseDev signing certificate\n"
            "**Verified: Gradle signingReport = keytool certificate = signed APK.**\n\n"
            f"- Firebase dev SHA-1: `{colonized(apk_cert['sha1'])}`\n"
            f"- Firebase dev SHA-256: `{colonized(apk_cert['sha256'])}`\n\n"
            "Register only for **petworld-dev / com.haebi.petworld.dev**, "
            "never for the Play production app.\n"
        )
        print("PASS: fixed firebaseDev signingReport, keytool and signed APK certificate match")

    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary)
    else:
        print(summary)


if __name__ == "__main__":
    main()
