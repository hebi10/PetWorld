import { test } from 'node:test';
import assert from 'node:assert/strict';
import { authorizeDeletion, deletionJobId, requestAccountDeletion, advanceAccountDeletion, DeletionDenied } from '../server/account-deletion.mjs';

const now = 1795572000;
const confirmation = 'DELETE_MY_PETWORLD_ACCOUNT';
const auth = uid => ({uid, auth_time:now-30, firebase:{sign_in_provider:'google.com'}});

class MemoryPort {
  constructor() {
    this.jobs = new Map();
    this.calls = [];
    this.fail = null;
    this.empty = {firestoreEmpty:true, storageEmpty:true, authAbsent:true, retentionPolicySatisfied:true};
    this.held = new Set();
  }
  async requestLock(uid, jobId) {
    if (!this.jobs.has(uid)) this.jobs.set(uid, {jobId,phase:'LOCKED'});
    return {...this.jobs.get(uid)};
  }
  async withLease(uid, jobId, fn) {
    if (this.held.has(uid)) throw new DeletionDenied();
    this.held.add(uid);
    try { return await fn('test-lease'); } finally { this.held.delete(uid); }
  }
  async readLockedJob(uid) { return this.jobs.get(uid); }
  async advance(uid, jobId, lease, before, after) {
    const job = this.jobs.get(uid);
    if (!this.held.has(uid) || job?.jobId !== jobId || job.phase !== before) return false;
    job.phase = after;
    return true;
  }
  async op(name, uid) {
    assert(this.held.has(uid));
    this.calls.push([name,uid]);
    if (this.fail === name) { this.fail = null; throw new Error('interrupted'); }
  }
  async disableAndRevokeAuth(uid) { await this.op('revoke',uid); }
  async purgeUserDocumentTree(uid) { await this.op('records',uid); }
  async purgeUserObjects(uid) { await this.op('photos',uid); }
  async deleteAuthUser(uid) { await this.op('auth',uid); }
  async verifyEmpty(uid) { await this.op('verify',uid); return this.empty; }
}
test('FB07-A: fresh verified Google auth and explicit confirmation only', () => {
  assert.equal(authorizeDeletion(auth('alice'),confirmation,now),'alice');
  for (const bad of [null,auth('../bob'),{...auth('alice'),auth_time:now-301},
    {...auth('alice'),auth_time:now+1},{...auth('alice'),firebase:{sign_in_provider:'anonymous'}}]) {
    assert.throws(()=>authorizeDeletion(bad,confirmation,now),DeletionDenied);
  }
  assert.throws(()=>authorizeDeletion(auth('alice'),'cancel',now),DeletionDenied);
});
test('FB07-A: same account request is idempotent, other account cannot advance its job', async () => {
  const p = new MemoryPort();
  const a = await requestAccountDeletion(p,auth('alice'),confirmation,now);
  assert.deepEqual(await requestAccountDeletion(p,auth('alice'),confirmation,now),a);
  const b = await requestAccountDeletion(p,auth('bob'),confirmation,now);
  assert.notEqual(a.jobId,b.jobId);
  await assert.rejects(()=>advanceAccountDeletion(p,'bob',a.jobId),DeletionDenied);
  assert.equal(p.calls.length,0);
  assert.equal(a.phase,'LOCKED');
});
test('FB07-A: interrupted step retries same phase, complete requires verified absence', async () => {
  const p = new MemoryPort();
  const {jobId} = await requestAccountDeletion(p,auth('alice'),confirmation,now);
  const step = ()=>advanceAccountDeletion(p,'alice',jobId);
  assert.equal((await step()).phase,'AUTH_REVOKED');
  p.fail = 'records';
  await assert.rejects(step);
  assert.equal(p.jobs.get('alice').phase,'AUTH_REVOKED');
  assert.equal((await step()).phase,'RECORDS_PURGED');
  assert.equal((await step()).phase,'PHOTOS_PURGED');
  assert.equal((await step()).phase,'AUTH_DELETED');
  p.empty.storageEmpty = false;
  assert.equal((await step()).pendingVerification,true);
  p.empty.storageEmpty = true;
  assert.equal((await step()).phase,'VERIFIED');
  p.empty.retentionPolicySatisfied = false;
  assert.equal((await step()).pendingVerification,true);
  p.empty.retentionPolicySatisfied = true;
  assert.equal((await step()).phase,'COMPLETE');
  assert.equal((await step()).phase,'COMPLETE');
  assert.deepEqual(p.calls.map(([name])=>name),['revoke','records','records','photos','auth','verify','verify','verify','verify']);
  assert(p.calls.every(([,uid])=>uid==='alice'));
  assert(p.jobs.has('alice'),'deletion lock remains for anti-replay');
});
