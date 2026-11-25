import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createAccountDeletionHttpHandler } from '../server/account-deletion-http.mjs';
import { DeletionDenied } from '../server/account-deletion.mjs';

const ticket = () => randomBytes(32).toString('base64url');
const jobId = 'a'.repeat(64);
const req = body => ({
  path:'/account-deletion/request',method:'POST',contentType:'application/json; charset=utf-8',
  authorization:'Bearer synthetic-auth',appCheckToken:'synthetic-app-check',
  ingress:Object.freeze({testOnly:true}),rawBody:Buffer.from(JSON.stringify(body)),
});
const requestBody = () => ({confirmation:'DELETE_MY_PETWORLD_ACCOUNT',statusTicket:ticket()});
function setup() {
  const calls=[];
  const service={
    async request(input) { calls.push(['request',input]); return {jobId,phase:'LOCKED',uid:'not-for-response'}; },
    async status(input) { calls.push(['status',input]); return {jobId,phase:'VERIFIED',ticketDigest:'not-for-response'}; },
    async advanceTrusted() { throw new Error('must not be public'); },
  };
  const admit = async input => input.ingress?.testOnly === true && input.appCheckToken==='synthetic-app-check' ? 'ALLOW':'DENY';
  return {calls,service,admit,handle:createAccountDeletionHttpHandler({service,admit})};
}
test('FB07-C1: unconfigured boundary, unknown routes and malformed requests never invoke service',async()=>{
  const {calls,handle}=setup();
  const input=req(requestBody());
  assert.equal((await createAccountDeletionHttpHandler()(input)).statusCode,503);
  for (const [modified,status] of [
    [{...input,path:'/account-deletion/advance'},404],
    [{...input,path:'/account-deletion/request?uid=alice'},404],
    [{...input,method:'GET'},405],
    [{...input,contentType:'text/plain'},415],
    [{...input,authorization:'invalid'},401],
    [{...input,rawBody:Buffer.alloc(2049)},413],
    [{...input,rawBody:Buffer.from('{bad')},400],
    [{...input,rawBody:Buffer.from([0xff,0xfe])},400],
    [req({...requestBody(),uid:'alice'}),400],
    [req({...requestBody(),confirmation:'cancel'}),400],
    [{...input,appCheckToken:''},403],
    [{...input,ingress:undefined},403],
  ]) assert.equal((await handle(modified)).statusCode,status);
  assert.equal(calls.length,0);
});
test('FB07-C1: admission must succeed and rate limit is distinct',async()=>{
  const {service,calls}=setup();
  const input=req(requestBody());
  for (const [outcome,status] of [['DENY',403],[true,403],['RATE_LIMITED',429]]) {
    const handle=createAccountDeletionHttpHandler({service,admit:async()=>outcome});
    assert.equal((await handle(input)).statusCode,status);
  }
  assert.equal(calls.length,0);
});
test('FB07-C1: request is accepted, not completed, and status returns only minimal receipt',async()=>{
  const {calls,handle}=setup();
  const body=requestBody(),input=req(body);
  const accepted=await handle(input);
  assert.equal(accepted.statusCode,202);
  assert.deepEqual(accepted.body,{jobId,phase:'LOCKED'});
  assert.equal(accepted.headers['Cache-Control'],'no-store, private, max-age=0');
  assert(!Object.keys(accepted.headers).some(x=>x.toLowerCase().startsWith('access-control-')));
  assert(!JSON.stringify(accepted).includes(body.statusTicket));
  const status=await handle({...req({jobId}),path:'/account-deletion/status',authorization:'Bearer '+body.statusTicket});
  assert.equal(status.statusCode,200);
  assert.deepEqual(status.body,{jobId,phase:'VERIFIED'});
  assert.deepEqual(calls.map(c=>c[0]),['request','status']);
});
test('FB07-C1: uncertain server failure and mismatched response cannot report deletion complete',async()=>{
  const {service,admit}=setup();
  const input=req(requestBody());
  const handle=createAccountDeletionHttpHandler({service,admit});
  service.request=async()=>{throw new Error('private response lost');};
  assert.deepEqual((await handle(input)).body,{code:'RESULT_UNKNOWN'});
  service.request=async()=>{throw new DeletionDenied();};
  assert.equal((await handle(input)).statusCode,403);
  service.request=async()=>({jobId,phase:'SUCCESS'});
  assert.equal((await handle(input)).statusCode,503);
  service.status=async()=>({jobId:'b'.repeat(64),phase:'COMPLETE'});
  assert.equal((await handle({...req({jobId}),path:'/account-deletion/status'})).statusCode,503);
});
