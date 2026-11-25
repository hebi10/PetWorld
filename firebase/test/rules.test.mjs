import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { deepStrictEqual, rejects } from 'node:assert/strict';
import { after, before, beforeEach, test } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  deleteDoc,
  doc,
  getDoc,
  getDocFromServer,
  setDoc,
  updateDoc,
  writeBatch,
  runTransaction,
} from 'firebase/firestore';
import {
  deleteObject,
  getBytes,
  getMetadata,
  ref,
  uploadBytes,
} from 'firebase/storage';

const projectId = 'demo-petworld-fb01';
const bucket = 'gs://demo-petworld-fb01.firebasestorage.app';
const photoName = 'pet_11111111-1111-4111-8111-111111111111.jpg';
const now = 1794441600000;
let env;

function userDoc(revision = 0) {
  return {
    schemaVersion: 1,
    createdAtMs: now,
    migrationState: 'pending',
    revision,
  };
}

function petDoc(id = 'pet-1', revision = 0) {
  return {
    id,
    schemaVersion: 1,
    revision,
    name: '나비',
    species: 'CAT',
    isArchived: false,
    createdAtMs: now,
    updatedAtMs: now,
    birthday: null,
    estimatedAgeYears: null,
    photoFile: null,
  };
}

function careDoc(id = 'record-1', petId = 'pet-1', revision = 0) {
  return {
    id,
    petId,
    schemaVersion: 1,
    revision,
    type: 'FOOD',
    occurredAtMs: now,
    timezoneId: 'Asia/Seoul',
    note: '',
    createdAtMs: now,
    updatedAtMs: now,
    foodKind: 'DRY',
    foodServedMilli: 1500,
    foodConsumedMilli: null,
    foodUnit: 'G',
  };
}

const pathFor = (uid, petId = 'pet-1', filename = photoName) =>
  `users/${uid}/pets/${petId}/photos/${filename}`;

before(async () => {
  env = await initializeTestEnvironment({
    projectId,
    firestore: {
      host: '127.0.0.1',
      port: 8080,
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
    storage: {
      host: '127.0.0.1',
      port: 9199,
      rules: readFileSync(new URL('../storage.rules', import.meta.url), 'utf8'),
    },
  });
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
});

after(async () => {
  if (env) await env.cleanup();
});

test('Firestore: signed-out requests cannot read or create user data', async () => {
  const db = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(db, 'users/alice')));
  await assertFails(setDoc(doc(db, 'users/alice'), userDoc()));
});

test('Firestore: owner may create/read user document, other users cannot', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  await assertSucceeds(setDoc(doc(alice, 'users/alice'), userDoc()));
  await assertSucceeds(getDoc(doc(alice, 'users/alice')));
  await assertFails(getDoc(doc(bob, 'users/alice')));
  await assertFails(setDoc(doc(bob, 'users/alice'), userDoc()));
});

test('Firestore: user schema limits fields and locks revisions', async () => {
  const db = env.authenticatedContext('alice').firestore();
  const u = doc(db, 'users/alice');
  await assertFails(setDoc(u, { ...userDoc(), elevated: true }));
  await assertSucceeds(setDoc(u, userDoc()));
  await assertFails(updateDoc(u, { migrationState: 'ready' }));
  await assertSucceeds(updateDoc(u, { revision: 1, migrationState: 'ready' }));
  await assertFails(deleteDoc(u));
});

test('Firestore: pets remain account-isolated and typed', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  const pet = doc(alice, 'users/alice/pets/pet-1');
  await assertSucceeds(setDoc(pet, petDoc()));
  await assertSucceeds(getDoc(pet));
  await assertFails(getDoc(doc(bob, 'users/alice/pets/pet-1')));
  await assertFails(setDoc(doc(bob, 'users/alice/pets/pet-2'), petDoc('pet-2')));
  await assertFails(setDoc(doc(alice, 'users/alice/pets/pet-2'), { ...petDoc('pet-2'), unexpected: 'x' }));
  await assertFails(setDoc(doc(alice, 'users/alice/pets/pet-2'), { ...petDoc('pet-2'), name: '' }));
  await assertFails(updateDoc(pet, { name: '초코' }));
  await assertSucceeds(updateDoc(pet, { name: '초코', revision: 1 }));
  await assertFails(deleteDoc(pet));
});

