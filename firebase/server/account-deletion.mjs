import { createHash } from 'node:crypto';

const FRESH_AUTH_SECONDS = 300;
const PHASES = Object.freeze(['LOCKED', 'AUTH_REVOKED', 'RECORDS_PURGED', 'PHOTOS_PURGED', 'AUTH_DELETED', 'VERIFIED', 'COMPLETE']);

export class DeletionDenied extends Error {
  constructor() { super('Account deletion authorization or safety check failed'); }
}

function validUid(uid) {
  return typeof uid === 'string' && uid.length > 0 && uid.length <= 128 &&
    !uid.includes('/') && uid !== '.' && uid !== '..';
}

function requireUid(uid) {
  if (!validUid(uid)) throw new DeletionDenied();
}

/** auth must be from a verified, revocation-checked ID token; never a client-supplied UID. */
export function authorizeDeletion(auth, confirmation, nowSeconds) {
  const uid = auth?.uid;
  requireUid(uid);
  if (!Number.isSafeInteger(nowSeconds) || !Number.isSafeInteger(auth.auth_time) ||
      auth.auth_time > nowSeconds || nowSeconds - auth.auth_time > FRESH_AUTH_SECONDS ||
      auth.firebase?.sign_in_provider !== 'google.com' ||
      confirmation !== 'DELETE_MY_PETWORLD_ACCOUNT') throw new DeletionDenied();
  return uid;
}

export function deletionJobId(uid) {
  requireUid(uid);
  return createHash('sha256').update('petworld-account-deletion-v1\0' + uid, 'utf8').digest('hex');
}

/**
 * Trusted server port (no Firebase credentials, HTTP handler or client permissions here).
 * requestLock must atomically create/get the per-UID lock and job.
 * withLease must use an exclusive, fenced server lease, renewed during long operations.
 * advance must compare-and-set phase plus lease; each destructive adapter operation
 * must be idempotent and reject targets outside the exact authenticated UID.
 * verifyEmpty must check all nested Firestore documents, live and retained Storage
 * objects per approved policy, and Auth absence. No inferred success.
 */
export async function requestAccountDeletion(port, verifiedAuth, confirmation, nowSeconds) {
  const uid = authorizeDeletion(verifiedAuth, confirmation, nowSeconds);
  const jobId = deletionJobId(uid);
  const job = await port.requestLock(uid, jobId);
  if (job?.jobId !== jobId || !PHASES.includes(job.phase)) throw new DeletionDenied();
  return { jobId, phase: job.phase };
}

export async function advanceAccountDeletion(port, uid, jobId) {
  requireUid(uid);
  if (jobId !== deletionJobId(uid)) throw new DeletionDenied();
  return port.withLease(uid, jobId, async lease => {
    const job = await port.readLockedJob(uid, jobId, lease);
    if (!job || job.jobId !== jobId || !PHASES.includes(job.phase)) throw new DeletionDenied();
    if (job.phase === 'COMPLETE') return { jobId, phase: 'COMPLETE' };

    let next;
    switch (job.phase) {
      case 'LOCKED':
        await port.disableAndRevokeAuth(uid, lease);
        next = 'AUTH_REVOKED';
        break;
      case 'AUTH_REVOKED':
        // Adapter must recurse, not just delete the parent document.
        await port.purgeUserDocumentTree(uid, lease);
        next = 'RECORDS_PURGED';
        break;
      case 'RECORDS_PURGED':
        // Adapter must drain the exact users/<uid>/ object prefix incl. policy-retained versions.
        await port.purgeUserObjects(uid, lease);
        next = 'PHOTOS_PURGED';
        break;
      case 'PHOTOS_PURGED':
        await port.deleteAuthUser(uid, lease);
        next = 'AUTH_DELETED';
        break;
      case 'AUTH_DELETED': {
        const verified = await port.verifyEmpty(uid, lease);
        if (verified?.firestoreEmpty !== true || verified?.storageEmpty !== true ||
            verified?.authAbsent !== true || verified?.retentionPolicySatisfied !== true) {
          return { jobId, phase: job.phase, pendingVerification: true };
        }
        next = 'VERIFIED';
        break;
      }
      case 'VERIFIED': {
        const verified = await port.verifyEmpty(uid, lease);
        if (verified?.firestoreEmpty !== true || verified?.storageEmpty !== true ||
            verified?.authAbsent !== true || verified?.retentionPolicySatisfied !== true) {
          return { jobId, phase: job.phase, pendingVerification: true };
        }
        // A lock cannot be removed here: stale ID tokens and concurrent requests
        // may remain valid. Separate approved retention and anti-replay policy required.
        next = 'COMPLETE';
        break;
      }
      default: throw new DeletionDenied();
    }
    if (await port.advance(uid, jobId, lease, job.phase, next) !== true) throw new DeletionDenied();
    return { jobId, phase: next };
  });
}
