import {createServer} from 'node:https';
import {DeletionDenied} from './account-deletion.mjs';
import {prepareDeletionRuntime} from './account-deletion-runtime.mjs';
import {prepareExistingGoogleWebDeletion} from './account-deletion-web-identity.mjs';
import {WEB_AUTH_PATH,createWebExistingAuthHandler} from './account-deletion-web-http.mjs';
import {createDeletionRateLimiter,createDevDeletionAdmission} from './account-deletion-admission.mjs';

const paths = new Set(['/account-deletion/request','/account-deletion/status']);
const maxBody = 2048;
const singleHeaders = new Set([
  'host','authorization','x-firebase-appcheck','content-type','content-length',
  'content-encoding','transfer-encoding','expect','origin',
]);
const securityHeaders = Object.freeze({
  'Content-Type':'application/json; charset=utf-8',
  'Cache-Control':'no-store, private, max-age=0',
  'Pragma':'no-cache',
  'X-Content-Type-Options':'nosniff',
  'Referrer-Policy':'no-referrer',
  'Connection':'close',
});

function reviewedHost(origin) {
  if (typeof origin !== 'string') throw new DeletionDenied();
  let parsed;
  try { parsed = new URL(origin); } catch (_) { throw new DeletionDenied(); }
  const host = parsed.hostname;
  if (parsed.protocol !== 'https:' || parsed.origin !== origin ||
      !/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(host) ||
      host.length > 253 || host.endsWith('.localhost') || host.endsWith('.local') ||
      parsed.port || parsed.username || parsed.password) throw new DeletionDenied();
  return host;
}

function readHeaders(raw) {
  if (!Array.isArray(raw) || raw.length % 2 !== 0) return null;
  const headers = Object.create(null);
  for (let i=0;i<raw.length;i+=2) {
    const key = raw[i].toLowerCase();
    if (!singleHeaders.has(key)) continue;
    if (Object.hasOwn(headers,key)) return null;
    headers[key] = raw[i+1];
  }
  return headers;
}

function reply(res,statusCode,body,extra={}) {
  const bytes = Buffer.from(JSON.stringify(body));
  res.writeHead(statusCode,{...extra,...securityHeaders,'Content-Length':bytes.length});
  res.end(bytes);
}

function reject(res,statusCode,code,extra) {
  reply(res,statusCode,{code},extra);
}

/**
 * Direct TLS only. No forwarded header is trusted, no request URL normalization,
 * no public worker route, no chunked/compressed body, no credential logging.
 * Fixed-length requests match the Android HTTPS adapter.
 */
export function createDeletionTlsListener({origin,handleHttp,webAuthEnabled=false} = {}) {
  const host = reviewedHost(origin);
  if (typeof handleHttp !== 'function') throw new DeletionDenied();
  return async (req,res) => {
    let rawBody;
    try {
      if (req.socket?.encrypted !== true) return reject(res,403,'DENIED');
      if (!paths.has(req.url) && !(webAuthEnabled===true&&req.url===WEB_AUTH_PATH)) return reject(res,404,'NOT_FOUND');
      if (req.method !== 'POST') return reject(res,405,'METHOD_NOT_ALLOWED',{Allow:'POST'});
      const headers = readHeaders(req.rawHeaders);
      if (!headers || (headers.host !== host && headers.host !== host+':443') ||
          headers['transfer-encoding'] !== undefined || headers.expect !== undefined ||
          (headers['content-encoding'] !== undefined && headers['content-encoding'] !== 'identity'))
        return reject(res,400,'INVALID_REQUEST');
      const length = headers['content-length'];
      if (typeof length !== 'string' || !/^[0-9]{1,10}$/.test(length))
        return reject(res,411,'LENGTH_REQUIRED');
      const size = Number(length);
      if (size > maxBody) return reject(res,413,'BODY_TOO_LARGE');
      if (size < 1) return reject(res,400,'INVALID_BODY');

      rawBody = Buffer.alloc(size);
      let offset = 0;
      for await (const chunk of req.iterator({destroyOnReturn:false})) {
        if (!Buffer.isBuffer(chunk) || offset + chunk.length > size)
          return reject(res,413,'BODY_TOO_LARGE');
        chunk.copy(rawBody,offset);
        offset += chunk.length;
      }
      if (offset !== size || !req.complete) return reject(res,400,'INVALID_BODY');

      const result = await handleHttp({
        path:req.url,method:req.method,contentType:headers['content-type'],
        authorization:headers.authorization,appCheckToken:headers['x-firebase-appcheck'],
        ingress:Object.freeze({directTls:true,origin:headers.origin}),rawBody,
      });
      if (!res.destroyed) reply(res,result.statusCode,result.body,result.headers);
    } catch (_) {
      if (!res.destroyed && !res.headersSent) reject(res,503,'RESULT_UNKNOWN');
      else if (!res.destroyed) res.destroy();
    } finally {
      rawBody?.fill(0);
    }
  };
}