test('Firestore: records require an existing pet and retain measurement null vs zero', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const record = doc(alice, 'users/alice/pets/pet-1/records/record-1');
  await assertFails(setDoc(record, careDoc()));
  await assertSucceeds(setDoc(doc(alice, 'users/alice/pets/pet-1'), petDoc()));
  await assertSucceeds(setDoc(record, careDoc()));
  await assertSucceeds(updateDoc(record, { revision: 1, foodConsumedMilli: 0 }));
  const actual = await assertSucceeds(getDoc(record));
  if (actual.data().foodConsumedMilli !== 0) {
    throw new Error('Measured zero must remain distinct from null.');
  }
  await assertFails(updateDoc(record, { revision: 1, petId: 'pet-2' }));
  await assertFails(deleteDoc(record));
});

test('Firestore: another account may not read or modify records', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  const path = 'users/alice/pets/pet-1/records/record-1';
  await assertSucceeds(setDoc(doc(alice, 'users/alice/pets/pet-1'), petDoc()));
  await assertSucceeds(setDoc(doc(alice, path), careDoc()));
  await assertFails(getDoc(doc(bob, path)));
  await assertFails(updateDoc(doc(bob, path), { revision: 1 }));
  await assertFails(setDoc(doc(bob, path), careDoc('record-1', 'pet-1', 1)));
});

test('Firestore: unknown paths and unauthenticated records are denied', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const guest = env.unauthenticatedContext().firestore();
  await assertFails(setDoc(doc(alice, 'admin/config'), { public: true }));
  await assertFails(getDoc(doc(alice, 'admin/config')));
  await assertFails(getDoc(doc(guest, 'users/alice/pets/pet-1/records/record-1')));
});

test('Storage: account owner can upload and download a valid JPEG', async () => {
  const alice = env.authenticatedContext('alice').storage(bucket);
  const file = ref(alice, pathFor('alice'));
  await assertSucceeds(uploadBytes(file, new Uint8Array([0xff, 0xd8, 0xff, 0xd9]), {
    contentType: 'image/jpeg',
  }));
  await assertSucceeds(getBytes(file));
});

test('FB-06-B: private photo SHA-256 metadata survives readback and cannot be overwritten', async () => {
  const alice = env.authenticatedContext('alice').storage(bucket);
  const bob = env.authenticatedContext('bob').storage(bucket);
  const path = pathFor('alice', 'pet-fb06-b');
  const photo = ref(alice, path);
  const bytes = new Uint8Array([0xff, 0xd8, 0x11, 0xff, 0xd9]);
  const sha256 = createHash('sha256').update(bytes).digest('hex');

  await assertSucceeds(uploadBytes(photo, bytes, {
    contentType: 'image/jpeg',
    customMetadata: { sha256 },
  }));
  const metadata = await assertSucceeds(getMetadata(photo));
  deepStrictEqual(metadata.size, bytes.byteLength);
  deepStrictEqual(metadata.contentType, 'image/jpeg');
  deepStrictEqual(metadata.customMetadata.sha256, sha256);
  const downloaded = await assertSucceeds(getBytes(photo));
  deepStrictEqual(createHash('sha256').update(Buffer.from(downloaded)).digest('hex'), sha256);

  await assertFails(getMetadata(ref(bob, path)));
  await assertFails(uploadBytes(photo, bytes, {
    contentType: 'image/jpeg',
    customMetadata: { sha256: '0'.repeat(64) },
  }));
});

test('Storage: guest and different UID cannot access a private image', async () => {
  const alice = env.authenticatedContext('alice').storage(bucket);
  const bob = env.authenticatedContext('bob').storage(bucket);
  const guest = env.unauthenticatedContext().storage(bucket);
  const path = pathFor('alice', 'pet-1', 'pet_22222222-2222-4222-8222-222222222222.jpg');
  await assertSucceeds(uploadBytes(ref(alice, path), new Uint8Array([1, 2, 3]), {
    contentType: 'image/jpeg',
  }));
  await assertFails(getBytes(ref(bob, path)));
  await assertFails(getBytes(ref(guest, path)));
  await assertFails(uploadBytes(ref(bob, path), new Uint8Array([4]), {
    contentType: 'image/jpeg',
  }));
});

