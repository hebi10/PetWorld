import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createWebDeletionController,createWebDeletionTransport} from '../web/account-deletion-client.mjs';
import {createDevDeletionAdmission,createDeletionRateLimiter} from '../server/account-deletion-admission.mjs';
import {prepareDevDeletionHttpsServer} from '../server/account-deletion-https.mjs';
import {DeletionDenied,deletionJobId} from '../server/account-deletion.mjs';

const uid='qa-web-alice', other='qa-web-bob', id=deletionJobId(uid);
const password='synthetic-recovery-password';
const now=1_795_000_000_000;
function setup(){
  let account=uid,auth={uid,idToken:'synthetic-fresh-token',authTimeSeconds:now/1000};
  let phase='LOCKED',lost=false;
  const calls=[];
  const controller=createWebDeletionController({
    currentAccount:()=>account,reauthenticate:async()=>auth,now:()=>now,
    exchange:async(route,bearer,body,expected,allowed)=>{
      if(!allowed())throw new Error('account changed');
      calls.push({route,bearer,body,expected});
      if(lost)throw new Error('synthetic lost response');
      return {jobId:expected,phase};
    },
  });
  return {controller,calls,account:value=>{account=value;},auth:value=>{auth=value;},
    phase:value=>{phase=value;},lost:value=>{lost=value;}};
}
async function prepared(env){
  const result=await env.controller.prepare(password);
  assert.equal(result.kind,'PREPARED');
  assert.deepEqual(Object.keys(JSON.parse(result.file)).sort(),['ciphertext','iv','salt','v']);
  assert(!result.file.includes(uid)&&!result.file.includes(id));
  return result.file;
}
test('web: explicit consent, saved file and fresh same-account reauthentication required',async()=>{
  const env=setup();
  await prepared(env);
  assert.equal((await env.controller.submit(true)).kind,'NOT_READY');
  env.controller.confirmSaved();
  assert.equal((await env.controller.submit(false)).kind,'NOT_READY');
  env.auth({uid:other,idToken:'test',authTimeSeconds:now/1000});
  assert.equal((await env.controller.submit(true)).kind,'NOT_READY');
  env.auth({uid,idToken:'test',authTimeSeconds:now/1000-301});
  assert.equal((await env.controller.submit(true)).kind,'NOT_READY');
  assert.equal(env.calls.length,0);
});
test('web: lost response retries the exact ticket and never creates a second recovery anchor',async()=>{
  const env=setup();
  await prepared(env);
  env.controller.confirmSaved();
  env.lost(true);
  assert.equal((await env.controller.submit(true)).kind,'UNKNOWN');
  assert.equal((await env.controller.prepare(password)).kind,'RECOVERY_REQUIRED');
  env.lost(false);
  assert.deepEqual(await env.controller.submit(true),{kind:'CONFIRMED',phase:'LOCKED'});
  assert.equal(env.calls.length,2);
  assert.equal(env.calls[0].expected,id);
  assert.equal(env.calls[0].body.statusTicket,env.calls[1].body.statusTicket);
  assert.equal(Buffer.from(env.calls[0].body.statusTicket,'base64url').length,32);
  assert.equal((await env.controller.submit(true)).kind,'STATUS_REQUIRED');
});
test('web: encrypted recovery survives auth deletion and cannot submit for a different account',async()=>{
  const original=setup(), file=await prepared(original);
  original.controller.confirmSaved();
  await original.controller.submit(true);
  const restored=setup();
  restored.account(null);
  assert.equal((await restored.controller.restore(file,password)).kind,'RESTORED');
  restored.phase('COMPLETE');
  assert.deepEqual(await restored.controller.refresh(),{kind:'CONFIRMED',phase:'COMPLETE'});
  assert.equal(restored.calls[0].route,'status');
  assert.deepEqual(restored.calls[0].body,{jobId:id});
  assert.equal(restored.calls[0].bearer,original.calls[0].body.statusTicket);
  const wrong=setup();wrong.account(other);
  assert.equal((await wrong.controller.restore(file,password)).kind,'RESTORED');
  assert.equal((await wrong.controller.submit(true)).kind,'NOT_READY');
  assert.equal(wrong.calls.length,0);
});
test('web: wrong password, tampering and unrelated recovery file never dispatch requests',async()=>{
  const env=setup(), file=await prepared(env), restored=setup();
  assert.equal((await restored.controller.restore(file,'incorrect-password')).kind,'UNKNOWN');
  const changed=JSON.parse(file);
  const bytes=Buffer.from(changed.ciphertext,'base64url');bytes[0]^=1;
  changed.ciphertext=bytes.toString('base64url');
  assert.equal((await restored.controller.restore(JSON.stringify(changed),password)).kind,'UNKNOWN');
  assert.equal((await restored.controller.refresh()).kind,'NOT_READY');
  assert.equal(restored.calls.length,0);
  const different=setup();different.account(other);
  const second=await different.controller.prepare(password);
  assert.equal(second.kind,'PREPARED');
  assert.equal((await env.controller.restore(second.file,password)).kind,'UNKNOWN');
});
test('web: receipt cannot regress and page exit clears in-memory state',async()=>{
  const env=setup();
  await prepared(env);env.controller.confirmSaved();await env.controller.submit(true);
  env.phase('COMPLETE');assert.equal((await env.controller.refresh()).phase,'COMPLETE');
  env.phase('LOCKED');assert.equal((await env.controller.refresh()).kind,'UNKNOWN');
  assert.equal(env.controller.snapshot().phase,'COMPLETE');
  env.controller.forget();
  assert.equal(env.controller.snapshot().hasRecovery,false);
  assert.equal((await env.controller.refresh()).kind,'NOT_READY');
});
test('web: strict same-origin HTTPS, App Check and no redirects or cross-origin credentials',async()=>{
  const origin='https://dev.example.invalid', calls=[];
  const fetcher=async(url,options)=>{
    calls.push({url,options});
    return new Response(JSON.stringify({jobId:id,phase:'LOCKED'}),{
      status:202,headers:{'content-type':'application/json; charset=utf-8','cache-control':'no-store'}});
  };
  const config={origin,currentOrigin:origin,appCheck:async()=> 'synthetic-app-check',fetcher};
  assert.throws(()=>createWebDeletionTransport({...config,currentOrigin:'https://other.example.invalid'}));
  assert.throws(()=>createWebDeletionTransport({...config,origin:'http://dev.example.invalid',currentOrigin:'http://dev.example.invalid'}));
  const send=createWebDeletionTransport(config);
  await assert.rejects(()=>send('advance','synthetic-token',{},id));
  await assert.rejects(()=>send('request','synthetic-token',{},id,()=>false));
  assert.equal(calls.length,0);
  assert.deepEqual(await send('request','synthetic-token',{confirmation:'DELETE_MY_PETWORLD_ACCOUNT'},id),
    {jobId:id,phase:'LOCKED'});
  assert.equal(calls[0].url,origin+'/account-deletion/request');
  assert.equal(calls[0].options.redirect,'error');
  assert.equal(calls[0].options.credentials,'omit');
  assert.equal(calls[0].options.cache,'no-store');
  assert.equal(calls[0].options.headers['X-Firebase-AppCheck'],'synthetic-app-check');
});
test('web: response size, wrong job, unexpected fields and missing no-store are rejected',async()=>{
  const origin='https://dev.example.invalid';
  const headers={'content-type':'application/json','cache-control':'no-store'};
  for(const [body,h,status] of [
    [JSON.stringify({jobId:deletionJobId(other),phase:'LOCKED'}),headers,202],
    [JSON.stringify({jobId:id,phase:'LOCKED',private:'not-for-browser'}),headers,202],
    [JSON.stringify({jobId:id,phase:'LOCKED'}),{'content-type':'application/json'},202],
    ['x'.repeat(2049),headers,202],
    [JSON.stringify({jobId:id,phase:'LOCKED'}),headers,503],
  ]){
    const send=createWebDeletionTransport({origin,currentOrigin:origin,appCheck:async()=> 'test',
      fetcher:async()=>new Response(body,{headers:h,status})});
    await assert.rejects(()=>send('request','test',{},id));
  }
});
test('web: App Check allowlist is explicit and browser Origin is additional enforcement',async()=>{
  const android='1:123:android:abcdef',web='1:123:web:abcdef',origin='https://dev.example.invalid';
  const counted=[];
  const verifyToken=async value=>({appId:value==='web-token'?web:android});
  const take=async input=>{counted.push(input);return true;};
  const plain=createDevDeletionAdmission({appIds:[android],appCheck:{verifyToken},take});
  const input={route:'web-auth',appCheckToken:'web-token',ingress:{directTls:true,origin}};
  assert.equal(await plain(input),'DENY');
  assert.throws(()=>createDevDeletionAdmission({appIds:[android],webAppIds:[web],
    appCheck:{verifyToken},take}),DeletionDenied);
  const admit=createDevDeletionAdmission({appIds:[android],webAppIds:[web],webOrigin:origin,
    appCheck:{verifyToken},take});
  assert.equal(await admit({...input,ingress:{directTls:true}}),'DENY');
  assert.equal(await admit({...input,ingress:{directTls:true,origin:'https://other.example.invalid'}}),'DENY');
  assert.equal(await admit(input),'ALLOW');
  assert.equal(await admit({...input,route:'request'}),'DENY');
  assert.equal(await admit({...input,appCheckToken:'android-token'}),'DENY');
  assert.equal(await admit({...input,route:'request',appCheckToken:'android-token',ingress:{directTls:true}}),'ALLOW');
  assert.deepEqual(counted.map(x=>x.appId),[web,android]);
  assert.throws(()=>createDeletionRateLimiter({db:{},appIds:[web],requestLimit:1,statusLimit:1}),DeletionDenied);
});
test('web: existing development readiness does not activate web admission',async()=>{
  const readiness=Object.fromEntries([
    'approvedDevDeletionTest','rulesLockVerified','retentionReviewed','iamReviewed','httpAdmissionVerified',
    'schedulerVerified','sdkCachePolicyVerified','httpsOriginVerified','appCheckAllowlistVerified',
    'rateLimitStoreVerified','ingressAbuseLimitsVerified',
  ].map(k=>[k,true]));
  await assert.rejects(()=>prepareDevDeletionHttpsServer({
    app:{options:{projectId:'petworld-dev',storageBucket:'petworld-dev.appspot.com'}},
    readiness,origin:'https://dev.example.invalid',appIds:['1:123:android:abcdef'],
    webAppIds:['1:123:web:abcdef'],tls:{key:'fixture',cert:'fixture'},retentionAudit:async()=>false,
  }),DeletionDenied);
});
test('web: unpublished review form cannot start authentication or deletion without bootstrap',()=>{
  const html=readFileSync(new URL('../web/account-deletion-preview.html',import.meta.url),'utf8');
  assert.match(html,/noindex,nofollow/);
  assert.doesNotMatch(html,/<script\b/i);
  for(const id of ['connect-existing','prepare-recovery','submit-deletion','restore-recovery','refresh-status']){
    assert(html.includes('id="'+id+'" disabled'));
  }
});
