#!/usr/bin/env python3
"""Validate PetWorld's ten Korean Play assets; submit only explicit listing-image edits.

Standard library only. Default mode performs offline checks and never authenticates.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
import urllib.error
import urllib.request
import zlib

PACKAGE = 'com.haebi.petworld'
LOCALE = 'ko-KR'
REPO = 'hebi10/PetWorld'
BASE = f'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}'
UPLOAD_BASE = f'https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}'
ASSET_DIR = Path(__file__).resolve().parents[2] / 'assets/play-store/ko-KR'
ICON = 'play-icon.png'
FEATURE = 'feature-graphic.png'
SCREENSHOTS = (
    '01-home.png', '02-quick-record.png', '03-food.png', '04-elimination.png',
    '05-records.png', '06-health.png', '07-statistics.png', '08-backup.png',
)
SOURCES = (
    'S03-home.png', 'S05-quick-record-sheet.png', 'S06-food-form.png',
    'S08-elimination-form.png', 'S11-record-list.png', 'S18-health.png',
    'S14-statistics.png', 'S31-backup-password-dialog.png',
)
UPLOAD_GROUPS = (('icon', (ICON,)), ('featureGraphic', (FEATURE,)),
                 ('phoneScreenshots', SCREENSHOTS))
APPROVAL = 'PETWORLD-KO-LISTING-10'
PNG_SIGNATURE = b'\x89PNG\r\n\x1a\n'
MAX_SCREEN_BYTES = 8 * 1024 * 1024
MAX_FEATURE_BYTES = 15 * 1024 * 1024
MAX_ICON_BYTES = 1024 * 1024


class ListingError(RuntimeError):
    """A listing validation or upload safety gate failed."""


def require(condition, reason):
    if not condition:
        raise ListingError(reason)


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def check_png(raw, filename, expected_size, color_type, max_bytes):
    """Strict PNG structure, CRC, zlib and RGBA opacity checks without Pillow."""
    require(64 <= len(raw) <= max_bytes, f'{filename}: PNG file size is invalid')
    require(raw.startswith(PNG_SIGNATURE), f'{filename}: PNG signature missing')
    offset, seen_header, seen_end, idat = len(PNG_SIGNATURE), False, False, []
    width = height = depth = mode = 0
    while offset < len(raw):
        require(offset + 12 <= len(raw), f'{filename}: truncated PNG chunk')
        length = struct.unpack('>I', raw[offset:offset+4])[0]
        kind = raw[offset+4:offset+8]
        end = offset + 12 + length
        require(end <= len(raw), f'{filename}: invalid PNG chunk length')
        chunk = raw[offset+8:offset+8+length]
        expected_crc = struct.unpack('>I', raw[end-4:end])[0]
        require(zlib.crc32(kind + chunk) & 0xFFFFFFFF == expected_crc,
                f'{filename}: PNG chunk CRC mismatch')
        if not seen_header:
            require(kind == b'IHDR' and length == 13,
                    f'{filename}: IHDR must be first')
            width, height, depth, mode, compression, filtering, interlace = struct.unpack('>IIBBBBB', chunk)
            require((width, height) == expected_size, f'{filename}: wrong image dimensions')
            require(depth == 8 and mode == color_type and
                    (compression, filtering, interlace) == (0, 0, 0),
                    f'{filename}: unsupported bit depth, color or interlacing')
            seen_header = True
        elif kind == b'IHDR':
            raise ListingError(f'{filename}: duplicate IHDR')
        elif kind == b'IDAT':
            idat.append(chunk)
        elif kind == b'IEND':
            require(length == 0, f'{filename}: invalid IEND')
            require(end == len(raw), f'{filename}: trailing bytes after IEND')
            seen_end = True
            offset = end
            break
        offset = end
    require(seen_header and seen_end and idat, f'{filename}: incomplete PNG')
    bpp = 4 if color_type == 6 else 3
    stride = width * bpp
    try:
        decompressor = zlib.decompressobj()
        maximum = height * (1 + stride)
        decoded = decompressor.decompress(b''.join(idat), maximum + 1)
        require(len(decoded) == maximum and decompressor.eof and
                not decompressor.unconsumed_tail and not decompressor.unused_data,
                f'{filename}: incorrect PNG pixel data')
    except zlib.error as exc:
        raise ListingError(f'{filename}: corrupted PNG pixel data') from exc
    if color_type == 6:
        # Decode PNG scanline filters to verify *every* icon alpha byte is 255.
        previous = bytearray(stride)
        for row in range(height):
            index = row * (stride + 1)
            filter_type = decoded[index]
            require(filter_type <= 4, f'{filename}: unsupported PNG row filter')
            current = bytearray(decoded[index+1:index+1+stride])
            for position in range(stride):
                left = current[position-bpp] if position >= bpp else 0
                up = previous[position]
                upper_left = previous[position-bpp] if position >= bpp else 0
                if filter_type == 1:
                    predictor = left
                elif filter_type == 2:
                    predictor = up
                elif filter_type == 3:
                    predictor = (left + up) // 2
                elif filter_type == 4:
                    p = left + up - upper_left
                    a, b, c = abs(p-left), abs(p-up), abs(p-upper_left)
                    predictor = left if a <= b and a <= c else up if b <= c else upper_left
                else:
                    predictor = 0
                current[position] = (current[position] + predictor) & 0xFF
            require(all(a == 255 for a in current[3::4]),
                    f'{filename}: icon must be fully opaque')
            previous = current
    else:
        require(all(decoded[row * (stride + 1)] <= 4 for row in range(height)),
                f'{filename}: invalid PNG scanline filter')
    return {'width': width, 'height': height, 'mode': 'RGBA' if mode == 6 else 'RGB'}


def validate(directory=ASSET_DIR):
    directory = Path(directory)
    icon_manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    screen_manifest = json.loads((directory / 'screenshots-manifest.json').read_text(encoding='utf-8'))
    require(icon_manifest.get('package') == PACKAGE and icon_manifest.get('locale') == LOCALE,
            'STORE-01 manifest points to a different app/locale')
    require(screen_manifest.get('package') == PACKAGE and screen_manifest.get('locale') == LOCALE,
            'STORE-02 manifest points to a different app/locale')
    source = screen_manifest.get('source', {})
    require(source.get('repo') == REPO and source.get('workflow_run') == 42780690910 and
            source.get('commit') == 'e3871fe00c9f986515daf97a7345d490d185aa34',
            'Unapproved Android capture provenance')
    approved = {ICON, FEATURE, *SCREENSHOTS, 'play-icon-dark-preview.png'}
    unexpected = sorted(p.name for p in directory.glob('*.png') if p.name not in approved)
    require(not unexpected, f'Unlisted PNG files in Play directory: {unexpected}')

    graphic_records = icon_manifest.get('files', [])
    graphic_by_name = {entry.get('path'): entry for entry in graphic_records}
    require(len(graphic_by_name) == 3 and
            set(graphic_by_name) == {ICON, FEATURE, 'play-icon-dark-preview.png'},
            'STORE-01 manifest file set is incomplete')
    screenshots = screen_manifest.get('files', [])
    require(len(screenshots) == len(SCREENSHOTS), 'Exactly eight screenshots are required')
    require([entry.get('file') for entry in screenshots] == list(SCREENSHOTS),
            'Screenshot filenames or ordering differ from approved set')
    require([entry.get('order') for entry in screenshots] == list(range(1, 9)),
            'Screenshot ordinals are incorrect')
    require([Path(entry.get('original_capture','')).name for entry in screenshots] == list(SOURCES),
            'Screenshot source screen mapping is incorrect')
    require(all(str(entry.get('original_capture', '')).startswith('screenshots/light/')
                for entry in screenshots), 'Only real light-theme Android captures are approved')

    validated, seen_hashes = [], set()
    all_entries = [
        (ICON, graphic_by_name[ICON], (512, 512), 6, MAX_ICON_BYTES),
        (FEATURE, graphic_by_name[FEATURE], (1024, 500), 2, MAX_FEATURE_BYTES),
        *((name, entry, (1350, 2400), 2, MAX_SCREEN_BYTES)
          for name, entry in zip(SCREENSHOTS, screenshots)),
    ]
    for name, entry, size, color, maximum in all_entries:
        path = directory / name
        require(path.is_file(), f'Missing required Play image: {name}')
        raw = path.read_bytes()
        digest = sha256(raw)
        require(re.fullmatch('[a-f0-9]{64}', digest) is not None, 'Invalid SHA-256 result')
        require(digest == entry.get('sha256', entry.get('image_sha256')),
                f'SHA-256 mismatch: {name}')
        require(entry.get('bytes') == len(raw), f'Byte count mismatch: {name}')
        png = check_png(raw, name, size, color, maximum)
        require(entry.get('width') == png['width'] and entry.get('height') == png['height'] and
                entry.get('mode') == png['mode'], f'Manifest PNG metadata mismatch: {name}')
        require(digest not in seen_hashes, f'Duplicate Play images: {name}')
        seen_hashes.add(digest)
        validated.append({'name': name, 'sha256': digest, 'bytes': len(raw),
                          'type': 'icon' if name == ICON else 'featureGraphic' if name == FEATURE else 'phoneScreenshots'})
    return validated


def assert_upload_authorization(env, confirmation):
    require(env.get('GITHUB_REPOSITORY') == REPO, 'Listing upload restricted to PetWorld')
    require(env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and
            env.get('GITHUB_REF') == 'refs/heads/main',
            'Listing upload requires a manual action on main')
    require(confirmation == APPROVAL and env.get('PLAY_LISTING_APPLY') == 'true',
            'Explicit listing-only approval is missing')
    require(bool(env.get('PLAY_ACCESS_TOKEN')), 'PLAY_ACCESS_TOKEN is required')


class PlayClient:
    def __init__(self, token, opener=urllib.request.urlopen):
        require(bool(token), 'Missing Google Play authorization')
        self.token = token
        self.opener = opener

    def request(self, method, path, body=None, upload=False):
        # Only edits and exactly three ko-KR image slots can be mutated.
        safe_edit = r'/edits/[A-Za-z0-9._-]+'
        safe_images = safe_edit + r'/listings/ko-KR/(icon|featureGraphic|phoneScreenshots)'
        permitted = ((method == 'POST' and path == '/edits') or
                     (method == 'POST' and re.fullmatch(safe_edit + r':commit', path)) or
                     (method == 'DELETE' and re.fullmatch(safe_edit, path)) or
                     (not upload and method in ('GET', 'DELETE') and re.fullmatch(safe_images, path)) or
                     (upload and method == 'POST' and re.fullmatch(safe_images, path)))
        require(permitted, 'Refused Play API operation outside approved listing-image scope')
        require(not upload or isinstance(body, bytes), 'Image upload payload must be bytes')
        prefix = UPLOAD_BASE if upload else BASE
        url = prefix + path + ('?uploadType=media' if upload else '')
        payload = body if isinstance(body, bytes) else json.dumps(body).encode('utf-8') if body is not None else None
        headers = {'Authorization': 'Bearer ' + self.token, 'Accept': 'application/json'}
        if payload is not None:
            headers['Content-Type'] = 'image/png' if upload else 'application/json'
        req = urllib.request.Request(url, method=method, data=payload, headers=headers)
        try:
            with self.opener(req, timeout=90) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as exc:
            raise ListingError(f'Google Play listing-image request failed: {method} HTTP {exc.code}') from None
        except urllib.error.URLError as exc:
            raise ListingError(f'Google Play listing-image network error: {type(exc.reason).__name__}') from None


def exact_image_set(images, expected):
    if len(images) != len(expected):
        return False
    for actual, item in zip(images, expected):
        if not isinstance(actual, dict) or not actual.get('id') or actual.get('id') != item['id']:
            return False
        reported = actual.get('sha256')
        if not isinstance(reported, str) or reported.lower() != item['sha256'].lower():
            return False
    return True


def upload(directory, manifest, client):
    """Single image-only edit; never touches tracks, AAB, listing text, or testers."""
    editor_id, committed = None, False
    expected = {kind: [] for kind, _ in UPLOAD_GROUPS}
    by_name = {item['name']: item for item in manifest}
    try:
        start = client.request('POST', '/edits', {})
        editor_id = start.get('id')
        require(editor_id and re.fullmatch(r'[A-Za-z0-9._-]+', editor_id), 'Google Play edit ID invalid')
        for kind, names in UPLOAD_GROUPS:
            url = f'/edits/{editor_id}/listings/{LOCALE}/{kind}'
            client.request('DELETE', url)
            for filename in names:
                content = (Path(directory) / filename).read_bytes()
                response = client.request('POST', url, content, upload=True)
                image = response.get('image', {})
                require(isinstance(image, dict) and image.get('id'), f'Play returned no image ID: {filename}')
                require(image.get('sha256', '').lower() == by_name[filename]['sha256'],
                        f'Play upload digest does not match: {filename}')
                expected[kind].append({'id': image['id'], 'sha256': by_name[filename]['sha256']})
            check = client.request('GET', url)
            require(exact_image_set(check.get('images', []), expected[kind]),
                    f'Google Play staged {kind} order/hash mismatch')
        # Once commit is attempted, its status can be uncertain: never auto-retry it.
        result = client.request('POST', f'/edits/{editor_id}:commit', {})
        require(result.get('id'), 'Play commit did not return a confirmed edit ID; verify Console manually')
        committed = True
    finally:
        if editor_id and not committed:
            try:
                client.request('DELETE', f'/edits/{editor_id}')
            except ListingError:
                pass
    # New read-only edit verifies committed state, not actual review/publishing.
    verification = client.request('POST', '/edits', {})
    verify_id = verification.get('id')
    require(verify_id and re.fullmatch(r'[A-Za-z0-9._-]+', verify_id),
            'Commit accepted; fresh verification edit unavailable')
    try:
        for kind, _ in UPLOAD_GROUPS:
            path = f'/edits/{verify_id}/listings/{LOCALE}/{kind}'
            image_set = client.request('GET', path)
            require(exact_image_set(image_set.get('images', []), expected[kind]),
                    f'Commit accepted but fresh {kind} readback differs; inspect Play Console')
    finally:
        client.request('DELETE', f'/edits/{verify_id}')
    return {'api_committed': True, 'fresh_readback': True, 'scope': list(expected),
            'image_count': sum(len(x) for x in expected.values())}


def main(argv=None, env=None, client_factory=PlayClient):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ASSET_DIR)
    parser.add_argument('--mode', choices=('check', 'upload'), default='check')
    parser.add_argument('--confirm', default='')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args(argv)
    env = os.environ if env is None else env
    assets = validate(args.directory)
    report = {'package': PACKAGE, 'locale': LOCALE, 'mode': args.mode,
              'validated': len(assets), 'images': assets, 'api_committed': False,
              'fresh_readback': False}
    if args.mode == 'upload':
        assert_upload_authorization(env, args.confirm)
        result = upload(args.directory, assets, client_factory(env['PLAY_ACCESS_TOKEN']))
        report.update(result)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'PetWorld {LOCALE}: validated 1 icon, 1 feature graphic, {len(SCREENSHOTS)} phone screenshots; mode={args.mode}')
    if args.mode == 'upload':
        print('Play edit committed and independently verified. Console review/public visibility remain unverified.')
    else:
        print('Offline check complete: no Play API calls and no upload.')
    return report


if __name__ == '__main__':
    try:
        main()
    except (ListingError, FileNotFoundError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f'STORE-03 stopped: {error}', file=sys.stderr)
        sys.exit(1)
