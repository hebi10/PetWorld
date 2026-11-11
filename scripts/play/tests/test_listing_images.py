"""Offline tests for the real Play assets and mocked edits API; no credentials."""
import copy
import hashlib
import json
import shutil
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import listing_images as listing

SOURCE = Path(__file__).resolve().parents[3] / 'assets/play-store/ko-KR'


def fixture_png(width, height, color, pixel=None):
    def chunk(kind, body):
        return (struct.pack('>I', len(body)) + kind + body +
                struct.pack('>I', zlib.crc32(kind + body) & 0xFFFFFFFF))
    if pixel is None:
        pixel = b'\x0b\x12\x30\xff' if color == 6 else b'\x0b\x12\x30'
    raw = (b'\x00' + pixel * width) * height
    header = struct.pack('>IIBBBBB', width, height, 8, color, 0, 0, 0)
    return listing.PNG_SIGNATURE + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')


class FakePlay:
    def __init__(self, fail_readback=False):
        self.calls = []
        self.edits = {}
        self.live = {typ: [] for typ, _ in listing.UPLOAD_GROUPS}
        self.nedits = 0
        self.committed = False
        self.fail_readback = fail_readback

    def request(self, method, path, body=None, upload=False):
        self.calls.append((method, path, upload))
        if (method, path) == ('POST', '/edits'):
            self.nedits += 1
            eid = f'edit-{self.nedits}'
            self.edits[eid] = copy.deepcopy(self.live)
            return {'id': eid}
        if method == 'POST' and path.endswith(':commit'):
            eid = path.split('/')[2].split(':')[0]
            self.live = copy.deepcopy(self.edits.pop(eid))
            self.committed = True
            return {'id': eid}
        elements = path.split('/')
        eid = elements[2]
        if method == 'DELETE' and len(elements) == 3:
            self.edits.pop(eid, None)
            return {}
        assert elements[3] == 'listings' and elements[4] == listing.LOCALE
        typ = elements[5]
        assert typ in self.live
        if method == 'DELETE':
            self.edits[eid][typ] = []
            return {}
        if method == 'POST' and upload:
            obj = {'id': f'{typ}-{len(self.edits[eid][typ])}',
                   'sha256': hashlib.sha256(body).hexdigest()}
            self.edits[eid][typ].append(obj)
            return {'image': obj}
        if method == 'GET':
            images = copy.deepcopy(self.edits[eid][typ])
            return {'images': list(reversed(images)) if self.fail_readback and self.committed else images}
        raise AssertionError('Unexpected API request')


class ListingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name) / 'ko-KR'
        shutil.copytree(SOURCE, self.folder)

    def replace_png(self, manifest, filename, raw):
        (self.folder / filename).write_bytes(raw)
        path = self.folder / manifest
        obj = json.loads(path.read_text(encoding='utf-8'))
        index_key = 'path' if manifest == 'manifest.json' else 'file'
        for item in obj['files']:
            if item[index_key] == filename:
                item['bytes'] = len(raw)
                item['sha256' if manifest == 'manifest.json' else 'image_sha256'] = hashlib.sha256(raw).hexdigest()
        path.write_text(json.dumps(obj, ensure_ascii=False), encoding='utf-8')

    def test_real_assets_pass(self):
        assets = listing.validate(self.folder)
        self.assertEqual([x['name'] for x in assets], [listing.ICON, listing.FEATURE, *listing.SCREENSHOTS])

    def test_tampering_rejected(self):
        p = self.folder / listing.SCREENSHOTS[0]
        p.write_bytes(p.read_bytes()+b'error')
        with self.assertRaisesRegex(listing.ListingError, 'SHA-256'): listing.validate(self.folder)

    def test_order_change_rejected(self):
        p = self.folder/'screenshots-manifest.json'
        data = json.loads(p.read_text());data['files'].reverse()
        p.write_text(json.dumps(data), encoding='utf-8')
        with self.assertRaisesRegex(listing.ListingError, 'ordering'): listing.validate(self.folder)

    def test_wrong_dimensions_rejected(self):
        self.replace_png('manifest.json', listing.FEATURE, fixture_png(1000,500,2))
        with self.assertRaisesRegex(listing.ListingError, 'dimensions'): listing.validate(self.folder)

    def test_transparent_icon_rejected(self):
        self.replace_png('manifest.json', listing.ICON, fixture_png(512,512,6, b'\x01\x02\x03\x00'))
        with self.assertRaisesRegex(listing.ListingError, 'opaque'): listing.validate(self.folder)

    def test_alpha_screenshot_rejected(self):
        self.replace_png('screenshots-manifest.json', listing.SCREENSHOTS[2], fixture_png(1350,2400,6))
        with self.assertRaisesRegex(listing.ListingError, 'unsupported bit depth'): listing.validate(self.folder)

    def test_crc_damage_rejected_even_if_manifest_matches(self):
        raw = bytearray(fixture_png(1024,500,2));raw[40] ^= 1
        self.replace_png('manifest.json', listing.FEATURE, bytes(raw))
        with self.assertRaisesRegex(listing.ListingError, 'CRC'): listing.validate(self.folder)

    def test_missing_image_rejected(self):
        (self.folder/listing.SCREENSHOTS[-1]).unlink()
        with self.assertRaisesRegex(listing.ListingError, 'Missing required'): listing.validate(self.folder)

    def test_extra_png_rejected(self):
        (self.folder/'09-unlisted.png').write_bytes(b'junk')
        with self.assertRaisesRegex(listing.ListingError, 'Unlisted PNG'): listing.validate(self.folder)

    def test_wrong_package_rejected(self):
        p=self.folder/'screenshots-manifest.json';obj=json.loads(p.read_text())
        obj['package']='com.other.app';p.write_text(json.dumps(obj),encoding='utf-8')
        with self.assertRaisesRegex(listing.ListingError, 'different app'): listing.validate(self.folder)

    def test_offline_check_without_api_access(self):
        result=listing.main(['--directory',str(self.folder)],client_factory=lambda _: self.fail('Network call'))
        self.assertEqual(result['validated'],10)
        self.assertFalse(result['api_committed'])

    def test_strict_manual_main_approval(self):
        env={'GITHUB_REPOSITORY':listing.REPO,'GITHUB_EVENT_NAME':'workflow_dispatch',
             'GITHUB_REF':'refs/heads/main','PLAY_LISTING_APPLY':'true','PLAY_ACCESS_TOKEN':'fake'}
        listing.assert_upload_authorization(env,listing.APPROVAL)
        for k,bad in [('GITHUB_REPOSITORY','other/repo'),('GITHUB_EVENT_NAME','pull_request'),
                      ('GITHUB_REF','refs/heads/feature'),('PLAY_LISTING_APPLY','false')]:
            with self.assertRaises(listing.ListingError):
                listing.assert_upload_authorization({**env,k:bad},listing.APPROVAL)
        with self.assertRaises(listing.ListingError): listing.assert_upload_authorization(env,'wrong')

    def test_mocked_commit_and_fresh_readback(self):
        fake=FakePlay();result=listing.upload(self.folder,listing.validate(self.folder),fake)
        self.assertEqual(result['image_count'],10)
        self.assertTrue(fake.committed)
        self.assertTrue(result['fresh_readback'])
        self.assertEqual(fake.nedits,2)
        self.assertEqual(fake.edits,{})
        self.assertFalse(any('/tracks/' in path or '/bundles' in path or '/apks' in path
                             for _,path,_ in fake.calls))

    def test_mocked_readback_mismatch_rejected(self):
        fake=FakePlay(fail_readback=True)
        with self.assertRaisesRegex(listing.ListingError,'fresh'):
            listing.upload(self.folder,listing.validate(self.folder),fake)
        self.assertTrue(fake.committed)

    def test_transport_denies_other_endpoints(self):
        api=listing.PlayClient('fake',opener=lambda *x,**kw:self.fail('Network called'))
        for url in ['/edits/1/tracks/alpha','/edits/1/listings/en-US/phoneScreenshots']:
            with self.assertRaisesRegex(listing.ListingError,'scope'):
                api.request('POST',url,{},upload=False)

if __name__ == '__main__': unittest.main()
