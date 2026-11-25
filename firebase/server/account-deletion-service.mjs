import { requestAccountDeletion, advanceAccountDeletion, DeletionDenied } from './account-deletion.mjs';
import { createAdminDeletionPort, statusTicketDigest } from './account-deletion-admin.mjs';

/** Standalone module, deliberately not registered in firebase.json or deployed.
 * The caller supplies a trusted initialized Admin App and server-only readiness evidence.
 * Never accept configuration, UID, readiness flags, or worker requests from the HTTP client.
 */
export async function createFirebaseDeletionService({app, readiness, retentionAudit,
    now = Date.now}) {
  if (!app || app.options?.projectId !== 'petworld-dev' ||
      !['petworld-dev.firebasestorage.app','petworld-dev.appspot.com'].includes(app.options?.storageBucket) ||
      readiness?.approvedDevDeletionTest !== true || readiness?.rulesLockVerified !== true ||
      readiness?.retentionReviewed !== true || readiness?.iamReviewed !== true ||
      typeof retentionAudit !== 'function') throw new DeletionDenied();
  const [{getAuth},{getFirestore},{getStorage}] = await Promise.all([
    import('firebase-admin/auth'),import('firebase-admin/firestore'),import('firebase-admin/storage'),
  ]);
  const auth = getAuth(app), db = getFirestore(app), bucket = getStorage(app).bucket(app.options.storageBucket);
  return createDeletionHandlers({db,auth,bucket,retentionAudit,now});
}

function exactBody(body, keys) {
  if (!body || typeof body !== 'object' || Array.isArray(body) ||
      Object.keys(body).sort().join(',') !== [...keys].sort().join(',')) throw new DeletionDenied();
}
function bearer(value) {
  if (typeof value !== 'string' || value.length > 8192 || !/^Bearer [A-Za-z0-9._-]+$/.test(value)) throw new DeletionDenied();
  return value.slice(7);
}
export function createDeletionHandlers({db,auth,bucket,retentionAudit,now=Date.now}) {
  const port = digest => createAdminDeletionPort({db,auth,bucket,ticketDigest:digest,retentionAudit,now});
  // POST-only, no UID from body, no token values returned or logged.
  async function request({method,authorization,body}) {
    if (method !== 'POST') throw new DeletionDenied();
    exactBody(body,['confirmation','statusTicket']);
    const claims = await auth.verifyIdToken(bearer(authorization),true);
    const digest = statusTicketDigest(body.statusTicket);
    return requestAccountDeletion(port(digest),claims,body.confirmation,Math.floor(now()/1000));
  }
  // A random 256-bit client-held capability permits minimal status after Auth deletion.
  // Caller must transport over HTTPS, never put tickets in URLs, logs or telemetry.
  async function status({method,authorization,body}) {
    if (method !== 'POST') throw new DeletionDenied();
    exactBody(body,['jobId']);
    return port(null).readStatus(body.jobId,bearer(authorization));
  }
  // INTERNAL ONLY: trusted task runner, not an exported HTTP route.
  async function advanceTrusted({uid,jobId}) {
    return advanceAccountDeletion(port(null),uid,jobId);
  }
  return Object.freeze({request,status,advanceTrusted});
}
