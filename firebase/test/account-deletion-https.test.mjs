import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Readable} from 'node:stream';
import {randomBytes} from 'node:crypto';
import {DeletionDenied} from '../server/account-deletion.mjs';
import {createDeletionRateLimiter,createDevDeletionAdmission} from '../server/account-deletion-admission.mjs';
import {createDeletionTlsListener,prepareDevDeletionHttpsServer} from '../server/account-deletion-https.mjs';
import {createAccountDeletionHttpHandler} from '../server/account-deletion-http.mjs';

const appA = '1:123:android:abcdef', appB = '1:123:android:123abc';
const origin = 'https://dev.example.invalid';
const jobId = 'a'.repeat(64);
const ticket = () => randomBytes(32).toString('base64url');

// Model transaction serialization for concurrent calls; no live Firebase account.
function fakeDb() {
  const rows = new Map();
  let pending = Promise.resolve();
  const db = {
    collection(name) {
      assert.equal(name,'accountDeletionAdmission');
      return {doc(id) { assert.match(id,/^[a-f0-9]{64}$/); return id; }};
    },
    runTransaction(action) {
      const run = pending.then(async()=>{
        const staged = new Map();
        const result = await action({
          get:async key=>({exists:rows.has(key),data:()=>rows.get(key)}),
          set:(key,value)=>staged.set(key,value),
        });
        for (const [key,value] of staged) rows.set(key,value);
        return result;
      });
      pending = run.catch(()=>{});
      return run;
    },
  };
  return {db,rows};
}

test('C2-C2-01: verified App Check, exact allowlist and trusted TLS are mandatory',async()=>{
  const checked=[], counted=[];
  const admit = createDevDeletionAdmission({
    appIds:[appA],appCheck:{verifyToken:async value=>{
      checked.push(value);
      if (value==='bad') throw new Error('private SDK error');
      return {appId:value==='other'?appB:appA};
    }},
    take:async input=>{ counted.push(input); return true; },
  });
  const input={route:'request',appCheckToken:'good',ingress:{directTls:true}};
  for (const changed of [
    {...input,ingress:{}},{...input,route:'advance'},{...input,appCheckToken:''},
    {...input,appCheckToken:'x'.repeat(8193)},
  ]) assert.equal(await admit(changed),'DENY');
  assert.deepEqual(checked,[]);
  for (const value of ['bad','other']) assert.equal(await admit({...input,appCheckToken:value}),'DENY');
  assert.deepEqual(counted,[]);
  assert.equal(await admit(input),'ALLOW');
  assert.deepEqual(counted,[{appId:appA,route:'request'}]);
});

test('C2-C2-01: shared quota is concurrent, durable across limiter instances and route/app scoped',async()=>{
  const {db,rows}=fakeDb();
  let time=1000;
  const config={db,appIds:[appA,appB],requestLimit:2,statusLimit:3,now:()=>time};
  const first=createDeletionRateLimiter(config), second=createDeletionRateLimiter(config);
  const input={appId:appA,route:'request'};
  const results=await Promise.all([first(input),second(input),first(input),second(input)]);
  assert.equal(results.filter(Boolean).length,2);
  assert.equal(await createDeletionRateLimiter(config)(input),false);
  assert.equal(await first({...input,route:'status'}),true);
  assert.equal(await first({...input,appId:appB}),true);
  assert.equal(rows.size,3);
  for(const row of rows.values()) assert.deepEqual(Object.keys(row).sort(),['count','windowStartMs']);
  time+=59_999;
  assert.equal(await first(input),false);
  time++;
  assert.equal(await second(input),true);
  assert.equal(rows.size,3);
});

test('C2-C2-01: store outage, corrupt counter and backward clock fail closed',async()=>{
  const {db,rows}=fakeDb();
  let time=1000;
  const take=createDeletionRateLimiter({db,appIds:[appA],requestLimit:1,statusLimit:1,now:()=>time});
  const input={route:'request',appCheckToken:'synthetic',ingress:{directTls:true}};
  const admit=createDevDeletionAdmission({appIds:[appA],appCheck:{verifyToken:async()=>({appId:appA})},take});
  assert.equal(await admit(input),'ALLOW');
  assert.equal(await admit(input),'RATE_LIMITED');
  time=999;
  assert.equal(await admit(input),'DENY');
  time=1000;
  for (const key of rows.keys()) rows.set(key,{windowStartMs:1000,count:-1});
  assert.equal(await admit(input),'DENY');
  db.runTransaction=async()=>{throw new Error('unavailable');};
  assert.equal(await admit(input),'DENY');
  assert.throws(()=>createDeletionRateLimiter({db,appIds:[appA],requestLimit:0,statusLimit:1}),DeletionDenied);
  assert.throws(()=>createDevDeletionAdmission({appIds:[],appCheck:{},take}),DeletionDenied);
});