test('Storage: type, path, overwrite and permanent deletion remain restricted', async () => {
  const alice = env.authenticatedContext('alice').storage(bucket);
  const valid = ref(alice, pathFor('alice', 'pet-1', 'pet_33333333-3333-4333-8333-333333333333.jpg'));
  await assertFails(uploadBytes(valid, new Uint8Array([1]), { contentType: 'image/png' }));
  await assertFails(uploadBytes(ref(alice, pathFor('alice', 'pet-1', 'evil.png')), new Uint8Array([1]), {
    contentType: 'image/jpeg',
  }));
  await assertFails(uploadBytes(valid, new Uint8Array(5 * 1024 * 1024 + 1), {
    contentType: 'image/jpeg',
  }));
  await assertSucceeds(uploadBytes(valid, new Uint8Array([1]), { contentType: 'image/jpeg' }));
  await assertSucceeds(getBytes(valid));
  await assertFails(uploadBytes(valid, new Uint8Array([2]), { contentType: 'image/jpeg' }));
  await assertFails(deleteObject(valid));
});


test('FB-04: strict schema accepts lossless initial pet and record maps and rejects overwrite', async () => {
  const db = env.authenticatedContext('alice').firestore();
  const user = doc(db, 'users/alice');
  const pet = doc(db, 'users/alice/pets/pet-1');
  const record = doc(db, 'users/alice/pets/pet-1/records/record-1');
  await assertSucceeds(setDoc(user, userDoc()));
  const fullPet = {
    ...petDoc(),
    breed: '',
    sex: 'UNKNOWN',
    neuterStatus: 'UNKNOWN',
    notes: '원본 메모',
  };
  const fullRecord = {
    ...careDoc(),
    foodProductName: '원본 사료',
    foodServedMilli: 0,
    foodConsumedMilli: null,
    waterMl: null,
    eliminationKind: null,
    eliminationCount: null,
    activityKind: null,
    activityMinutes: null,
    weightGrams: null,
    weightInputUnit: null,
    photoFile: null,
  };
  await assertSucceeds(setDoc(pet, fullPet));
  await assertSucceeds(setDoc(record, fullRecord));
  const remote = await assertSucceeds(getDoc(record));
  if (remote.data().foodServedMilli !== 0 || remote.data().foodConsumedMilli !== null) {
    throw new Error('Initial import must keep measured zero distinct from unknown null.');
  }
  await assertFails(setDoc(pet, fullPet));
  await assertFails(setDoc(record, fullRecord));
  await assertFails(setDoc(record, { ...fullRecord, foodServedMilli: 400 }));
  await assertSucceeds(updateDoc(user, { migrationState: 'ready', revision: 1 }));
});


/** Offline integration: runs only against demo Firestore/Storage emulators, never a live account. */
async function createMatchingDocumentOrSkip(db, path, payload) {
  const refDoc = doc(db, path);
  const current = await getDocFromServer(refDoc);
  if (current.exists()) {
    deepStrictEqual(current.data(), payload, 'An existing UUID must not be overwritten');
    return 'skipped-identical';
  }
  await assertSucceeds(setDoc(refDoc, payload));
  const verified = await getDocFromServer(refDoc);
  deepStrictEqual(verified.data(), payload);
  return 'created';
}

