import unittest

from firebase.dev_signing_report import (
    apksigner_cert,
    colonized,
    fingerprint,
    gradle_variant,
    keytool_cert,
)

SHA1 = "0123456789abcdef0123456789abcdef01234567"
SHA256 = "0123456789abcdef" * 4


class FirebaseDevSigningReportTest(unittest.TestCase):
    def test_gradle_variant_selects_firebase_dev_only(self):
        text = (
            "Variant: debug\nConfig: debug\nSHA1: " + ("f" * 40) +
            "\nSHA-256: " + ("f" * 64) +
            "\n----------\nVariant: firebaseDev\nConfig: firebaseDevFixed\n"
            "SHA1: " + colonized(SHA1) +
            "\nSHA-256: " + colonized(SHA256) + "\n----------\n"
        )
        config, sha = gradle_variant(text, "firebaseDev")
        self.assertEqual(config, "firebaseDevFixed")
        self.assertEqual(sha, {"sha1": SHA1.upper(), "sha256": SHA256.upper()})

    def test_parser_rejects_missing_variant(self):
        with self.assertRaisesRegex(ValueError, "no firebaseDev variant"):
            gradle_variant("Variant: debug\nConfig: debug\n", "firebaseDev")

    def test_parsers_agree_on_public_fingerprints(self):
        keytool = (
            "Certificate fingerprints:\n"
            " SHA1: " + colonized(SHA1) +
            "\n SHA256: " + colonized(SHA256) + "\n"
        )
        apksigner = (
            "Signer #1 certificate SHA-1 digest: " + SHA1 +
            "\nSigner #1 certificate SHA-256 digest: " + SHA256 + "\n"
        )
        self.assertEqual(keytool_cert(keytool), apksigner_cert(apksigner))

    def test_rejects_invalid_digest(self):
        with self.assertRaisesRegex(ValueError, "Invalid SHA"):
            fingerprint("AB:CD", 40)


if __name__ == "__main__":
    unittest.main()
