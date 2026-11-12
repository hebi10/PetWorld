import { readFileSync } from 'node:fs';
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
  setDoc,
  updateDoc,
} from 'firebase/firestore';
import {
  deleteObject,
  getBytes,
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

test('Storage: guest and different UID cannot access a private image', async () => {
  const alice = env.authenticatedContext('alice').storage(bucket);
  const bob = env.authenticatedContext('bob').storage(bucket);
  const guest = env.unauthenticatedContext().storage(bucket);
  const path = pathFor('alice');
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
  const valid = ref(alice, pathFor('alice'));
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
