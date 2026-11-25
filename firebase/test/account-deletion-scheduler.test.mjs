import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DeletionDenied} from '../server/account-deletion.mjs';
import {createDeletionSchedulerStore,createDeletionScheduledTick,prepareDevDeletionScheduler} from '../server/account-deletion-scheduler.mjs';

const a='a'.repeat(64), b='b'.repeat(64);
const leaseMs=25*60_000, deadline=10*60_000;
const receipt=(nextCursor,counts={attempted:1,progressed:1,pending:0,retry:0})=>({...counts,nextCursor});

function setup() {
  let row, tail=Promise.resolve(), clock=1000;
  const db={
    collection(name){
      assert.equal(name,'accountDeletionScheduler');
      return {doc(id){assert.equal(id,'v1');return id;}};
    },
    runTransaction(fn){
      const run=tail.then(async()=>{
        let staged;
        const result=await fn({
          get:async()=>({exists:row!==undefined,data:()=>({...row})}),
          set:(_,value)=>{staged={...value};},
        });
        if(staged)row=staged;
        return result;
      });
      tail=run.catch(()=>{});
      return run;
    },
  };
  const now=()=>clock;
  const store=()=>createDeletionSchedulerStore({db,now});
  const tick=(runTrustedBatch,workerDeadlineMs=deadline)=>createDeletionScheduledTick({
    store:store(),runTrustedBatch,workerDeadlineMs,now,
  });
  return {db,now,store,tick,read:()=>row&&({...row}),seed:value=>{row=value;},time:value=>{clock=value;}};
}

test('C2-C2-02: concurrent invocations share one lease and resume from a durable cursor',async()=>{
  const env=setup();
  let release,entered;
  const blocked=new Promise(resolve=>{release=resolve;});
  const started=new Promise(resolve=>{entered=resolve;});
  const inputs=[];
  const first=env.tick(async input=>{
    inputs.push(input);entered();await blocked;return receipt(a);
  });
  const running=first();
  await started;
  try{
    const competing=env.tick(async()=>{assert.fail('concurrent batch');});
    assert.deepEqual(await competing(),{status:'BUSY'});
  }finally{release();}
  assert.deepEqual(await running,{status:'CHECKPOINTED',attempted:1,progressed:1,pending:0,retry:0});
  assert.equal(env.read().cursor,a);
  assert.equal(env.read().leaseId,null);
  const next=env.tick(async input=>{inputs.push(input);return receipt(b);});
  assert.equal((await next()).status,'CHECKPOINTED');
  assert.deepEqual(inputs,[
    {after:null,limit:10,budgetMs:60_000},{after:a,limit:10,budgetMs:60_000},
  ]);
  assert.equal(env.read().cursor,b);
});

test('C2-C2-02: pending and failed jobs are revisited after a full pass',async()=>{
  const env=setup();
  const seen=[];
  const tick=env.tick(async ({after})=>{
    seen.push(after);
    if(after===null)return receipt(a,{attempted:2,progressed:0,pending:1,retry:1});
    return receipt(null,{attempted:0,progressed:0,pending:0,retry:0});
  });
  const first=await tick();
  assert.deepEqual(first,{status:'CHECKPOINTED',attempted:2,progressed:0,pending:1,retry:1});
  assert.equal(env.read().cursor,a);
  assert.equal((await tick()).status,'CHECKPOINTED');
  assert.equal(env.read().cursor,null);
  assert.equal((await tick()).status,'CHECKPOINTED');
  assert.deepEqual(seen,[null,a,null]);
});

test('C2-C2-02: crashed/uncertain batch retains the old cursor until lease expires',async()=>{
  const env=setup();
  await env.tick(async()=>receipt(a))();
  const failing=env.tick(async()=>{throw Error('private SDK failure');});
  assert.deepEqual(await failing(),{status:'UNCERTAIN'});
  const old=env.read();
  assert.equal(old.cursor,a);
  assert.ok(old.leaseId);
  env.time(old.leaseUntilMs-1);
  assert.deepEqual(await env.tick(async()=>{assert.fail('lease not expired');})(),{status:'BUSY'});
  env.time(old.leaseUntilMs);
  assert.equal((await env.tick(async ({after})=>{
    assert.equal(after,a);return receipt(b);
  })()).status,'CHECKPOINTED');
  assert.equal(env.read().cursor,b);
});