function request({path='/account-deletion/request',method='POST',tls=true,
    data=Buffer.from(JSON.stringify({confirmation:'DELETE_MY_PETWORLD_ACCOUNT',statusTicket:ticket()})),
    extra=[],host='dev.example.invalid',length=String(data.length),complete=true}={}){
  const req=Readable.from([data]);
  Object.assign(req,{socket:{encrypted:tls},url:path,method,complete,
    rawHeaders:['Host',host,'Authorization','Bearer synthetic','X-Firebase-AppCheck','synthetic',
      'Content-Type','application/json','Content-Length',length,...extra]});
  const response={destroyed:false,headersSent:false,writeHead(code,headers){
    this.statusCode=code;this.headers=headers;this.headersSent=true;
  },end(bytes){this.body=JSON.parse(bytes.toString());},destroy(){this.destroyed=true;}};
  return {req,response};
}

test('C2-C2-01: strict TLS ingress denies wrong host, public worker path, queries, duplicates and large bodies',async()=>{
  let calls=0;
  const handle=createDeletionTlsListener({origin,handleHttp:async()=>{calls++;}});
  const data=Buffer.from('{}');
  for(const [changed,code] of [
    [{tls:false,extra:['X-Forwarded-Proto','https']},403],
    [{host:'other.example.invalid'},400],
    [{path:'/account-deletion/advance'},404],
    [{path:'/account-deletion/request?uid=someone'},404],
    [{method:'GET'},405],
    [{extra:['authorization','Bearer duplicate']},400],
    [{extra:['transfer-encoding','chunked']},400],
    [{extra:['content-encoding','gzip']},400],
    [{extra:['Expect','100-continue']},400],
    [{length:'wrong'},411],
    [{length:'2049'},413],
    [{data:Buffer.alloc(2049),length:'2'},413],
    [{data,complete:false},400],
  ]){
    const {req,response}=request(changed);
    await handle(req,response);
    assert.equal(response.statusCode,code,JSON.stringify(changed));
    assert.match(response.headers['Cache-Control'],/no-store/);
    assert.equal(response.headers.Connection,'close');
  }
  assert.equal(calls,0);
});

test('C2-C2-01: TLS listener uses existing admission and only returns a minimal receipt',async()=>{
  const calls=[];
  const handleHttp=createAccountDeletionHttpHandler({
    service:{
      request:async input=>{calls.push(input);return {jobId,phase:'LOCKED',secret:'not-returned'};},
      status:async()=>({jobId,phase:'AUTH_DELETED',secret:'not-returned'}),
    },
    admit:async ({ingress})=>ingress.directTls===true?'ALLOW':'DENY',
  });
  const handle=createDeletionTlsListener({origin,handleHttp});
  const {req,response}=request();
  await handle(req,response);
  assert.equal(response.statusCode,202);
  assert.deepEqual(response.body,{jobId,phase:'LOCKED'});
  assert.equal(calls.length,1);
  const status=request({path:'/account-deletion/status',data:Buffer.from(JSON.stringify({jobId}))});
  await handle(status.req,status.response);
  assert.equal(status.response.statusCode,200);
  assert.deepEqual(status.response.body,{jobId,phase:'AUTH_DELETED'});
});

test('C2-C2-01: no server bootstrap with invalid origin, production project or missing readiness',async()=>{
  for(const invalid of ['http://dev.example.invalid','https://127.0.0.1','https://dev.example.invalid:8443',
    'https://dev.example.invalid/path','https://name@dev.example.invalid','https://dev.example.invalid?key=x']){
    assert.throws(()=>createDeletionTlsListener({origin:invalid,handleHttp:async()=>{}}),DeletionDenied);
  }
  const readiness=Object.fromEntries([
    'approvedDevDeletionTest','rulesLockVerified','retentionReviewed','iamReviewed','httpAdmissionVerified',
    'schedulerVerified','sdkCachePolicyVerified','httpsOriginVerified','appCheckAllowlistVerified',
    'rateLimitStoreVerified','ingressAbuseLimitsVerified',
  ].map(key=>[key,true]));
  const config={origin,app:{options:{projectId:'petworld-dev',storageBucket:'petworld-dev.appspot.com'}},
    readiness,retentionAudit:async()=>false,tls:{key:'synthetic',cert:'synthetic'}};
  await assert.rejects(()=>prepareDevDeletionHttpsServer({...config,app:{options:{projectId:'production'}}}),DeletionDenied);
  for(const key of Object.keys(readiness)){
    await assert.rejects(()=>prepareDevDeletionHttpsServer({...config,readiness:{...readiness,[key]:false}}),DeletionDenied);
  }
});
