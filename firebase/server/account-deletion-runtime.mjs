import { deletionJobId, DeletionDenied } from './account-deletion.mjs';
import { createFirebaseDeletionService } from './account-deletion-service.mjs';
import { createAccountDeletionHttpHandler } from './account-deletion-http.mjs';

const PHASE_ORDER = ['LOCKED','AUTH_REVOKED','RECORDS_PURGED','PHOTOS_PURGED','AUTH_DELETED','VERIFIED','COMPLETE'];
const PHASES = new Set(PHASE_ORDER.slice(0,-1));
const knownReceipt = phase => PHASE_ORDER.includes(phase);
const jobKey = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);

/**
 * A bounded, trusted scheduled-worker entry contract. The source must only read
 * durable Admin-owned jobs. No public HTTP path accepts UID, job ID or a cursor
 * for advancing deletion. Existing per-UID lock/lease and CAS authorize each step.
 * Schedule continuation independently of the HTTP response, including lost responses.
 */
export function createDeletionBatchRunner({service, readPage, now = Date.now} = {}) {
  if (typeof service?.advanceTrusted !== 'function' || typeof readPage !== 'function')
    throw new DeletionDenied();
  return async function runBatch({after = null, limit = 10, budgetMs = 60000} = {}) {
    if ((after !== null && !jobKey(after)) || !Number.isInteger(limit) || limit < 1 || limit > 25 ||
        !Number.isInteger(budgetMs) || budgetMs < 1000 || budgetMs > 60000) throw new DeletionDenied();
    const deadline = now() + budgetMs;
    const page = await readPage({after,limit});
    if (!Array.isArray(page) || page.length > limit) throw new DeletionDenied();
    // Validate the full page before advancing any job.
    let previous = after;
    for (const entry of page) {
      if (!entry || !jobKey(entry.jobId) || (previous !== null && entry.jobId <= previous) ||
          deletionJobId(entry.uid) !== entry.jobId || !PHASES.has(entry.phase)) throw new DeletionDenied();
      previous = entry.jobId;
    }
    let attempted=0, progressed=0, pending=0, retry=0, cursor=after;
    for (const entry of page) {
      if (now() >= deadline) break;
      attempted++;
      try {
        const result = await service.advanceTrusted({uid:entry.uid,jobId:entry.jobId});
        if (result?.jobId !== entry.jobId || !knownReceipt(result?.phase) ||
            PHASE_ORDER.indexOf(result.phase) < PHASE_ORDER.indexOf(entry.phase)) throw new DeletionDenied();
        if (result.pendingVerification === true || result.phase === entry.phase) pending++;
        else progressed++;
      } catch (_) { retry++; }
      cursor=entry.jobId;
    }
    return {attempted,progressed,pending,retry,
      // Null indicates a full pass. Next scheduled invocation restarts at the beginning,
      // allowing retained/failed jobs another chance without starving later IDs.
      nextCursor: attempted < page.length || page.length === limit ? cursor : null};
  };
}

/** Shared server-only gate; never derive readiness from a request or event. */
function requireRuntimeReadiness(readiness) {
  if (readiness?.httpAdmissionVerified !== true || readiness?.schedulerVerified !== true ||
      readiness?.sdkCachePolicyVerified !== true) throw new DeletionDenied();
}

async function prepareTrustedBatch({app,service,now}) {
  const {getFirestore,FieldPath}=await import('firebase-admin/firestore');
  const db=getFirestore(app);
  const readPage=async ({after,limit})=>{
    let query=db.collection('accountDeletionJobs').where('phase','in',[...PHASES])
      .orderBy(FieldPath.documentId()).limit(limit);
    if(after!==null) query=query.startAfter(after);
    const result=await query.get();
    return result.docs.map(doc=>{
      const row=doc.data();
      if(row.jobId!==doc.id) throw new DeletionDenied();
      return {uid:row.uid,jobId:row.jobId,phase:row.phase};
    });
  };
  return createDeletionBatchRunner({service,readPage,now});
}

/** No listener, schedule registration or environment-variable activation. */
export async function prepareDeletionRuntime({app,readiness,retentionAudit,admit,now=Date.now} = {}) {
  requireRuntimeReadiness(readiness);
  if (typeof admit !== 'function') throw new DeletionDenied();
  const service=await createFirebaseDeletionService({app,readiness,retentionAudit,now});
  return Object.freeze({
    handleHttp:createAccountDeletionHttpHandler({service,admit}),
    runTrustedBatch:await prepareTrustedBatch({app,service,now}),
  });
}

/** Separate private worker initialization: no HTTP server, TLS key or public route. */
export async function prepareDeletionWorkerRuntime({app,readiness,retentionAudit,now=Date.now} = {}) {
  requireRuntimeReadiness(readiness);
  const service=await createFirebaseDeletionService({app,readiness,retentionAudit,now});
  return prepareTrustedBatch({app,service,now});
}