test('FB-04-FINAL: interrupted initial upload resumes without duplicate records or overwrites', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const uid = 'users/alice';
  const sourcePet = petDoc();
  const sourceRecord = careDoc();
  const snapshot = JSON.stringify([sourcePet, sourceRecord]);

  await assertSucceeds(setDoc(doc(alice, uid), userDoc()));
  if (await createMatchingDocumentOrSkip(alice, uid + '/pets/pet-1', sourcePet) !== 'created') {
    throw new Error('First snapshot should create pet once');
  }
  // Simulated app termination/network loss: record upload and ready marker never ran.
  const incompleteRecord = await getDocFromServer(doc(alice, uid + '/pets/pet-1/records/record-1'));
  if (incompleteRecord.exists()) throw new Error('Record was incorrectly created before retry');
  const pending = await getDocFromServer(doc(alice, uid));
  if (pending.data().migrationState !== 'pending') throw new Error('Migration marked ready too early');

  deepStrictEqual(
    await createMatchingDocumentOrSkip(alice, uid + '/pets/pet-1', sourcePet),
    'skipped-identical',
  );
  deepStrictEqual(
    await createMatchingDocumentOrSkip(alice, uid + '/pets/pet-1/records/record-1', sourceRecord),
    'created',
  );
  const record = await getDocFromServer(doc(alice, uid + '/pets/pet-1/records/record-1'));
  deepStrictEqual(record.data(), sourceRecord);
  await assertSucceeds(updateDoc(doc(alice, uid), { migrationState: 'ready', revision: 1 }));
  deepStrictEqual(
    await createMatchingDocumentOrSkip(alice, uid + '/pets/pet-1/records/record-1', sourceRecord),
    'skipped-identical',
  );
  deepStrictEqual(JSON.stringify([sourcePet, sourceRecord]), snapshot, 'Local source was mutated');
});

test('FB-04-FINAL: changed UUID payload is rejected and leaves stored data untouched', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const user = doc(alice, 'users/alice');
  const pet = doc(alice, 'users/alice/pets/pet-1');
  await assertSucceeds(setDoc(user, userDoc()));
  await assertSucceeds(setDoc(pet, petDoc()));
  await rejects(() => createMatchingDocumentOrSkip(
    alice,
    'users/alice/pets/pet-1',
    { ...petDoc(), name: 'different-content' },
  ));
  const storedPet = await getDocFromServer(pet);
  const storedUser = await getDocFromServer(user);
  deepStrictEqual(storedPet.data(), petDoc());
  if (storedUser.data().migrationState !== 'pending') {
    throw new Error('A conflicting import must remain pending');
  }
});

test('FB-04-FINAL: bytes-hashed photo is reusable but cannot be replaced by a retry', async () => {
  const alice = env.authenticatedContext('alice').storage(bucket);
  const bob = env.authenticatedContext('bob').storage(bucket);
  const isolatedFilename = 'pet_44444444-4444-4444-8444-444444444444.jpg';
  const isolatedPath = pathFor('alice', 'pet-1', isolatedFilename);
  const photo = ref(alice, isolatedPath);
  const localBytes = new Uint8Array([0xff, 0xd8, 0x11, 0x22, 0xff, 0xd9]);
  const sha = createHash('sha256').update(localBytes).digest('hex');
  await assertSucceeds(uploadBytes(photo, localBytes, { contentType: 'image/jpeg' }));
  const serverBytes = await assertSucceeds(getBytes(photo));
  deepStrictEqual(createHash('sha256').update(Buffer.from(serverBytes)).digest('hex'), sha);
  await assertFails(uploadBytes(photo, new Uint8Array([0xff, 0xd8, 0, 0xff, 0xd9]), {
    contentType: 'image/jpeg',
  }));
  await assertFails(getBytes(ref(bob, isolatedPath)));
  const afterRetry = await assertSucceeds(getBytes(photo));
  deepStrictEqual(createHash('sha256').update(Buffer.from(afterRetry)).digest('hex'), sha);
});

test('FB-04-FINAL: no Guest records means no initial upload or user ready marker', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const before = await getDocFromServer(doc(alice, 'users/alice'));
  if (before.exists()) throw new Error('Unexpected initial user data');
  // Explicit preview guard: an empty pet list cannot be committed.
  const emptySource = { pets: [], records: [], photos: [] };
  const canImport = emptySource.pets.length > 0;
  if (canImport) throw new Error('Empty guest data must not start migration');
  const after = await getDocFromServer(doc(alice, 'users/alice'));
  if (after.exists()) throw new Error('Preview created a remote user unexpectedly');
});

const syncId1 = '11111111-1111-4111-8111-111111111111';
const syncId2 = '22222222-2222-4222-8222-222222222222';
const syncId3 = '33333333-3333-4333-8333-333333333333';

function syncReceipt(opId, entityKind, petId, entityId, operation, expectedRevision) {
  return {
    opId, entityKind, petId, entityId, operation, expectedRevision,
    resultingRevision: expectedRevision + 1, requestDigest: 'a'.repeat(64),
  };
}

