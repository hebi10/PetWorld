import {createHash} from 'node:crypto';
import {DeletionDenied} from './account-deletion.mjs';

const routes = new Set(['request','status']);
const windowMs = 60_000;
const appIdPattern = /^1:[0-9]+:android:[a-f0-9]+$/;
const validLimit = value => Number.isSafeInteger(value) && value >= 1 && value <= 1000;

function reviewedApps(appIds) {
  if (!Array.isArray(appIds) || appIds.length < 1 || appIds.length > 10 ||
      !appIds.every(id => typeof id === 'string' && appIdPattern.test(id)) ||
      new Set(appIds).size !== appIds.length) throw new DeletionDenied();
  return new Set(appIds);
}

/**
 * A durable, bounded per-app and per-route admission quota.
 * Document IDs are deterministic; no UID, IP, token or ticket is stored.
 * A shared Firestore transaction serializes concurrent server instances.
 * An outage or invalid stored counter is denial, not an in-memory fallback.
 * This is not a replacement for pre-verification ingress abuse controls.
 */
export function createDeletionRateLimiter({db,appIds,requestLimit,statusLimit,now=Date.now} = {}) {
  const allowed = reviewedApps(appIds);
  if (typeof db?.runTransaction !== 'function' || typeof db?.collection !== 'function' ||
      !validLimit(requestLimit) || !validLimit(statusLimit) || typeof now !== 'function')
    throw new DeletionDenied();
  const limits = {request:requestLimit,status:statusLimit};
  return async ({appId,route} = {}) => {
    if (!allowed.has(appId) || !routes.has(route)) throw new DeletionDenied();
    const key = createHash('sha256').update(appId + ':' + route).digest('hex');
    const ref = db.collection('accountDeletionAdmission').doc(key);
    return db.runTransaction(async tx => {
      const time = now();
      if (!Number.isSafeInteger(time) || time < 0) throw new DeletionDenied();
      const snap = await tx.get(ref);
      let start = time, count = 0;
      if (snap.exists) {
        const row = snap.data();
        if (!row || !Number.isSafeInteger(row.windowStartMs) || row.windowStartMs < 0 ||
            row.windowStartMs > time || !Number.isSafeInteger(row.count) ||
            row.count < 1 || row.count > 1000) throw new DeletionDenied();
        if (time - row.windowStartMs < windowMs) {
          start = row.windowStartMs;
          count = row.count;
        }
      }
      if (count >= limits[route]) return false;
      tx.set(ref,{windowStartMs:start,count:count+1});
      return true;
    });
  };
}

/** Verify the Firebase-issued App Check token, not client claims or its presence. */
export function createDevDeletionAdmission({appCheck,appIds,take} = {}) {
  const allowed = reviewedApps(appIds);
  if (typeof appCheck?.verifyToken !== 'function' || typeof take !== 'function')
    throw new DeletionDenied();
  return async ({route,appCheckToken,ingress} = {}) => {
    if (!routes.has(route) || ingress?.directTls !== true ||
        typeof appCheckToken !== 'string' || !appCheckToken || appCheckToken.length > 8192)
      return 'DENY';
    try {
      const verified = await appCheck.verifyToken(appCheckToken);
      if (!allowed.has(verified?.appId)) return 'DENY';
      const admitted = await take({appId:verified.appId,route});
      return admitted === true ? 'ALLOW' : admitted === false ? 'RATE_LIMITED' : 'DENY';
    } catch (_) { return 'DENY'; }
  };
}
