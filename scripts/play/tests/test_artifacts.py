from pathlib import Path
import subprocess
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
import artifacts


class ArtifactIdentityTests(unittest.TestCase):
    def runner(self, package='com.haebi.petworld', aab_code='1', apk_code='1', version='0.1.0', fail=False):
        def run(command, **kwargs):
            if command[0] == 'java':
                text = f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="{aab_code}" android:versionName="{version}" />'
            else:
                text = f"package: name='{package}' versionCode='{apk_code}' versionName='{version}' compileSdkVersion='37'"
            return subprocess.CompletedProcess(command, 1 if fail else 0, text, '')
        return run

    def test_real_tool_identity_matches_expected_package_version_and_allocated_code(self):
        result = artifacts.verify('app.aab', 'app.apk', 'bundletool.jar', 'aapt2', '1', self.runner())
        self.assertEqual(result, {'package': 'com.haebi.petworld', 'versionName': '0.1.0', 'versionCode': 1, 'status': 'artifact_identity_verified'})

    def test_wrong_package_stale_aab_apk_or_version_blocks_release(self):
        for options in ({'package': 'other.app'}, {'aab_code': '2'}, {'apk_code': '2'}, {'version': 'old'}):
            with self.subTest(options=options), self.assertRaises(artifacts.IdentityError):
                artifacts.verify('app.aab', 'app.apk', 'bundletool.jar', 'aapt2', '1', self.runner(**options))

    def test_tool_failure_invalid_manifest_or_badging_blocks_release(self):
        for run in (self.runner(fail=True), lambda cmd, **kwargs: subprocess.CompletedProcess(cmd, 0, 'invalid', '')):
            with self.assertRaises(artifacts.IdentityError):
                artifacts.verify('app.aab', 'app.apk', 'bundletool.jar', 'aapt2', '1', run)

    def test_invalid_version_code_never_calls_tools(self):
        def forbidden(*args, **kwargs):
            self.fail('tools must not run for invalid code')
        for code in ('0', '-1', '1x', True, '2100000001'):
            with self.subTest(code=code), self.assertRaises(artifacts.IdentityError):
                artifacts.verify('app.aab', 'app.apk', 'bundletool.jar', 'aapt2', code, forbidden)