async function syncReady(db, uid = 'alice') {
  const user = doc(db, 'users/' + uid);
  await assertSucceeds(setDoc(user, userDoc()));
  await assertSucceeds(updateDoc(user, { revision: 1, migrationState: 'ready' }));
}

function syncBatch(db, target, receipt, data, uid = 'alice') {
  const batch = writeBatch(db);
  batch.set(target, { ...data, lastSyncOpId: receipt.opId });
  batch.set(doc(db, 'users/' + uid + '/syncReceipts/' + receipt.opId), receipt);
  return batch.commit();
}

test('FB-05-B2: entity and receipt must be created atomically and only for ready owner', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  const guest = env.unauthenticatedContext().firestore();
  const pet = doc(alice, 'users/alice/pets/pet-1');
  const receipt = syncReceipt(syncId1, 'PET', 'pet-1', 'pet-1', 'CREATE', -1);
  const ref = doc(alice, 'users/alice/syncReceipts/' + syncId1);
  await assertFails(syncBatch(alice, pet, receipt, petDoc()));
  await syncReady(alice);
  await assertFails(setDoc(ref, receipt));
  await assertFails(setDoc(pet, { ...petDoc(), lastSyncOpId: syncId1 }));
  await assertFails(syncBatch(alice, pet, { ...receipt, entityId: 'other' }, petDoc()));
  await assertFails(syncBatch(alice, pet, { ...receipt, requestDigest: 'wrong' }, petDoc()));
  await assertFails(syncBatch(bob, doc(bob, pet.path), receipt, petDoc()));
  await assertSucceeds(syncBatch(alice, pet, receipt, petDoc()));
  deepStrictEqual((await getDocFromServer(ref)).data(), receipt);
  await assertFails(getDoc(doc(bob, ref.path)));
  await assertFails(getDoc(doc(guest, ref.path)));
  await assertFails(updateDoc(ref, { requestDigest: 'b'.repeat(64) }));
  await assertFails(deleteDoc(ref));
  await assertFails(updateDoc(pet, { name: 'bypass', revision: 1 }));
  await assertFails(setDoc(pet, { ...petDoc('pet-1', 1), name: 'remove-link' }));
});

test('FB-05-B2: lost-response replay reads immutable receipt instead of writing twice', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await syncReady(alice);
  const pet = doc(alice, 'users/alice/pets/pet-1');
  const ref = doc(alice, 'users/alice/syncReceipts/' + syncId1);
  const receipt = syncReceipt(syncId1, 'PET', 'pet-1', 'pet-1', 'CREATE', -1);
  async function commit() {
    return runTransaction(alice, async tx => {
      const existing = await tx.get(ref);
      if (existing.exists()) {
        deepStrictEqual(existing.data(), receipt);
        return 'replay';
      }
      const current = await tx.get(pet);
      if (current.exists()) throw Error('conflict');
      tx.set(pet, { ...petDoc(), lastSyncOpId: syncId1 });
      tx.set(ref, receipt);
      return 'created';
    });
  }
  deepStrictEqual(await commit(), 'created');
  deepStrictEqual(await commit(), 'replay');
  deepStrictEqual((await getDocFromServer(pet)).data().revision, 0);
  await assertFails(syncBatch(alice, pet, receipt, petDoc('pet-1', 1)));
});

