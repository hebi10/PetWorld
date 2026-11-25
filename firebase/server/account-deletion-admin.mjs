import { randomUUID, createHash, timingSafeEqual } from 'node:crypto';
import { DeletionDenied, deletionJobId } from './account-deletion.mjs';

const LEASE_MS = 20 * 60 * 1000;
const JOB = 'accountDeletionJobs';
const LOCK = 'accountDeletionLocks';
const sha = text => createHash('sha256').update(text, 'utf8').digest('hex');
const hex = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const phases = ['LOCKED','AUTH_REVOKED','RECORDS_PURGED','PHOTOS_PURGED','AUTH_DELETED','VERIFIED','COMPLETE'];
const missingAuth = error => error?.code === 'auth/user-not-found';

export function statusTicketDigest(ticket) {
  if (typeof ticket !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(ticket)) throw new DeletionDenied();
  const bytes = Buffer.from(ticket,'base64url');
  if (bytes.length !== 32 || bytes.toString('base64url') !== ticket) throw new DeletionDenied();
  return sha(ticket);
}
function sameDigest(a,b) {
  return hex(a) && hex(b) && timingSafeEqual(Buffer.from(a,'hex'),Buffer.from(b,'hex'));
}
function checkedJob(uid, jobId) {
  if (deletionJobId(uid) !== jobId) throw new DeletionDenied();
}
function readJob(snapshot, uid, jobId) {
  const data = snapshot.data();
  if (!snapshot.exists || !data || data.uid !== uid || data.jobId !== jobId ||
      !phases.includes(data.phase)) throw new DeletionDenied();
  return data;
}

/** Firebase Admin SDK-compatible port. Only instantiated by a trusted, checked server runtime.
 * Lock never expires; lease expiration only permits idempotent same-UID work to resume.
 * Worker runtime must be less than lease lifetime. A stale worker cannot advance state.
 */
