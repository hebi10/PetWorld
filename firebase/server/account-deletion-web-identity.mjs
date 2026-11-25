import {createHash,randomBytes} from 'node:crypto';
import {DeletionDenied,deletionJobId} from './account-deletion.mjs';
import {createAdminDeletionPort,statusTicketDigest} from './account-deletion-admin.mjs';

const ttlMs=5*60_000;
const freshSeconds=300;
const purposes=new Set(['identify','submit']);
const hex=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const nonceValid=value=>typeof value==='string'&&/^[A-Za-z0-9_-]{43}$/.test(value)&&
  Buffer.from(value,'base64url').toString('base64url')===value;
const clientValid=value=>typeof value==='string'&&/^[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$/.test(value);
const digest=value=>createHash('sha256').update(value,'utf8').digest('hex');
const safeTime=value=>Number.isSafeInteger(value)&&value>=0&&value<=Number.MAX_SAFE_INTEGER-ttlMs;
const validPurpose=(purpose,jobId)=>purposes.has(purpose)&&
  (purpose==='identify'?jobId===null:hex(jobId));
const millis=value=>value instanceof Date?value.getTime():
  typeof value?.toMillis==='function'?value.toMillis():NaN;

/**
 * One-time server-generated OAuth nonce. No credentials or raw nonce stored.
 * Firestore TTL on expiresAt is a separate activation prerequisite; expiration
 * is also checked synchronously in the consuming transaction.
 */
export function createWebDeletionChallenges({db,now=Date.now}={}) {
  if(typeof db?.collection!=='function'||typeof db?.runTransaction!=='function'||
    typeof now!=='function')throw new DeletionDenied();
  const collection=db.collection('accountDeletionWebChallenges');
  return Object.freeze({
    async issue(purpose,jobId){
      if(!validPurpose(purpose,jobId))throw new DeletionDenied();
      const issuedAtMs=now();
      if(!safeTime(issuedAtMs))throw new DeletionDenied();
      const nonce=randomBytes(32).toString('base64url');
      await db.runTransaction(async tx=>{
        tx.create(collection.doc(digest(nonce)),{
          purpose,jobId,issuedAtMs,expiresAt:new Date(issuedAtMs+ttlMs),
        });
      });
      return {nonce};
    },
    async consume(nonce,purpose,jobId,tokenIssuedAt){
      if(!nonceValid(nonce)||!validPurpose(purpose,jobId)||
        !Number.isSafeInteger(tokenIssuedAt))throw new DeletionDenied();
      return db.runTransaction(async tx=>{
        const ref=collection.doc(digest(nonce)),snap=await tx.get(ref),row=snap.data(),time=now();
        if(!snap.exists||!row||row.purpose!==purpose||row.jobId!==jobId||
          !safeTime(time)||!safeTime(row.issuedAtMs)||time<row.issuedAtMs||
          millis(row.expiresAt)!==row.issuedAtMs+ttlMs||time>=row.issuedAtMs+ttlMs||
          tokenIssuedAt<Math.floor(row.issuedAtMs/1000))throw new DeletionDenied();
        tx.delete(ref);
        return true;
      });
    },
  });
}

/**
 * A Google ID token is not a Firebase ID token. Verify it separately, resolve
 * the existing linked provider subject, and NEVER sign in, create, link, or
 * mint Firebase credentials. The returned UID is strictly server-internal.
 */
export function createExistingGoogleDeletionIdentity({verify,auth,challenges,clientId,now=Date.now}={}) {
  if(typeof verify!=='function'||typeof auth?.getUserByProviderUid!=='function'||
    typeof challenges?.consume!=='function'||!clientValid(clientId)||typeof now!=='function')
    throw new DeletionDenied();
  return async function existingIdentity({idToken,purpose,expectedJobId=null}={}) {
    if(typeof idToken!=='string'||idToken.length>8185||
      !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(idToken)||
      !validPurpose(purpose,expectedJobId))throw new DeletionDenied();
    const claims=await verify(idToken,clientId);
    const time=now(),seconds=Math.floor(time/1000);
    if(!safeTime(time)||!claims||!['accounts.google.com','https://accounts.google.com'].includes(claims.iss)||
      claims.aud!==clientId||(claims.azp!==undefined&&claims.azp!==clientId)||
      typeof claims.sub!=='string'||!/^[A-Za-z0-9_-]{1,255}$/.test(claims.sub)||
      !nonceValid(claims.nonce)||!Number.isSafeInteger(claims.iat)||
      !Number.isSafeInteger(claims.exp)||claims.iat>seconds||seconds-claims.iat>freshSeconds||
      claims.exp<=seconds||claims.exp<=claims.iat)throw new DeletionDenied();
    // Issuing a new token is not equivalent to a recent Google authentication.
    // Require the independently verified auth_time claim for destructive submit.
    if(purpose==='submit'&&(!Number.isSafeInteger(claims.auth_time)||
      claims.auth_time>claims.iat||seconds-claims.auth_time>freshSeconds))throw new DeletionDenied();
    if(await challenges.consume(claims.nonce,purpose,expectedJobId,claims.iat)!==true)
      throw new DeletionDenied();
    const user=await auth.getUserByProviderUid('google.com',claims.sub).catch(()=>{throw new DeletionDenied();});
    if(!user||user.disabled!==false||user.tenantId!=null||
      !Array.isArray(user.providerData)||!user.providerData.some(p=>p.providerId==='google.com'&&p.uid===claims.sub))
      throw new DeletionDenied();
    const jobId=deletionJobId(user.uid);
    if(purpose==='submit'){
      if(jobId!==expectedJobId)throw new DeletionDenied();
      // Honor explicit Firebase credential revocations where available. An
      // absent value has no revocation evidence; malformed values fail closed.
      if(user.tokensValidAfterTime!=null){
        const validAfter=Date.parse(user.tokensValidAfterTime);
        if(!Number.isFinite(validAfter)||claims.auth_time<Math.floor(validAfter/1000))throw new DeletionDenied();
      }
    }
    return Object.freeze({uid:user.uid,jobId});
  };
}

export function createExistingGoogleWebDeletionService({db,auth,bucket,retentionAudit,
  verify,clientId,now=Date.now}={}) {
  const challenges=createWebDeletionChallenges({db,now});
  const existing=createExistingGoogleDeletionIdentity({verify,auth,challenges,clientId,now});
  return Object.freeze({
    challenge({purpose,jobId}){return challenges.issue(purpose,jobId);},
    async identify(idToken){
      const identity=await existing({idToken,purpose:'identify'});
      return {jobId:identity.jobId};
    },
    async submit(idToken,{jobId,confirmation,statusTicket}){
      if(confirmation!=='DELETE_MY_PETWORLD_ACCOUNT'||!hex(jobId))throw new DeletionDenied();
      const ticketDigest=statusTicketDigest(statusTicket);
      const identity=await existing({idToken,purpose:'submit',expectedJobId:jobId});
      // Existing Admin lock/CAS/worker contract, with a separately authorized
      // existing Google provider identity. No fabricated Firebase ID claims.
      const port=createAdminDeletionPort({db,auth,bucket,ticketDigest,retentionAudit,now});
      const receipt=await port.requestLock(identity.uid,identity.jobId);
      if(receipt?.jobId!==jobId)throw new DeletionDenied();
      return receipt;
    },
  });
}

/** Only a trusted dev runtime with reviewed GIS, TTL and existing-account proof. */
export async function prepareExistingGoogleWebDeletion({app,readiness,clientId,retentionAudit,now=Date.now}={}) {
  if(app?.options?.projectId!=='petworld-dev'||
    !['petworld-dev.firebasestorage.app','petworld-dev.appspot.com'].includes(app?.options?.storageBucket)||
    !clientValid(clientId)||typeof retentionAudit!=='function'||
    ['approvedDevDeletionTest','rulesLockVerified','iamReviewed','retentionReviewed',
      'webDeletionVerified','webExistingAccountSignInVerified','webSameOriginVerified','webRecoveryVerified',
      'webGoogleIdTokenVerified','webGoogleAuthTimeVerified','webChallengeTtlVerified']
      .some(flag=>readiness?.[flag]!==true))throw new DeletionDenied();
  const [{OAuth2Client},{getAuth},{getFirestore},{getStorage}]=await Promise.all([
    import('google-auth-library'),import('firebase-admin/auth'),import('firebase-admin/firestore'),import('firebase-admin/storage'),
  ]);
  const google=new OAuth2Client();
  const verify=async(idToken,audience)=>(await google.verifyIdToken({idToken,audience})).getPayload();
  return createExistingGoogleWebDeletionService({db:getFirestore(app),auth:getAuth(app),
    bucket:getStorage(app).bucket(app.options.storageBucket),retentionAudit,verify,clientId,now});
}
