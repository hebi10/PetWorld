import {test} from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,sign} from 'node:crypto';
import {readFileSync,mkdirSync,rmSync,mkdtempSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {chromium} from 'playwright';
import {OAuth2Client} from 'google-auth-library';
import {createExistingGoogleWebDeletionService} from '../server/account-deletion-web-identity.mjs';
import {createWebExistingAuthHandler,WEB_AUTH_PATH} from '../server/account-deletion-web-http.mjs';
import {createDeletionHandlers} from '../server/account-deletion-service.mjs';
import {createAccountDeletionHttpHandler} from '../server/account-deletion-http.mjs';
import {createDevDeletionAdmission,createDeletionRateLimiter} from '../server/account-deletion-admission.mjs';
import {deletionJobId} from '../server/account-deletion.mjs';

const origin='https://petworld-browser-qa.example.invalid';
const appId='1:123:web:abcdef',androidId='1:123:android:abcdef';
const clientId='123456-fixture.apps.googleusercontent.com';
const appToken='fixture-app-check';
const uid='qa-browser-alice',otherUid='qa-browser-bob';
const subject='fixture-google-alice',otherSubject='fixture-google-bob';
const jobId=deletionJobId(uid);
const password='fixture-recovery-password';
const webRoot=new URL('../web/',import.meta.url);
const html=readFileSync(new URL('account-deletion-preview.html',webRoot),'utf8');
const evidence=join(process.cwd(),'web-qa-evidence');
const staticHeaders={
  'Cache-Control':'no-store, private','X-Content-Type-Options':'nosniff',
  'Referrer-Policy':'no-referrer',
  'Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
};
const modules=new Set(['account-deletion-client.mjs','account-deletion-google.mjs','account-deletion-page.mjs','account-deletion-bootstrap.mjs']);

function memoryDb(){
  const rows=new Map();let queue=Promise.resolve();
  const db={
    doc:path=>({path,get:async()=>snapshot(path)}),
    collection:name=>({doc:id=>db.doc(name+'/'+id)}),
    runTransaction(action){
      const run=queue.then(async()=>{
        const writes=[];
        const result=await action({get:async ref=>snapshot(ref.path),
          create:(ref,value)=>{assert(!rows.has(ref.path));writes.push([ref.path,value]);},
          set:(ref,value)=>writes.push([ref.path,value]),
          delete:ref=>writes.push([ref.path,undefined])});
        for(const [path,value] of writes)value===undefined?rows.delete(path):rows.set(path,value);
        return result;
      });queue=run.catch(()=>{});return run;
    },
  };
  function snapshot(path){return {exists:rows.has(path),data:()=>rows.get(path)};}
  return {db,rows};
}

function backend(){
  const {db,rows}=memoryDb();
  const {publicKey,privateKey}=generateKeyPairSync('rsa',{modulusLength:2048});
  const certs={fixture:publicKey.export({type:'spki',format:'pem'})};
  const google=new OAuth2Client();
  const users=new Map([
    [subject,{uid,disabled:false,providerData:[{providerId:'google.com',uid:subject}]}],
    [otherSubject,{uid:otherUid,disabled:false,providerData:[{providerId:'google.com',uid:otherSubject}]}],
  ]);
  const auth={async getUserByProviderUid(provider,sub){
    assert.equal(provider,'google.com');
    const user=users.get(sub);
    if(!user)throw new Error('Synthetic account not found');
    return user;
  }};
  const verify=async(token,audience)=>(await google.verifySignedJwtWithCertsAsync(
    token,certs,audience,['https://accounts.google.com'])).getPayload();
  const service=createExistingGoogleWebDeletionService({db,auth,bucket:{},retentionAudit:async()=>false,verify,clientId});
  const take=createDeletionRateLimiter({db,appIds:[androidId],webAppIds:[appId],requestLimit:100,statusLimit:100});
  const admit=createDevDeletionAdmission({appIds:[androidId],webAppIds:[appId],webOrigin:origin,take,
    appCheck:{async verifyToken(token){assert.equal(token,appToken);return {appId};}}});
  const fallback=createAccountDeletionHttpHandler({service:createDeletionHandlers({db,auth,bucket:{},retentionAudit:async()=>false}),admit});
  const handle=createWebExistingAuthHandler({service,admit,fallback});
  function credential({nonce,identity=subject,mode='fresh'}){
    assert.match(nonce,/^[A-Za-z0-9_-]{43}$/);
    const time=Math.floor(Date.now()/1000);
    const data={iss:'https://accounts.google.com',aud:clientId,sub:identity,nonce,iat:time,exp:time+300,
      ...(mode==='missing'?{}:{auth_time:time-(mode==='stale'?301:0)})};
    const encode=value=>Buffer.from(JSON.stringify(value)).toString('base64url');
    const input=encode({alg:'RS256',typ:'JWT',kid:'fixture'})+'.'+encode(data);
    return input+'.'+sign('RSA-SHA256',Buffer.from(input),privateKey).toString('base64url');
  }
  return {handle,credential,users,rows,
    phase(value){const row=rows.get('accountDeletionJobs/'+jobId);assert(row);row.phase=value;}};
}

const qaBootstrap=`
import {mountReviewedWebDeletionPage} from '/account-deletion-bootstrap.mjs';
const flags=['webDeletionVerified','existingAccountSignInVerified','appCheckVerified','sameOriginVerified',
  'recoveryVerified','googleAuthTimeVerified','googleCredentialUiVerified','webHostHeadersVerified'];
const readiness=Object.fromEntries(flags.map(flag=>[flag,true]));
let config;
const gis={
  initialize(options){config=options;},
  renderButton(node){
    const captured=config;
    const button=document.createElement('button');button.type='button';button.textContent='합성 Google 인증';
    button.addEventListener('click',async()=>{
      const credential=await globalThis.qaSignedCredential({nonce:captured.nonce});
      captured.callback({credential});
    });node.append(button);
  },
};
const settings={root:document.querySelector('main'),gis,
  appCheck:{app:{options:{projectId:'petworld-dev',appId:'1:123:web:abcdef'}}},
  sdk:{getToken:async()=>({token:'fixture-app-check'})},webAppId:'1:123:web:abcdef',
  googleClientId:'123456-fixture.apps.googleusercontent.com',origin:location.origin,readiness};
if(mountReviewedWebDeletionPage({...settings,readiness:{}})!==null)throw Error('Unreviewed bootstrap enabled');
if(!document.querySelector('#connect-existing').disabled)throw Error('Unreviewed bootstrap changed UI');
const dispose=mountReviewedWebDeletionPage(settings);
if(typeof dispose!=='function')throw Error('Synthetic bootstrap failed');
if(mountReviewedWebDeletionPage(settings)!==null)throw Error('Duplicate mount accepted');
globalThis.qaDispose=dispose;
`;

async function setup({mobile=false}={}){
  assert.equal(process.env.PETWORLD_LOCAL_BROWSER_QA,'true','Only dedicated synthetic QA is allowed');
  const server=backend(),events=[],unexpected=[],pageErrors=[];
  const browser=await chromium.launch({headless:true});
  const context=await browser.newContext({acceptDownloads:true,serviceWorkers:'block',
    viewport:mobile?{width:390,height:844}:{width:1200,height:900}});
  let identity=subject,mode='fresh',loseResponse=false;
  await context.exposeBinding('qaSignedCredential',(_source,request)=>server.credential({
    nonce:request.nonce,identity,mode,
  }));
  // Never continue a request onto the network. All paths have a fixed allowlist.
  await context.route('**/*',async route=>{
    const req=route.request(),url=new URL(req.url());
    if(url.origin!==origin||url.search||url.hash){unexpected.push('external-or-variable-url');await route.abort();return;}
    if(req.method()==='GET'){
      const name=url.pathname.slice(1);
      if(name==='review'||name==='qa'){
        const body=name==='qa'?html.replace('</body>','<script type="module" src="/qa-bootstrap.mjs"></script></body>'):html;
        await route.fulfill({status:200,headers:{...staticHeaders,'Content-Type':'text/html; charset=utf-8'},body});return;
      }
      if(name==='qa-bootstrap.mjs'||modules.has(name)){
        const body=name==='qa-bootstrap.mjs'?qaBootstrap:readFileSync(new URL(name,webRoot),'utf8');
        await route.fulfill({status:200,headers:{...staticHeaders,'Content-Type':'text/javascript; charset=utf-8'},body});return;
      }
      if(name==='favicon.ico'){await route.fulfill({status:204,headers:staticHeaders});return;}
    }
    if(req.method()==='POST'&&[WEB_AUTH_PATH,'/account-deletion/status'].includes(url.pathname)){
      const headers=await req.allHeaders(),rawBody=req.postDataBuffer();
      assert(rawBody&&rawBody.length<=2048);
      const body=JSON.parse(rawBody.toString('utf8'));
      assert.equal(headers.origin,origin);
      const result=await server.handle({path:url.pathname,method:req.method(),
        contentType:headers['content-type'],authorization:headers.authorization,
        appCheckToken:headers['x-firebase-appcheck'],rawBody,
        ingress:{directTls:true,origin:headers.origin}});
      // Only minimal non-secret diagnostics leave the fixture.
      events.push({path:url.pathname,action:body.action??'status',code:result.statusCode});
      if(loseResponse&&body.action==='submit'&&result.statusCode===202){loseResponse=false;await route.abort();return;}
      await route.fulfill({status:result.statusCode,headers:result.headers,body:JSON.stringify(result.body)});return;
    }
    unexpected.push('unlisted-route');await route.abort();
  });
  context.on('page',opened=>opened.on('pageerror',()=>pageErrors.push('page-error')));
  const page=await context.newPage();
  return {server,browser,context,page,events,identity:v=>{identity=v;},mode:v=>{mode=v;},
    lose:()=>{loseResponse=true;},
    async finish(){await browser.close();assert.deepEqual(unexpected,[]);assert.deepEqual(pageErrors,[]);}};
}
const waitText=(page,id,value)=>page.waitForFunction(({id,value})=>document.getElementById(id).textContent.includes(value),{id,value});
const enabled=(page,id)=>page.waitForFunction(id=>!document.getElementById(id).disabled,id);
const disabled=(page,id)=>page.waitForFunction(id=>document.getElementById(id).disabled,id);
const count=(env,action)=>env.events.filter(e=>e.action===action).length;
async function open(page,path='qa'){await page.goto(origin+'/'+path);await page.waitForLoadState('load');}
async function authenticate(env){
  const {page}=env;
  await enabled(page,'connect-existing');await page.locator('#connect-existing').click();
  const dialog=page.getByRole('dialog',{name:'기존 Google 계정 확인'});
  await dialog.getByRole('button',{name:'합성 Google 인증'}).click();
  await waitText(page,'account-status','기존 Google 계정 인증됨');
  await enabled(page,'prepare-recovery');
}
async function exportRecovery(env,file){
  const {page}=env;
  await page.locator('#recovery-password').fill(password);
  const download=page.waitForEvent('download');
  await page.locator('#prepare-recovery').click();
  const saved=await download;assert.equal(saved.suggestedFilename(),'petworld-deletion-recovery.json');
  await saved.saveAs(file);
  const encrypted=readFileSync(file,'utf8');
  assert.deepEqual(Object.keys(JSON.parse(encrypted)).sort(),['ciphertext','iv','salt','v']);
  assert(!encrypted.includes(uid)&&!encrypted.includes(jobId)&&!encrypted.includes(password));
  await disabled(page,'saved-recovery');await disabled(page,'submit-deletion');
  await enabled(page,'restore-recovery');
}
async function restore(page,file,secret=password){
  await enabled(page,'restore-recovery');
  await page.locator('#recovery-file').setInputFiles(file);
  await page.locator('#restore-password').fill(secret);
  await page.locator('#restore-recovery').click();
}
async function confirm(page){
  await enabled(page,'saved-recovery');await page.locator('#saved-recovery').check();
  await enabled(page,'confirm-deletion');await page.locator('#confirm-deletion').check();
  await enabled(page,'submit-deletion');
}
async function submit(env){
  await env.page.locator('#submit-deletion').click();
  const dialog=env.page.getByRole('dialog',{name:'Google 계정 다시 인증'});
  await dialog.getByRole('button',{name:'합성 Google 인증'}).click();
}
async function noStorage(page){
  assert.deepEqual(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length})),{local:0,session:0});
}