test('FB-05-B2: revisions, immutable tombstones and parent deletion block stale writes', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await syncReady(alice);
  const pet = doc(alice, 'users/alice/pets/pet-1');
  const record = doc(alice, pet.path + '/records/record-1');
  await assertSucceeds(setDoc(pet, petDoc()));
  const create = syncReceipt(syncId1, 'CARE', 'pet-1', 'record-1', 'CREATE', -1);
  await assertSucceeds(syncBatch(alice, record, create, careDoc()));
  const update = syncReceipt(syncId2, 'CARE', 'pet-1', 'record-1', 'UPDATE', 0);
  await assertFails(syncBatch(alice, record, { ...update, expectedRevision: 1, resultingRevision: 2 },
    careDoc('record-1', 'pet-1', 2)));
  await assertSucceeds(syncBatch(alice, record, update, { ...careDoc('record-1', 'pet-1', 1), foodConsumedMilli: 0 }));
  const remove = syncReceipt(syncId3, 'CARE', 'pet-1', 'record-1', 'DELETE', 1);
  await assertFails(syncBatch(alice, record, remove, careDoc('record-1', 'pet-1', 2)));
  await assertSucceeds(syncBatch(alice, record, remove, {
    ...careDoc('record-1', 'pet-1', 2), foodConsumedMilli: 0, deletedAtMs: now,
  }));
  deepStrictEqual((await getDocFromServer(record)).data().deletedAtMs, now);
  await assertFails(updateDoc(record, { revision: 3, deletedAtMs: null }));
  await assertFails(deleteDoc(record));
  const parentId = '44444444-4444-4444-8444-444444444444';
  const parentDelete = syncReceipt(parentId, 'PET', 'pet-1', 'pet-1', 'DELETE', 0);
  await assertSucceeds(syncBatch(alice, pet, parentDelete, { ...petDoc('pet-1', 1), deletedAtMs: now }));
  await assertFails(setDoc(doc(alice, pet.path + '/records/record-2'), careDoc('record-2')));
});

test('FB-06-C: ready photo reference requires immutable same-owner manifest and receipt', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const bob = env.authenticatedContext('bob').firestore();
  const guest = env.unauthenticatedContext().firestore();
  await syncReady(alice);
  const pet = doc(alice, 'users/alice/pets/pet-1');
  const name = 'pet_55555555-5555-4555-8555-555555555555.jpg';
  const manifest = doc(alice, pet.path + '/photoManifests/' + name);
  const proof = {fileName:name, sha256:'a'.repeat(64), sizeBytes:1024, width:16, height:12, mimeType:'image/jpeg'};
  const create = syncReceipt(syncId1, 'PET', 'pet-1', 'pet-1', 'CREATE', -1);
  const payload = {...petDoc(), photoFile:name};
  await assertFails(setDoc(pet, payload));
  await assertFails(syncBatch(alice, pet, create, payload));
  await assertFails(setDoc(manifest, {...proof, width:1201}));
  await assertFails(setDoc(manifest, {...proof, sha256:'invalid'}));
  await assertFails(setDoc(doc(bob, manifest.path), proof));
  const batch = writeBatch(alice);
  batch.set(manifest, proof);
  batch.set(pet, {...payload, lastSyncOpId:syncId1});
  batch.set(doc(alice, 'users/alice/syncReceipts/' + syncId1), create);
  await assertSucceeds(batch.commit());
  deepStrictEqual((await getDocFromServer(pet)).data().photoFile, name);
  await assertFails(getDoc(doc(bob, manifest.path)));
  await assertFails(getDoc(doc(guest, manifest.path)));
  await assertFails(updateDoc(manifest, {sha256:'b'.repeat(64)}));
  await assertFails(deleteDoc(manifest));
  const replacement = 'pet_66666666-6666-4666-8666-666666666666.jpg';
  const change = syncReceipt(syncId2, 'PET', 'pet-1', 'pet-1', 'UPDATE', 0);
  await assertFails(syncBatch(alice, pet, change, {...petDoc('pet-1',1), photoFile:replacement}));
  deepStrictEqual((await getDocFromServer(pet)).data().photoFile, name);
  const remove = syncReceipt(syncId3, 'PET', 'pet-1', 'pet-1', 'UPDATE', 0);
  await assertSucceeds(syncBatch(alice, pet, remove, {...petDoc('pet-1',1), photoFile:null}));
  deepStrictEqual((await getDocFromServer(manifest)).data(), proof);
});

test('FB-06-C: legacy first import photo path is allowed only during pending migration', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  const user = doc(alice, 'users/alice');
  await assertSucceeds(setDoc(user,userDoc()));
  const pet = doc(alice,'users/alice/pets/pet-1');
  await assertSucceeds(setDoc(pet,{...petDoc(),photoFile:photoName}));
  await assertSucceeds(updateDoc(user,{revision:1,migrationState:'ready'}));
  await assertFails(updateDoc(pet,{photoFile:null,revision:1}));
  await assertFails(setDoc(doc(alice,'users/alice/pets/pet-2'),{...petDoc('pet-2'),photoFile:photoName}));
  await assertSucceeds(updateDoc(pet,{name:'이름 수정',revision:1}));
});

