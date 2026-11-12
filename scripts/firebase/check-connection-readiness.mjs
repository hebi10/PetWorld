import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));

function source(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

const config = JSON.parse(source('firebase.json'));
const gradle = source('app/build.gradle.kts');
const ignore = source('.gitignore');

assert.equal(config.firestore?.rules, 'firebase/firestore.rules');
assert.equal(config.storage?.rules, 'firebase/storage.rules');
assert.equal(config.emulators?.firestore?.host, '127.0.0.1');
assert.equal(config.emulators?.storage?.host, '127.0.0.1');
assert.equal(config.projects, undefined, 'Do not bind a live project in firebase.json.');
assert.match(gradle, /applicationId\s*=\s*"com\.haebi\.petworld"/);
assert.match(ignore, /^google-services\.json\s*$/m);
assert.match(ignore, /^\.firebaserc\s*$/m);
assert.match(source('firebase/test/rules.test.mjs'), /demo-petworld-fb01/);
assert.match(source('firebase/firestore.rules'), /request\.auth\.uid == uid/);
assert.match(source('firebase/storage.rules'), /request\.auth\.uid == uid/);

const tracked = execFileSync('git', ['ls-files', '--cached'], {
  cwd: root,
  encoding: 'utf8',
}).split(/\r?\n/).filter(Boolean);

const forbidden = tracked.filter((path) => (
  path === '.firebaserc' ||
  /(^|\/)google-services\.json$/.test(path) ||
  /(^|\/)\.env(\.|$)/.test(path) ||
  /(^|\/)(service-account[^/]*\.json|[^/]*\.jks|[^/]*\.keystore)$/.test(path)
));
assert.deepEqual(forbidden, [], 'Private Firebase configuration must not be tracked.');

console.log('PASS: repository package, emulator paths, rules files and ignored configuration');
console.log('NOT CHECKED: live Firebase projects, Android app registrations, SHA fingerprints, billing and deployed rules');
console.log('NO LIVE DEPLOY: no authenticated Firebase or Google Play operations performed');
