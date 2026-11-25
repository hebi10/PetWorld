import { TextDecoder } from 'node:util';
import { DeletionDenied } from './account-deletion.mjs';

const REQUEST = '/account-deletion/request';
const STATUS = '/account-deletion/status';
const PHASES = new Set(['LOCKED','AUTH_REVOKED','RECORDS_PURGED','PHOTOS_PURGED','AUTH_DELETED','VERIFIED','COMPLETE']);
const HEADERS = Object.freeze({
  'Content-Type':'application/json; charset=utf-8',
  'Cache-Control':'no-store, private, max-age=0',
  'Pragma':'no-cache',
  'X-Content-Type-Options':'nosniff',
  'Referrer-Policy':'no-referrer',
});
const respond = (statusCode, body) => ({statusCode,headers:{...HEADERS},body});
const error = (statusCode,code) => respond(statusCode,{code});
const isJobId = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const validAuth = v => typeof v === 'string' && v.length <= 8192 && /^Bearer [A-Za-z0-9._-]+$/.test(v);

function parseBody(rawBody) {
  if (!Buffer.isBuffer(rawBody) || rawBody.length === 0 || rawBody.length > 2048) return null;
  try {
    const data = JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(rawBody));
    return data && typeof data === 'object' && !Array.isArray(data) ? data : null;
  } catch (_) { return null; }
}

function exactKeys(body,keys) {
  return Object.keys(body).sort().join(',') === [...keys].sort().join(',');
}

/**
 * Framework-neutral HTTP boundary, not a deployed listener. Only trusted bootstrap
 * may supply the already verified dev service and admission adapter. The ingress
 * must enforce TLS, request-size limits, verified App Check and durable abuse limits.
 * Admission is server-derived, never a body/URL flag. Missing adapters fail closed.
 * No CORS, no public worker route, no credential/error logging.
 */
export function createAccountDeletionHttpHandler({service, admit} = {}) {
  if (!service || typeof service.request !== 'function' || typeof service.status !== 'function' ||
      typeof admit !== 'function') {
    return async () => error(503,'UNAVAILABLE');
  }
  return async function handle(input) {
    if (!input || ![REQUEST,STATUS].includes(input.path)) return error(404,'NOT_FOUND');
    if (input.method !== 'POST') return {...error(405,'METHOD_NOT_ALLOWED'),headers:{...HEADERS,Allow:'POST'}};
    if (typeof input.contentType !== 'string' ||
        !/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(input.contentType)) return error(415,'JSON_REQUIRED');
    if (!validAuth(input.authorization)) return error(401,'AUTHORIZATION_REQUIRED');
    if (!Buffer.isBuffer(input.rawBody)) return error(400,'INVALID_BODY');
    if (input.rawBody.length > 2048) return error(413,'BODY_TOO_LARGE');
    const body = parseBody(input.rawBody);
    const isRequest = input.path === REQUEST;
    if (!body || !(isRequest ? exactKeys(body,['confirmation','statusTicket']) : exactKeys(body,['jobId'])))
      return error(400,'INVALID_BODY');
    if (isRequest && (body.confirmation !== 'DELETE_MY_PETWORLD_ACCOUNT' ||
        typeof body.statusTicket !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(body.statusTicket)))
      return error(400,'INVALID_BODY');
    if (!isRequest && !isJobId(body.jobId)) return error(400,'INVALID_BODY');
    if (typeof input.appCheckToken !== 'string' || input.appCheckToken.length === 0 ||
        input.appCheckToken.length > 8192) return error(403,'DENIED');

    try {
      // ingress is opaque trusted server context. A real adapter must verify it;
      // neither this module nor a client-provided value is sufficient evidence.
      const admission = await admit({
        route:isRequest?'request':'status',appCheckToken:input.appCheckToken,ingress:input.ingress,
      });
      if (admission === 'RATE_LIMITED') return error(429,'RATE_LIMITED');
      if (admission !== 'ALLOW') return error(403,'DENIED');
      const args = {method:'POST',authorization:input.authorization,body};
      const result = isRequest ? await service.request(args) : await service.status(args);
      if (!isJobId(result?.jobId) || !PHASES.has(result?.phase) ||
          (!isRequest && result.jobId !== body.jobId)) return error(503,'RESULT_UNKNOWN');
      // Return only the server-confirmed receipt; never UID, token, ticket, stack or job internals.
      return respond(isRequest?202:200,{jobId:result.jobId,phase:result.phase});
    } catch (failure) {
      if (failure instanceof DeletionDenied) return error(403,'DENIED');
      // Lock might have committed before a response was lost. Do not assert rejection,
      // success, or suggest a new status ticket.
      return error(503,'RESULT_UNKNOWN');
    }
  };
}
