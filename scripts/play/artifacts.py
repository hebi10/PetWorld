#!/usr/bin/env python3
"""Verify the actual release AAB and APK package, version name and allocated code."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

PACKAGE = 'com.haebi.petworld'
VERSION_NAME = '0.1.0'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class IdentityError(RuntimeError):
    pass


def verify(bundle, apk, bundletool, aapt2, code, run=subprocess.run):
    if isinstance(code, bool) or not re.fullmatch(r'[0-9]+', str(code)) or not 1 <= int(code) <= 2100000000:
        raise IdentityError('Invalid allocated release versionCode')
    code = int(code)
    try:
        manifest = run(['java', '-jar', str(bundletool), 'dump', 'manifest', '--bundle=' + str(bundle), '--module=base'],
                       capture_output=True, check=False, text=True)
        if manifest.returncode:
            raise IdentityError('Unable to inspect release AAB manifest')
        root = ET.fromstring(manifest.stdout)
        aab = (root.get('package'), root.get(ANDROID + 'versionName'), root.get(ANDROID + 'versionCode'))
        badging = run([str(aapt2), 'dump', 'badging', str(apk)], capture_output=True, check=False, text=True)
        if badging.returncode:
            raise IdentityError('Unable to inspect release APK manifest')
        line = next((line for line in badging.stdout.splitlines() if line.startswith('package: ')), '')
        fields = dict(re.findall(r"([A-Za-z]+)='([^']*)'", line))
        apk_identity = (fields.get('name'), fields.get('versionName'), fields.get('versionCode'))
        expected = (PACKAGE, VERSION_NAME, str(code))
        if aab != expected or apk_identity != expected:
            raise IdentityError('AAB and APK must match the exact app package, version name and allocated versionCode')
    except (OSError, ValueError, ET.ParseError):
        raise IdentityError('Release artifact identity inspection failed') from None
    return {'package': PACKAGE, 'versionName': VERSION_NAME, 'versionCode': code, 'status': 'artifact_identity_verified'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--bundletool', type=Path, required=True)
    parser.add_argument('--aapt2', type=Path, required=True)
    parser.add_argument('--version-code', required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.bundle, args.apk, args.bundletool, args.aapt2, args.version_code)))
        return 0
    except IdentityError as error:
        print('Release identity verification failed: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
