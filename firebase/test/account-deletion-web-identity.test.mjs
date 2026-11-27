import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,generateKeyPairSync,sign} from 'node:crypto';
import {OAuth2Client} from 'google-auth-library';
import {createWebDeletionChallenges,createExistingGoogleDeletionIdentity,
  createExistingGoogleWebDeletionService,prepareExistingGoogleWebDeletion} from '../server/account-deletion-web-identity.mjs';
import {createWebExistingAuthHandler,WEB_AUTH_PATH} from '../server/account-deletion-web-http.mjs';
import {createDevDeletionAdmission,createDeletionRateLimiter} from '../server/account-deletion-admission.mjs';
import {createExistingGoogleWebTransport} from '../web/account-deletion-client.mjs';
import {createExistingGoogleWebRuntime} from '../web/account-deletion-google.mjs';
import {DeletionDenied,deletionJobId} from '../server/account-deletion.mjs';

const clientId='123456-fixture.apps.googleusercontent.com';
const uid='qa-web-alice',subject='google-subject-a',jobId=deletionJobId(uid);
const otherJob=deletionJobId('qa-web-other');
const at=1_795_000_000_000;
const token='synthetic.header.signature';
const ticket=()=>randomBytes(32).toString('base64url');
const confirmation='DELETE_MY_PETWORLD_ACCOUNT';
const origin='https://dev.example.invalid';
function fakeDb(){
  const rows=new Map();let queue=Promise.resolve();
  const db={
    doc:path=>({path,get:async()=>snapshot(path)}),
    collection:name=>({doc:id=>db.doc(name+'/'+id)}),
    runTransaction(action){
      const run=queue.then(async()=>{
        const changes=[];
        const result=await action({get:async ref=>snapshot(ref.path),
          create:(ref,value)=>{assert(!rows.has(ref.path));changes.push([ref.path,value]);},
          delete:ref=>changes.push([ref.path,undefined]),set:(ref,value)=>changes.push([ref.path,value])});
        for(const [path,value] of changes)value===undefined?rows.delete(path):rows.set(path,value);
        return result;
      });
      queue=run.catch(()=>{});return run;
    },
  };
  function snapshot(path){return {exists:rows.has(path),data:()=>rows.get(path)};}
  return {db,rows};
}
function fixture(){
  const {db,rows}=fakeDb();let time=at,claims=null;
  const lookups=[];
  const user={uid,disabled:false,providerData:[{providerId:'google.com',uid:subject}],
    tokensValidAfterTime:new Date(at-600_000).toISOString()};
  const auth={async getUserByProviderUid(provider,sub){
    lookups.push([provider,sub]);
    if(sub!==subject)throw Object.assign(new Error('fixture not found'),{code:'auth/user-not-found'});
    return user;
  }};
  const now=()=>time,verify=async(id,audience)=>{
    assert.equal(id,token);assert.equal(audience,clientId);return claims;
  };
  const challenges=createWebDeletionChallenges({db,now});
  const identity=createExistingGoogleDeletionIdentity({verify,auth,challenges,clientId,now});
  const service=createExistingGoogleWebDeletionService({db,auth,bucket:{},verify,clientId,now,retentionAudit:async()=>false});
  const fresh=nonce=>({iss:'https://accounts.google.com',aud:clientId,sub:subject,nonce,
    iat:Math.floor(time/1000),exp:Math.floor(time/1000)+3600,auth_time:Math.floor(time/1000)-5});
  return {db,rows,auth,user,lookups,now,challenges,identity,service,fresh,
    time:value=>{time=value;},claims:value=>{claims=value;}};
}

