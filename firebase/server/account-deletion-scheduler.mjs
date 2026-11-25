import {randomUUID} from 'node:crypto';
import {DeletionDenied} from './account-deletion.mjs';
import {prepareDeletionWorkerRuntime} from './account-deletion-runtime.mjs';

// Longer than the existing 20-minute per-job lease. The independently enforced
// worker hard deadline must terminate the invocation well before either lease expires.
const schedulerLeaseMs = 25 * 60_000;
const maxWorkerMs = 10 * 60_000;
const batchBudgetMs = 60_000;
const batchLimit = 10;
const cursorValid = value => value === null || (typeof value === 'string' && /^[a-f0-9]{64}$/.test(value));
const tokenValid = value => typeof value === 'string' && /^[a-f0-9-]{36}$/.test(value);
const safeTime = value => Number.isSafeInteger(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER - schedulerLeaseMs;
const validDeadline = value => Number.isSafeInteger(value) && value > batchBudgetMs && value <= maxWorkerMs;

function state(snapshot) {
  if (!snapshot.exists) return {cursor:null,leaseId:null,leaseUntilMs:0,observedAtMs:0};
  const row = snapshot.data();
  if (!row || !cursorValid(row.cursor) || !safeTime(row.observedAtMs) ||
      !Number.isSafeInteger(row.leaseUntilMs) ||
      !(row.leaseId === null && row.leaseUntilMs === 0 ||
        tokenValid(row.leaseId) && row.leaseUntilMs === row.observedAtMs + schedulerLeaseMs))
    throw new DeletionDenied();
  return row;
}

function checkedClock(now,row) {
  const time = now();
  if (!safeTime(time) || time < row.observedAtMs) throw new DeletionDenied();
  return time;
}

/**
 * One Admin-only Firestore checkpoint. Acquisition and cursor commit are
 * transactional and fenced by a fresh lease ID. An uncertain/crashed invocation
 * leaves the old cursor and lease intact; never release the lease from a finally.
 */
export function createDeletionSchedulerStore({db,now=Date.now} = {}) {
  if (typeof db?.collection !== 'function' || typeof db?.runTransaction !== 'function' ||
      typeof now !== 'function') throw new DeletionDenied();
  const ref = db.collection('accountDeletionScheduler').doc('v1');
  return Object.freeze({
    async claim() {
      const leaseId = randomUUID();
      return db.runTransaction(async tx => {
        const row = state(await tx.get(ref));
        const time = checkedClock(now,row);
        if (row.leaseId !== null && row.leaseUntilMs > time) return null;
        const next = {cursor:row.cursor,leaseId,leaseUntilMs:time+schedulerLeaseMs,observedAtMs:time};
        tx.set(ref,next);
        return Object.freeze(next);
      });
    },
    async commit(claim,nextCursor,workerDeadlineMs) {
      if (!claim || !tokenValid(claim.leaseId) || !cursorValid(claim.cursor) ||
          !cursorValid(nextCursor) || !validDeadline(workerDeadlineMs) ||
          (claim.cursor !== null && nextCursor !== null && nextCursor < claim.cursor))
        throw new DeletionDenied();
      await db.runTransaction(async tx => {
        const row = state(await tx.get(ref));
        const time = checkedClock(now,row);
        if (row.leaseId !== claim.leaseId || row.cursor !== claim.cursor ||
            row.observedAtMs !== claim.observedAtMs || row.leaseUntilMs !== claim.leaseUntilMs ||
            time >= row.leaseUntilMs || time-row.observedAtMs >= workerDeadlineMs)
          throw new DeletionDenied();
        tx.set(ref,{cursor:nextCursor,leaseId:null,leaseUntilMs:0,observedAtMs:time});
      });
    },
  });
}

function batchResult(result,after) {
  if (!result || !cursorValid(result.nextCursor)) throw new DeletionDenied();
  const counts = ['attempted','progressed','pending','retry'];
  if (counts.some(key=>!Number.isSafeInteger(result[key]) || result[key]<0 || result[key]>batchLimit) ||
      result.attempted !== result.progressed+result.pending+result.retry ||
      (result.attempted===0 && result.nextCursor!==null && result.nextCursor!==after) ||
      (result.attempted>0 && result.nextCursor!==null && after!==null && result.nextCursor<=after))
    throw new DeletionDenied();
  return result;
}

/**
 * Single scheduled invocation, not a timer/listener or public function.
 * The caller must provide a separately verified hard process deadline. A Promise
 * timeout does not cancel an Admin SDK deletion, so this module never races and
 * releases a lease while an SDK call may still be running.
 */
export function createDeletionScheduledTick({store,runTrustedBatch,workerDeadlineMs,now=Date.now} = {}) {
  if (typeof store?.claim !== 'function' || typeof store?.commit !== 'function' ||
      typeof runTrustedBatch !== 'function' || !validDeadline(workerDeadlineMs) ||
      typeof now !== 'function') throw new DeletionDenied();
  return async function runScheduledTick(...args) {
    if (args.length !== 0) throw new DeletionDenied();
    const claim = await store.claim();
    if (claim === null) return {status:'BUSY'};
    const checkTime = () => {
      const time = checkedClock(now,claim);
      if (time >= claim.leaseUntilMs || time-claim.observedAtMs >= workerDeadlineMs)
        throw new DeletionDenied();
    };
    try {
      checkTime();
      const result = batchResult(await runTrustedBatch({
        after:claim.cursor,limit:batchLimit,budgetMs:batchBudgetMs,
      }),claim.cursor);
      checkTime();
      await store.commit(claim,result.nextCursor,workerDeadlineMs);
      // Aggregate only; never return the internal cursor, lease, UID or ticket.
      return {status:'CHECKPOINTED',attempted:result.attempted,progressed:result.progressed,
        pending:result.pending,retry:result.retry};
    } catch (_) {
      // The previous cursor remains on a pre-commit failure. If commit response
      // was lost, only its durable transaction determines the next invocation.
      // Neither case constitutes proof that any account deletion completed.
      return {status:'UNCERTAIN'};
    }
  };
}

/** Trusted dev bootstrap only; no actual scheduler registration or network call. */
export async function prepareDevDeletionScheduler({
  app,readiness,retentionAudit,workerDeadlineMs,now=Date.now,
} = {}) {
  if (app?.options?.projectId !== 'petworld-dev' ||
      !['petworld-dev.firebasestorage.app','petworld-dev.appspot.com'].includes(app?.options?.storageBucket) ||
      readiness?.approvedDevDeletionTest !== true || readiness?.schedulerVerified !== true ||
      readiness?.schedulerStoreVerified !== true || readiness?.workerIdentityVerified !== true ||
      readiness?.workerHardDeadlineVerified !== true || readiness?.workerClockVerified !== true ||
      !validDeadline(workerDeadlineMs) || typeof retentionAudit !== 'function') throw new DeletionDenied();
  const runTrustedBatch=await prepareDeletionWorkerRuntime({app,readiness,retentionAudit,now});
  const {getFirestore}=await import('firebase-admin/firestore');
  return createDeletionScheduledTick({
    store:createDeletionSchedulerStore({db:getFirestore(app),now}),runTrustedBatch,workerDeadlineMs,now,
  });
}
