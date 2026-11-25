import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createDeletionBatchRunner,prepareDeletionRuntime} from '../server/account-deletion-runtime.mjs';
import {deletionJobId,DeletionDenied} from '../server/account-deletion.mjs';
const jobs=['qa-alice','qa-bob','qa-charlie'].map(uid=>({uid,jobId:deletionJobId(uid),phase:'LOCKED'}))
  .sort((a,b)=>a.jobId.localeCompare(b.jobId));
const source=async ({after,limit})=>jobs.filter(j=>after===null||j.jobId>after).slice(0,limit);
test('FB07-C2B: runtime remains unavailable before trusted verification',async()=>{
  await assert.rejects(()=>prepareDeletionRuntime(),DeletionDenied);
  await assert.rejects(()=>prepareDeletionRuntime({readiness:{httpAdmissionVerified:true,schedulerVerified:true,sdkCachePolicyVerified:true},admit:async()=> 'ALLOW',app:{options:{projectId:'production'}}}),DeletionDenied);
});
test('FB07-C2B: bounded scheduled pass continues independently of an HTTP response',async()=>{
  const calls=[];
  const service={advanceTrusted:async entry=>{
    calls.push(entry.jobId);
    if(entry.jobId===jobs[0].jobId)throw new Error('retry');
    return {jobId:entry.jobId,phase:'AUTH_REVOKED'};
  }};
  const run=createDeletionBatchRunner({service,readPage:source});
  const first=await run({limit:2});
  assert.deepEqual(first,{attempted:2,progressed:1,pending:0,retry:1,nextCursor:jobs[1].jobId});
  const second=await run({after:first.nextCursor,limit:2});
  assert.deepEqual(second,{attempted:1,progressed:1,pending:0,retry:0,nextCursor:null});
  assert.deepEqual(calls,jobs.map(j=>j.jobId));
});
test('FB07-C2B: invalid or reordered Admin jobs cannot trigger any deletion',async()=>{
  let count=0;
  const service={advanceTrusted:async()=>{count++;}};
  for(const page of [[jobs[0],{...jobs[1],uid:'wrong'}],[jobs[1],jobs[0]],[{...jobs[0],phase:'COMPLETE'}]]){
    const run=createDeletionBatchRunner({service,readPage:async()=>page});
    await assert.rejects(()=>run(),DeletionDenied);
  }
  assert.equal(count,0);
});
test('FB07-C2B: uncertain status and expired batch budget are not completion',async()=>{
  let time=0;
  const service={advanceTrusted:async entry=>{
    time+=1100;
    return {jobId:entry.jobId,phase:entry.phase,pendingVerification:true};
  }};
  const run=createDeletionBatchRunner({service,readPage:source,now:()=>time});
  assert.deepEqual(await run({limit:3,budgetMs:1000}),{attempted:1,progressed:0,pending:1,retry:0,nextCursor:jobs[0].jobId});
});

test('FB07-C2B: a regressing worker response is retry, not progress',async()=>{
  const entry={...jobs[0],phase:'RECORDS_PURGED'};
  const run=createDeletionBatchRunner({service:{advanceTrusted:async()=>({jobId:entry.jobId,phase:'LOCKED'})},readPage:async()=>[entry]});
  assert.deepEqual(await run(),{attempted:1,progressed:0,pending:0,retry:1,nextCursor:null});
});