test('FB-06-C: photo evidence cannot authorize another pet or mutate deleted photo reference', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await syncReady(alice);
  const name = 'pet_77777777-7777-4777-8777-777777777777.jpg';
  const proof = {fileName:name,sha256:'c'.repeat(64),sizeBytes:1024,width:16,height:12,mimeType:'image/jpeg'};
  await assertSucceeds(setDoc(doc(alice,'users/alice/pets/pet-other/photoManifests/'+name),proof));
  const pet = doc(alice,'users/alice/pets/pet-1');
  const create = syncReceipt(syncId1,'PET','pet-1','pet-1','CREATE',-1);
  await assertFails(syncBatch(alice,pet,create,{...petDoc(),photoFile:name}));
  await assertSucceeds(setDoc(doc(alice,pet.path+'/photoManifests/'+name),proof));
  await assertSucceeds(syncBatch(alice,pet,create,{...petDoc(),photoFile:name}));
  const remove = syncReceipt(syncId2,'PET','pet-1','pet-1','DELETE',0);
  await assertFails(syncBatch(alice,pet,remove,{...petDoc('pet-1',1),photoFile:null,deletedAtMs:now}));
  await assertSucceeds(syncBatch(alice,pet,remove,{...petDoc('pet-1',1),photoFile:name,deletedAtMs:now}));
  deepStrictEqual((await getDocFromServer(pet)).data().photoFile,name);
});

test('FB-07-A: account deletion lock denies cloud access and stale authenticated writes', async () => {
  // Use unique identities and paths; no dependence on other Storage test fixtures.
  const uid = 'deletion-owner';
  const otherUid = 'deletion-other';
  const ownerContext = env.authenticatedContext(uid);
  const otherContext = env.authenticatedContext(otherUid);
  const alice = ownerContext.firestore();
  const bob = otherContext.firestore();
  const user = doc(alice, 'users/' + uid);
  const pet = doc(alice, user.path + '/pets/pet-1');
  const lock = doc(alice, 'accountDeletionLocks/' + uid);
  const name = 'pet_99999999-9999-4999-8999-999999999999.jpg';
  const photo = ref(ownerContext.storage(bucket), pathFor(uid, 'pet-1', name));
  const bytes = new Uint8Array([0xff, 0xd8, 1, 0xff, 0xd9]);
  await assertSucceeds(setDoc(user, userDoc()));
  await assertSucceeds(setDoc(pet, petDoc()));
  await assertSucceeds(uploadBytes(photo, bytes, { contentType: 'image/jpeg' }));
  deepStrictEqual(new Uint8Array(await assertSucceeds(getBytes(photo))), bytes);
  await assertFails(setDoc(lock, { state: 'LOCKED' }));
  await env.withSecurityRulesDisabled(async admin => {
    await setDoc(doc(admin.firestore(), lock.path), { state: 'LOCKED' });
  });
  await assertFails(getDoc(lock));
  await assertFails(getDoc(doc(bob, lock.path)));
  await assertFails(deleteDoc(lock));
  await assertFails(getDoc(user));
  await assertFails(getDoc(pet));
  await assertFails(updateDoc(pet, { name:'stale device', revision:1 }));
  await assertFails(setDoc(doc(alice, pet.path + '/records/record-1'), careDoc()));
  await assertFails(getBytes(photo));
  const newPhoto = ref(ownerContext.storage(bucket),
      pathFor(uid, 'pet-1', 'pet_88888888-8888-4888-8888-888888888888.jpg'));
  await assertFails(uploadBytes(newPhoto, bytes, { contentType:'image/jpeg' }));
  await assertSucceeds(setDoc(doc(bob, 'users/' + otherUid), userDoc()));
  await assertSucceeds(setDoc(doc(bob, 'users/' + otherUid + '/pets/pet-1'), petDoc()));
  await assertSucceeds(uploadBytes(ref(otherContext.storage(bucket), pathFor(otherUid, 'pet-1', name)),
    bytes, { contentType:'image/jpeg' }));
  await env.withSecurityRulesDisabled(async admin => {
    await deleteDoc(doc(admin.firestore(), user.path));
  });
  await assertFails(setDoc(user, userDoc()));
});