test('web identity: challenge is hashed, purpose-bound, job-bound and consumed once atomically',async()=>{
  const env=fixture();
  const {nonce}=await env.challenges.issue('submit',jobId);
  assert.equal(Buffer.from(nonce,'base64url').length,32);
  assert.equal(env.rows.size,1);
  assert(!JSON.stringify([...env.rows]).includes(nonce));
  await assert.rejects(()=>env.challenges.consume(nonce,'identify',null,at/1000),DeletionDenied);
  await assert.rejects(()=>env.challenges.consume(nonce,'submit',otherJob,at/1000),DeletionDenied);
  await assert.rejects(()=>env.challenges.consume(nonce,'submit',jobId,at/1000-1),DeletionDenied);
  const outcomes=await Promise.allSettled([
    env.challenges.consume(nonce,'submit',jobId,at/1000),
    env.challenges.consume(nonce,'submit',jobId,at/1000),
  ]);
  assert.equal(outcomes.filter(r=>r.status==='fulfilled').length,1);
  assert.equal(env.rows.size,0);
});
test('web identity: expired challenge or backward clock cannot authorize a request',async()=>{
  const env=fixture(),{nonce}=await env.challenges.issue('identify',null);
  env.time(at-1);
  await assert.rejects(()=>env.challenges.consume(nonce,'identify',null,at/1000),DeletionDenied);
  env.time(at+300_000);
  await assert.rejects(()=>env.challenges.consume(nonce,'identify',null,at/1000),DeletionDenied);
});
test('web identity: verified subject maps existing provider only, never email or account creation',async()=>{
  const env=fixture(),{nonce}=await env.challenges.issue('identify',null);
  env.claims({...env.fresh(nonce),email:'different@example.invalid'});
  assert.deepEqual(await env.service.identify(token),{jobId});
  assert.deepEqual(env.lookups,[['google.com',subject]]);
  await assert.rejects(()=>env.service.identify(token),DeletionDenied);
  const missing=await env.challenges.issue('identify',null);
  env.claims({...env.fresh(missing.nonce),sub:'unknown-subject'});
  await assert.rejects(()=>env.service.identify(token),DeletionDenied);
  const disabled=await env.challenges.issue('identify',null);
  env.user.disabled=true;env.claims(env.fresh(disabled.nonce));
  await assert.rejects(()=>env.service.identify(token),DeletionDenied);
  assert(![...env.rows.keys()].some(path=>path.startsWith('users/')));
});
test('web identity: issuer, audience, nonce, expiry and actual fresh auth_time are required',async()=>{
  const env=fixture(),{nonce}=await env.challenges.issue('submit',jobId),good=env.fresh(nonce);
  for(const bad of [
    {...good,iss:'https://other.example.invalid'}, {...good,aud:'wrong'}, {...good,azp:'wrong'},
    {...good,nonce:ticket()}, {...good,exp:at/1000}, {...good,iat:at/1000+1},
    {...good,auth_time:undefined}, {...good,auth_time:at/1000-301}, {...good,auth_time:at/1000+1},
  ]){
    env.claims(bad);
    await assert.rejects(()=>env.service.submit(token,{jobId,confirmation,statusTicket:ticket()}),DeletionDenied);
  }
  assert.equal(env.lookups.length,0);
  assert(![...env.rows.keys()].some(path=>path.startsWith('accountDeletionLocks/')));
});
test('web identity: wrong job and revoked identity cannot create a lock; exact repeat reuses ticket',async()=>{
  const env=fixture();
  async function submit(expected,statusTicket){
    const {nonce}=await env.service.challenge({purpose:'submit',jobId:expected});
    env.claims(env.fresh(nonce));
    return env.service.submit(token,{jobId:expected,confirmation,statusTicket});
  }
  const saved=ticket();
  await assert.rejects(()=>submit(otherJob,saved),DeletionDenied);
  env.user.tokensValidAfterTime=new Date(at).toISOString();
  await assert.rejects(()=>submit(jobId,saved),DeletionDenied);
  env.user.tokensValidAfterTime=new Date(at-600_000).toISOString();
  assert.equal(env.rows.size,0);
  assert.deepEqual(await submit(jobId,saved),{jobId,phase:'LOCKED'});
  assert.deepEqual(await submit(jobId,saved),{jobId,phase:'LOCKED'});
  await assert.rejects(()=>submit(jobId,ticket()),DeletionDenied);
  assert.equal(env.rows.get('accountDeletionLocks/'+uid).jobId,jobId);
  assert(!JSON.stringify([...env.rows]).includes(saved));
});
test('web identity: official Google library verifies signed JWT against fixture key without network',async()=>{
  const env=fixture(),{nonce}=await env.challenges.issue('identify',null);
  const {publicKey,privateKey}=generateKeyPairSync('rsa',{modulusLength:2048});
  const now=Math.floor(Date.now()/1000);
  const payload={...env.fresh(nonce),iat:now,exp:now+300,auth_time:now};
  const part=value=>Buffer.from(JSON.stringify(value)).toString('base64url');
  const signingInput=part({alg:'RS256',kid:'fixture',typ:'JWT'})+'.'+part(payload);
  const signature=sign('RSA-SHA256',Buffer.from(signingInput),privateKey).toString('base64url');
  const jwt=signingInput+'.'+signature;
  const google=new OAuth2Client(),certs={fixture:publicKey.export({type:'spki',format:'pem'})};
  const checked=await google.verifySignedJwtWithCertsAsync(jwt,certs,clientId,['accounts.google.com','https://accounts.google.com']);
  assert.equal(checked.getPayload().sub,subject);
  const changed=part({...payload,sub:'different-subject'});
  await assert.rejects(()=>google.verifySignedJwtWithCertsAsync(
    signingInput.split('.')[0]+'.'+changed+'.'+signature,certs,clientId,['https://accounts.google.com']));
});
test('web identity: HTTP boundary requires web admission, exact bodies and returns no UID',async()=>{
  const env=fixture();let allow=false;
  const handle=createWebExistingAuthHandler({service:env.service,admit:async({route})=>{
    assert.equal(route,'web-auth');return allow?'ALLOW':'DENY';
  },fallback:async()=>({statusCode:404})});
  const call=(body,authorization)=>handle({path:WEB_AUTH_PATH,method:'POST',
    contentType:'application/json',rawBody:Buffer.from(JSON.stringify(body)),authorization,
    appCheckToken:'synthetic',ingress:{directTls:true,origin}});
  const request={action:'challenge',purpose:'identify',jobId:null};
  assert.equal((await call(request)).statusCode,403);assert.equal(env.rows.size,0);
  allow=true;
  assert.equal((await call({...request,uid})).statusCode,400);
  const response=await call(request);
  assert.equal(response.statusCode,200);assert.match(response.headers['Cache-Control'],/no-store/);
  env.claims(env.fresh(response.body.nonce));
  const result=await call({action:'identify'},'Bearer '+token);
  assert.deepEqual(result.body,{jobId});assert.equal(result.statusCode,200);
  assert.equal((await call({action:'identify'},'Bearer '+token)).statusCode,403);
  assert.equal((await call({action:'advance',uid})).statusCode,400);
});
test('web identity: Android cannot use web-auth and browser cannot use Android request quota',async()=>{
  const env=fixture(),android='1:123:android:abc',web='1:123:web:abc';
  const take=createDeletionRateLimiter({db:env.db,appIds:[android],webAppIds:[web],requestLimit:1,statusLimit:2,now:env.now});
  const admit=createDevDeletionAdmission({appIds:[android],webAppIds:[web],webOrigin:origin,take,
    appCheck:{verifyToken:async value=>({appId:value==='android'?android:web})}});
  const input={route:'web-auth',appCheckToken:'web',ingress:{directTls:true,origin}};
  assert.equal(await admit({...input,appCheckToken:'android'}),'DENY');
  assert.equal(await admit({...input,route:'request'}),'DENY');
  assert.equal(await admit(input),'ALLOW');
  assert.equal(await admit(input),'RATE_LIMITED');
  assert.equal(await admit({...input,route:'status'}),'ALLOW');
  assert.equal(await admit({...input,route:'request',appCheckToken:'android'}),'ALLOW');
});
test('web identity: browser challenge/identify uses no Firebase sign-in and request is job-bound',async()=>{
  const calls=[];let nonce,issuedPurpose;
  const fetcher=async(url,options)=>{
    const body=JSON.parse(options.body);calls.push({url,body,authorization:options.headers.Authorization});
    let value,status=200;
    if(body.action==='challenge'){
      nonce=ticket();issuedPurpose=body.purpose;value={nonce};
    }else if(body.action==='identify'){value={jobId};}
    else if(body.action==='submit'){assert.equal(body.jobId,jobId);status=202;value={jobId,phase:'LOCKED'};}
    else value={jobId,phase:'COMPLETE'};
    return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
  };
  const appId='1:123:web:abc',appCheck={app:{options:{projectId:'petworld-dev',appId}}};
  const readiness=Object.fromEntries(['webDeletionVerified','existingAccountSignInVerified','appCheckVerified',
    'sameOriginVerified','recoveryVerified','googleAuthTimeVerified','googleCredentialUiVerified'].map(x=>[x,true]));
  const config={appCheck,sdk:{getToken:async()=>({token:'fixture'})},webAppId:appId,googleClientId:clientId,
    origin,currentOrigin:origin,readiness,fetcher,acquireGoogleCredential:async request=>{
      assert.equal(request.nonce,nonce);assert.equal(request.purpose,issuedPurpose);
      const claims={nonce,aud:clientId,auth_time:Math.floor(Date.now()/1000)};
      return 'header.'+Buffer.from(JSON.stringify(claims)).toString('base64url')+'.signature';
    }};
  assert.equal(createExistingGoogleWebRuntime({...config,readiness:{}}),null);
  const runtime=createExistingGoogleWebRuntime(config);
  assert.equal(await runtime.connectExisting(),true);
  assert.equal(runtime.currentAccount(),jobId);
  assert.equal(calls[0].authorization,undefined);
  const prepared=await runtime.controller.prepare('fixture-recovery-password');
  assert.equal(prepared.kind,'PREPARED');runtime.controller.confirmSaved();
  assert.deepEqual(await runtime.controller.submit(true),{kind:'CONFIRMED',phase:'LOCKED'});
  assert.equal(calls.at(-1).url,origin+WEB_AUTH_PATH);
  assert.equal(calls.at(-1).body.action,'submit');
  assert.equal(calls.at(-1).body.jobId,jobId);
  runtime.dispose();assert.equal(runtime.currentAccount(),null);
});
test('web identity: actual Google runtime is disabled without explicit TTL and auth_time review',async()=>{
  await assert.rejects(()=>prepareExistingGoogleWebDeletion({
    app:{options:{projectId:'petworld-dev',storageBucket:'petworld-dev.appspot.com'}},clientId,readiness:{},
    retentionAudit:async()=>false,
  }),DeletionDenied);
});