export function createAdminDeletionPort({db, auth, bucket, ticketDigest = null,
    now = Date.now, retentionAudit = async () => false}) {
  const jobRef = id => db.doc(JOB+'/'+id);
  const lockRef = uid => db.doc(LOCK+'/'+uid);
  const prefix = uid => { deletionJobId(uid); return 'users/'+uid+'/'; };

  async function readPair(tx,uid,jobId) {
    checkedJob(uid,jobId);
    const lock = await tx.get(lockRef(uid));
    const snapshot = await tx.get(jobRef(jobId));
    const job = readJob(snapshot,uid,jobId);
    if (!lock.exists || lock.data()?.jobId !== jobId || lock.data()?.state !== 'LOCKED')
      throw new DeletionDenied();
    return job;
  }
  function checkLease(job,lease) {
    if (!lease || job.leaseId !== lease || !Number.isSafeInteger(job.leaseUntil) ||
        job.leaseUntil <= now()) throw new DeletionDenied();
  }
  async function assertLease(uid,lease) {
    const id = deletionJobId(uid);
    return db.runTransaction(async tx => {
      const job = await readPair(tx,uid,id);
      checkLease(job,lease);
      return job;
    });
  }
  async function scoped(uid,lease,action) {
    await assertLease(uid,lease);
    const value = await action();
    await assertLease(uid,lease);
    return value;
  }
  async function listPage(uid,softDeleted=false,limit=100) {
    const path = prefix(uid);
    const options = {prefix:path,autoPaginate:false,maxResults:limit};
    if (softDeleted) options.softDeleted = true;
    else options.versions = true;
    const [files,next] = await bucket.getFiles(options);
    if (!Array.isArray(files) || files.some(f => typeof f.name !== 'string' || !f.name.startsWith(path)))
      throw new DeletionDenied();
    return {files,more:!!next};
  }
  async function firestoreEmpty(uid) {
    const ref = db.doc('users/'+uid);
    return !(await ref.get()).exists && (await ref.listCollections()).length === 0;
  }
  async function authAbsent(uid) {
    try { await auth.getUser(uid); return false; }
    catch (error) { if (missingAuth(error)) return true; throw error; }
  }
  async function ignoreMissingAuth(action) {
    try { await action(); } catch (error) { if (!missingAuth(error)) throw error; }
  }

  return {
    async requestLock(uid,jobId) {
      checkedJob(uid,jobId);
      if (!hex(ticketDigest)) throw new DeletionDenied();
      return db.runTransaction(async tx => {
        const l = lockRef(uid), j = jobRef(jobId);
        const lock = await tx.get(l), existing = await tx.get(j);
        if (lock.exists || existing.exists) {
          const old = readJob(existing,uid,jobId);
          if (!lock.exists || lock.data()?.jobId !== jobId || lock.data()?.state !== 'LOCKED' ||
              !sameDigest(old.ticketDigest,ticketDigest)) throw new DeletionDenied();
          return {jobId,phase:old.phase};
        }
        tx.create(l,{jobId,state:'LOCKED',createdAt:now()});
        tx.create(j,{uid,jobId,phase:'LOCKED',ticketDigest,leaseId:null,leaseUntil:0,createdAt:now()});
        return {jobId,phase:'LOCKED'};
      });
    },
    async withLease(uid,jobId,fn) {
      checkedJob(uid,jobId);
      const lease = randomUUID();
      await db.runTransaction(async tx => {
        const job = await readPair(tx,uid,jobId);
        if (job.leaseId && job.leaseUntil > now()) throw new DeletionDenied();
        tx.update(jobRef(jobId),{leaseId:lease,leaseUntil:now()+LEASE_MS});
      });
      try { return await fn(lease); }
      finally {
        await db.runTransaction(async tx => {
          const job = await readPair(tx,uid,jobId);
          if (job.leaseId === lease) tx.update(jobRef(jobId),{leaseId:null,leaseUntil:0});
        });
      }
    },
    async readLockedJob(uid,jobId,lease) {
      checkedJob(uid,jobId);
      return assertLease(uid,lease);
    },
    async advance(uid,jobId,lease,from,to) {
      checkedJob(uid,jobId);
      if (phases.indexOf(to) !== phases.indexOf(from)+1 || !phases.includes(from)) throw new DeletionDenied();
      return db.runTransaction(async tx => {
        const job = await readPair(tx,uid,jobId);
        checkLease(job,lease);
        if (job.phase !== from) return false;
        tx.update(jobRef(jobId),{phase:to,updatedAt:now()});
        return true;
      });
    },
    async disableAndRevokeAuth(uid,lease) {
      await scoped(uid,lease,async () => ignoreMissingAuth(async () => {
        await auth.updateUser(uid,{disabled:true});
        await assertLease(uid,lease);
        await auth.revokeRefreshTokens(uid);
      }));
    },
    async purgeUserDocumentTree(uid,lease) {
      await scoped(uid,lease,async () => {
        // recursiveDelete includes subcollections beneath missing parent documents.
        await db.recursiveDelete(db.doc('users/'+uid));
        if (!await firestoreEmpty(uid)) throw new Error('Firestore deletion verification pending');
      });
    },
    async purgeUserObjects(uid,lease) {
      await scoped(uid,lease,async () => {
        // One bounded batch per invocation; repeating the phase is safe.
        const {files,more} = await listPage(uid);
        for (const file of files) {
          await assertLease(uid,lease);
          const generation = file.generation ?? file.metadata?.generation;
          if (typeof generation !== 'string' || !/^[0-9]+$/.test(generation))
            throw new DeletionDenied();
          try { await file.delete({ifGenerationMatch:generation}); }
          catch (error) { if (Number(error?.code) !== 404) throw error; }
        }
        const remaining = await listPage(uid,false,1);
        if (more || remaining.more || remaining.files.length) throw new Error('Storage deletion continuation required');
      });
    },
    async deleteAuthUser(uid,lease) {
      await scoped(uid,lease,async () => ignoreMissingAuth(() => auth.deleteUser(uid)));
    },
    async verifyEmpty(uid,lease) {
      return scoped(uid,lease,async () => {
        const records = await firestoreEmpty(uid);
        const live = await listPage(uid,false,1);
        // Unsupported listing, retained versions, or failed policy audit must not report success.
        const retained = await listPage(uid,true,1);
        const storageEmpty = !live.more && !live.files.length && !retained.more && !retained.files.length;
        const absent = await authAbsent(uid);
        const policy = await retentionAudit({uid,jobId:deletionJobId(uid)});
        return {firestoreEmpty:records,storageEmpty,authAbsent:absent,retentionPolicySatisfied:policy===true};
      });
    },
    async readStatus(jobId,ticket) {
      if (!hex(jobId)) throw new DeletionDenied();
      const digest = statusTicketDigest(ticket);
      const snap = await jobRef(jobId).get();
      const job = snap.data();
      if (!snap.exists || !job || !sameDigest(job.ticketDigest,digest) ||
          job.jobId !== jobId || !phases.includes(job.phase)) throw new DeletionDenied();
      checkedJob(job.uid,jobId);
      const lock = await lockRef(job.uid).get();
      if (!lock.exists || lock.data()?.jobId !== jobId) throw new DeletionDenied();
      return {jobId,phase:job.phase};
    },
  };
}