test('C2-C2-02: expired or superseded lease cannot checkpoint a stale cursor',async()=>{
  const env=setup();
  const old=await env.store().claim();
  env.time(old.leaseUntilMs);
  const current=await env.store().claim();
  assert.notEqual(current.leaseId,old.leaseId);
  await assert.rejects(()=>env.store().commit(old,a,deadline),DeletionDenied);
  await env.store().commit(current,b,deadline);
  await assert.rejects(()=>env.store().commit(current,a,deadline),DeletionDenied);
  assert.equal(env.read().cursor,b);
});

test('C2-C2-02: late result never releases lease, even if an SDK action returned',async()=>{
  const env=setup();
  const tick=env.tick(async()=>{
    env.time(env.now()+deadline);
    return receipt(a);
  });
  assert.deepEqual(await tick(),{status:'UNCERTAIN'});
  assert.equal(env.read().cursor,null);
  assert.ok(env.read().leaseId);
  assert.equal(env.read().leaseUntilMs,1000+leaseMs);
});

test('C2-C2-02: malformed counts, regressing cursor and untrusted tick input fail closed',async()=>{
  const env=setup();
  let calls=0;
  const tick=env.tick(async()=>{calls++;return receipt(a);});
  await assert.rejects(()=>tick({uid:'not-trusted',after:b}),DeletionDenied);
  assert.equal(calls,0);
  assert.equal((await tick()).status,'CHECKPOINTED');
  const corrupt=env.tick(async()=>receipt(a));
  assert.deepEqual(await corrupt(),{status:'UNCERTAIN'});
  env.time(env.read().leaseUntilMs);
  const counts=env.tick(async()=>receipt(b,{attempted:1,progressed:1,pending:1,retry:0}));
  assert.deepEqual(await counts(),{status:'UNCERTAIN'});
  assert.equal(env.read().cursor,a);
  assert.throws(()=>env.tick(async()=>receipt(b),60_000),DeletionDenied);
});

test('C2-C2-02: clock rollback, corrupt durable state and unavailable store cannot run a batch',async()=>{
  const env=setup();
  await env.tick(async()=>receipt(a))();
  env.time(999);
  const never=env.tick(async()=>{assert.fail('must not run');});
  await assert.rejects(()=>never(),DeletionDenied);
  env.time(1000);
  env.seed({...env.read(),cursor:'malformed'});
  await assert.rejects(()=>never(),DeletionDenied);
  env.db.runTransaction=async()=>{throw Error('unavailable');};
  await assert.rejects(()=>never(),/unavailable/);
});

test('C2-C2-02: lost checkpoint acknowledgement uses the stored cursor on the next tick',async()=>{
  const env=setup();
  const store=env.store();
  const tick=createDeletionScheduledTick({
    store:{
      claim:store.claim,
      commit:async (...args)=>{await store.commit(...args);throw Error('lost acknowledgement');},
    },
    runTrustedBatch:async()=>receipt(a),workerDeadlineMs:deadline,now:env.now,
  });
  assert.deepEqual(await tick(),{status:'UNCERTAIN'});
  assert.equal(env.read().cursor,a);
  assert.equal((await env.tick(async ({after})=>{assert.equal(after,a);return receipt(b);})()).status,'CHECKPOINTED');
});

test('C2-C2-02: private worker bootstrap requires reviewed dev project, runtime gates and hard deadline',async()=>{
  const readiness=Object.fromEntries([
    'approvedDevDeletionTest','schedulerVerified','schedulerStoreVerified','workerIdentityVerified',
    'workerHardDeadlineVerified','workerClockVerified','httpAdmissionVerified','sdkCachePolicyVerified',
    'rulesLockVerified','retentionReviewed','iamReviewed',
  ].map(key=>[key,true]));
  const config={app:{options:{projectId:'petworld-dev',storageBucket:'petworld-dev.appspot.com'}},
    readiness,retentionAudit:async()=>false,workerDeadlineMs:deadline};
  await assert.rejects(()=>prepareDevDeletionScheduler({...config,app:{options:{projectId:'production'}}}),DeletionDenied);
  for(const key of Object.keys(readiness))
    await assert.rejects(()=>prepareDevDeletionScheduler({...config,readiness:{...readiness,[key]:false}}),DeletionDenied);
  await assert.rejects(()=>prepareDevDeletionScheduler({...config,workerDeadlineMs:deadline+1}),DeletionDenied);
});