/**
 * Build an unbound, dev-only HTTPS server. Never call listen here. The caller
 * must supply reviewed TLS credentials, App IDs, quotas and all existing
 * deletion runtime evidence. No environment flag grants readiness.
 * Scheduled worker registration stays internal and is a separate step.
 */
export async function prepareDevDeletionHttpsServer({
  app,readiness,retentionAudit,origin,appIds,webAppIds=[],webGoogleClientId,requestLimit,statusLimit,tls,now=Date.now,
} = {}) {
  reviewedHost(origin);
  if (app?.options?.projectId !== 'petworld-dev' ||
      !['petworld-dev.firebasestorage.app','petworld-dev.appspot.com'].includes(app?.options?.storageBucket) ||
      readiness?.approvedDevDeletionTest !== true || readiness?.rulesLockVerified !== true ||
      readiness?.retentionReviewed !== true || readiness?.iamReviewed !== true ||
      readiness?.httpAdmissionVerified !== true || readiness?.schedulerVerified !== true ||
      readiness?.sdkCachePolicyVerified !== true || readiness?.httpsOriginVerified !== true ||
      readiness?.appCheckAllowlistVerified !== true || readiness?.rateLimitStoreVerified !== true ||
      readiness?.ingressAbuseLimitsVerified !== true || typeof retentionAudit !== 'function' ||
      !tls?.key || !tls?.cert) throw new DeletionDenied();
  if (!Array.isArray(webAppIds) || (webAppIds.length && (
      readiness?.webDeletionVerified !== true || readiness?.webExistingAccountSignInVerified !== true ||
      readiness?.webSameOriginVerified !== true || readiness?.webRecoveryVerified !== true))) throw new DeletionDenied();
  const [{getAppCheck},{getFirestore}] = await Promise.all([
    import('firebase-admin/app-check'),import('firebase-admin/firestore'),
  ]);
  const take = createDeletionRateLimiter({db:getFirestore(app),appIds,webAppIds,requestLimit,statusLimit,now});
  const admit = createDevDeletionAdmission({appCheck:getAppCheck(app),appIds,webAppIds,webOrigin:origin,take});
  const runtime = await prepareDeletionRuntime({app,readiness,retentionAudit,admit,now});
  const webService=webAppIds.length?await prepareExistingGoogleWebDeletion({
    app,readiness,clientId:webGoogleClientId,retentionAudit,now,
  }):null;
  const handleHttp=webService?createWebExistingAuthHandler({service:webService,admit,
    fallback:runtime.handleHttp}):runtime.handleHttp;
  const listener = createDeletionTlsListener({origin,handleHttp,webAuthEnabled:!!webService});
  const server = createServer({
    key:tls.key,cert:tls.cert,minVersion:'TLSv1.2',
    maxHeaderSize:24*1024,headersTimeout:5000,requestTimeout:10000,
    connectionsCheckingInterval:1000,keepAliveTimeout:1000,
  },listener);
  server.maxRequestsPerSocket = 1;
  server.setTimeout(15000,socket=>socket.destroy());
  server.on('checkContinue',listener);
  return Object.freeze({server,runTrustedBatch:runtime.runTrustedBatch});
}