test('Chromium: default review stays inert and mobile layout has no horizontal overflow',{timeout:60_000},async()=>{
  const env=await setup({mobile:true});
  try{
    await open(env.page,'review');
    for(const id of ['connect-existing','prepare-recovery','saved-recovery','submit-deletion','restore-recovery','refresh-status'])
      assert.equal(await env.page.locator('#'+id).isDisabled(),true);
    assert.equal(await env.page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
    assert.equal(env.events.length,0);
    await noStorage(env.page);
    mkdirSync(evidence,{recursive:true});
    await env.page.screenshot({path:join(evidence,'01-disabled-mobile.png'),fullPage:true});
  }finally{await env.finish();}
});

test('Chromium: cancellation, saved-file readback, stale authentication and uncertain-response recovery',{timeout:90_000},async()=>{
  const env=await setup(),dir=mkdtempSync(join(tmpdir(),'petworld-browser-qa-'));
  const file=join(dir,'fixture-recovery.json');
  try{
    const {page,server}=env;
    await open(page);await enabled(page,'connect-existing');
    await page.locator('#connect-existing').click();
    await page.getByRole('dialog',{name:'기존 Google 계정 확인'}).getByRole('button',{name:'취소'}).click();
    await enabled(page,'connect-existing');
    assert.equal(count(env,'identify'),0);
    env.identity('missing-fixture-account');await page.locator('#connect-existing').click();
    await page.getByRole('dialog',{name:'기존 Google 계정 확인'}).getByRole('button',{name:'합성 Google 인증'}).click();
    await waitText(page,'result-status','기존 계정을 확인하지 못했습니다.');
    env.identity(subject);await authenticate(env);
    await exportRecovery(env,file);
    assert.equal(count(env,'submit'),0);
    await restore(page,file,'incorrect-password');
    await waitText(page,'result-status','결과를 확인하지 못했습니다.');
    await disabled(page,'submit-deletion');
    await restore(page,file);await waitText(page,'result-status','복구 파일을 확인했습니다.');
    await disabled(page,'submit-deletion');await confirm(page);
    env.mode('missing');await submit(env);
    await waitText(page,'result-status','동일 계정 인증');
    assert.equal(count(env,'submit'),0);
    env.mode('stale');await submit(env);
    await waitText(page,'result-status','동일 계정 인증');
    await enabled(page,'submit-deletion');assert.equal(count(env,'submit'),0);
    env.mode('fresh');env.identity(otherSubject);await submit(env);
    await waitText(page,'result-status','결과를 확인하지 못했습니다.');
    await enabled(page,'submit-deletion');
    assert(!server.rows.has('accountDeletionLocks/'+uid));
    assert(!server.rows.has('accountDeletionLocks/'+otherUid));
    env.identity(subject);env.lose();await submit(env);
    await waitText(page,'result-status','결과를 확인하지 못했습니다.');
    await enabled(page,'refresh-status');
    assert(server.rows.has('accountDeletionLocks/'+uid));
    assert(!server.rows.has('accountDeletionLocks/'+otherUid));
    await page.locator('#refresh-status').click();
    await waitText(page,'result-status','삭제 요청 접수 및 접근 차단');
    await disabled(page,'submit-deletion');
    await noStorage(page);
    mkdirSync(evidence,{recursive:true});
    await page.screenshot({path:join(evidence,'02-request-confirmed.png'),fullPage:true});
    // Delete only the synthetic provider record, not a live Firebase account.
    server.users.delete(subject);server.phase('AUTH_DELETED');
    const page2=await env.context.newPage();
    await open(page2);await enabled(page2,'restore-recovery');
    await disabled(page2,'submit-deletion');
    await restore(page2,file);await waitText(page2,'result-status','복구 파일을 확인했습니다.');
    await page2.locator('#refresh-status').click();
    await waitText(page2,'result-status','아직 전체 완료가 아닙니다.');
    assert(!(await page2.locator('#result-status').textContent()).includes('서버 삭제 완료'));
    server.phase('COMPLETE');await page2.locator('#refresh-status').click();
    await waitText(page2,'result-status','서버 삭제 완료');
    await disabled(page2,'submit-deletion');
    await noStorage(page2);
    await page2.screenshot({path:join(evidence,'03-recovered-complete.png'),fullPage:true});
  }finally{rmSync(dir,{recursive:true,force:true});await env.finish();}
});

test('Chromium: exiting a credential dialog invalidates the previous browser session',{timeout:60_000},async()=>{
  const env=await setup();
  try{
    const {page}=env;
    await open(page);await enabled(page,'connect-existing');
    await page.locator('#connect-existing').click();
    await page.getByRole('dialog',{name:'기존 Google 계정 확인'}).waitFor();
    // Real browser pagehide event; no identity response or new request follows.
    await page.evaluate(()=>globalThis.dispatchEvent(new PageTransitionEvent('pagehide')));
    await disabled(page,'connect-existing');
    await disabled(page,'restore-recovery');
    assert.equal(count(env,'identify'),0);
    assert.equal(count(env,'submit'),0);
    assert.equal(await page.locator('#result-status').textContent(),'');
    await noStorage(page);
  }finally{await env.finish();}
});
