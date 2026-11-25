import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createAdminDeletionPort, statusTicketDigest } from '../server/account-deletion-admin.mjs';
import { createDeletionHandlers, createFirebaseDeletionService } from '../server/account-deletion-service.mjs';
import { deletionJobId, DeletionDenied } from '../server/account-deletion.mjs';

const now = 1795572000000;
const confirmation = 'DELETE_MY_PETWORLD_ACCOUNT';
const ticket = () => randomBytes(32).toString('base64url');
const missingAuth = () => Object.assign(new Error('missing'),{code:'auth/user-not-found'});
class FakeDb {
  constructor() { this.rows=new Map(); this.deleted=[]; }
  doc(path) {
    const db=this;
    return {path,async get() { return db.snapshot(path); },async listCollections() {
      return [...new Set([...db.rows.keys()].filter(k=>k.startsWith(path+'/')).map(k=>k.slice(path.length+1).split('/')[0]))];
    }};
  }
  snapshot(path) { const data=this.rows.get(path); return {exists:data!==undefined,data:()=>data?structuredClone(data):undefined}; }
  async runTransaction(fn) {
    const writes=[];
    const result=await fn({get:async ref=>this.snapshot(ref.path),create:(ref,data)=>writes.push(['create',ref.path,data]),update:(ref,data)=>writes.push(['update',ref.path,data])});
    for (const [kind,path,data] of writes) {
      if (kind==='create') assert(!this.rows.has(path));
      this.rows.set(path,{...this.rows.get(path),...data});
    }
    return result;
  }
  async recursiveDelete(ref) {
    this.deleted.push(ref.path);
    for (const key of [...this.rows.keys()]) if (key===ref.path||key.startsWith(ref.path+'/')) this.rows.delete(key);
  }
}
class FakeAuth {
  constructor(){this.users=new Map([['alice',{disabled:false}],['alice-other',{disabled:false}]]);this.revocations=[];this.verifications=[];}
  async verifyIdToken(token,revoked){this.verifications.push(revoked); if(token!=='test-token')throw new Error('auth failed'); return {uid:'alice',auth_time:Math.floor(now/1000)-10,firebase:{sign_in_provider:'google.com'}};}
  async getUser(uid){if(!this.users.has(uid))throw missingAuth();return this.users.get(uid);}
  async updateUser(uid,fields){Object.assign(await this.getUser(uid),fields);}
  async revokeRefreshTokens(uid){await this.getUser(uid);this.revocations.push(uid);}
  async deleteUser(uid){await this.getUser(uid);this.users.delete(uid);}
}
class FakeBucket {
  constructor(){this.names=new Set(['users/alice/pets/p/photos/a.jpg','users/alice-other/pets/p/photos/b.jpg']);this.retained=new Set();this.deleted=[];}
  async getFiles(options){
    const source=options.softDeleted?this.retained:this.names;
    const matches=[...source].filter(n=>n.startsWith(options.prefix));
    const files=matches.slice(0,options.maxResults).map(name=>({name,generation:'1234567890123456789',metadata:{generation:'1234567890123456789'},
      delete:async options=>{assert.equal(options.ifGenerationMatch,'1234567890123456789');this.deleted.push(name);this.names.delete(name);}}));
    return [files,matches.length>options.maxResults?{pageToken:'next'}:null];
  }
}
function setup(){
  const db=new FakeDb(),auth=new FakeAuth(),bucket=new FakeBucket();
  db.rows.set('users/alice/pets/p/records/r',{value:1}); // missing parent, descendant remains
  db.rows.set('users/alice/syncReceipts/op',{value:2});
  db.rows.set('users/alice/pets/p/photoManifests/f',{value:3});
  db.rows.set('users/alice-other/pets/p',{value:4});
  let audit=true;
  const handlers=createDeletionHandlers({db,auth,bucket,now:()=>now,retentionAudit:async()=>audit});
  return {db,auth,bucket,handlers,setAudit:v=>{audit=v;}};
}
test('FB07-B: runtime is disabled without explicit dev-only readiness and cannot use production',async()=>{
  await assert.rejects(()=>createFirebaseDeletionService({app:{options:{projectId:'petworld-dev'}}}),DeletionDenied);
  await assert.rejects(()=>createFirebaseDeletionService({app:{options:{projectId:'prod',storageBucket:'prod.appspot.com'}},readiness:{approvedDevDeletionTest:true,rulesLockVerified:true,retentionReviewed:true,iamReviewed:true},retentionAudit:async()=>true}),DeletionDenied);
});
test('FB07-B: verified token, exact request body, idempotent private status ticket',async()=>{
  const {db,auth,handlers}=setup();
  const secret=ticket(),req={method:'POST',authorization:'Bearer test-token',body:{confirmation,statusTicket:secret}};
  await assert.rejects(()=>handlers.request({...req,body:{...req.body,uid:'alice-other'}}),DeletionDenied);
  await assert.rejects(()=>handlers.request({...req,method:'GET'}),DeletionDenied);
  await assert.rejects(()=>handlers.request({...req,authorization:'Bearer invalid'}));
  const accepted=await handlers.request(req);
  assert.equal(accepted.jobId,deletionJobId('alice'));
  assert.equal(accepted.phase,'LOCKED');
  assert.deepEqual(await handlers.request(req),accepted);
  await assert.rejects(()=>handlers.request({...req,body:{confirmation,statusTicket:ticket()}}),DeletionDenied);
  assert(auth.verifications.every(x=>x===true));
  assert.equal(db.rows.get('accountDeletionLocks/alice').state,'LOCKED');
  assert.equal(db.rows.get('accountDeletionJobs/'+accepted.jobId).ticketDigest,statusTicketDigest(secret));
  assert(!JSON.stringify([...db.rows]).includes(secret));
  assert.deepEqual(await handlers.status({method:'POST',authorization:'Bearer '+secret,body:{jobId:accepted.jobId}}),accepted);
  await assert.rejects(()=>handlers.status({method:'POST',authorization:'Bearer '+ticket(),body:{jobId:accepted.jobId}}),DeletionDenied);
});
test('FB07-B: exact UID tree and versioned object deletion, retained data delays completion',async()=>{
  const {db,auth,bucket,handlers,setAudit}=setup();
  const secret=ticket();
  const {jobId}=await handlers.request({method:'POST',authorization:'Bearer test-token',body:{confirmation,statusTicket:secret}});
  const step=()=>handlers.advanceTrusted({uid:'alice',jobId});
  await assert.rejects(()=>handlers.advanceTrusted({uid:'alice-other',jobId}),DeletionDenied);
  assert.equal((await step()).phase,'AUTH_REVOKED');
  assert.equal(auth.users.get('alice').disabled,true);
  assert.deepEqual(auth.revocations,['alice']);
  assert.equal((await step()).phase,'RECORDS_PURGED');
  assert(![...db.rows.keys()].some(x=>x.startsWith('users/alice/')));
  assert(db.rows.has('users/alice-other/pets/p'));
  assert.equal((await step()).phase,'PHOTOS_PURGED');
  assert.deepEqual(bucket.deleted,['users/alice/pets/p/photos/a.jpg']);
  assert(bucket.names.has('users/alice-other/pets/p/photos/b.jpg'));
  assert.equal((await step()).phase,'AUTH_DELETED');
  assert(!auth.users.has('alice')); assert(auth.users.has('alice-other'));
  bucket.retained.add('users/alice/pets/p/photos/a.jpg');
  assert.equal((await step()).pendingVerification,true);
  bucket.retained.clear(); setAudit(false);
  assert.equal((await step()).pendingVerification,true);
  setAudit(true);
  assert.equal((await step()).phase,'VERIFIED');
  assert.equal((await step()).phase,'COMPLETE');
  assert.equal((await step()).phase,'COMPLETE');
  const status=await handlers.status({method:'POST',authorization:'Bearer '+secret,body:{jobId}});
  assert.deepEqual(status,{jobId,phase:'COMPLETE'});
  assert(db.rows.has('accountDeletionLocks/alice'));
  assert.deepEqual(db.deleted,['users/alice']);
});
test('FB07-B: lease excludes another worker and wrong-generation write cannot advance',async()=>{
  const {db,auth,bucket}=setup();
  const port=createAdminDeletionPort({db,auth,bucket,ticketDigest:statusTicketDigest(ticket()),now:()=>now});
  const jobId=deletionJobId('alice');
  await port.requestLock('alice',jobId);
  await port.withLease('alice',jobId,async lease=>{
    await assert.rejects(()=>port.withLease('alice',jobId,async()=>{}),DeletionDenied);
    await assert.rejects(()=>port.advance('alice',jobId,'stale','LOCKED','AUTH_REVOKED'),DeletionDenied);
    assert.equal(await port.advance('alice',jobId,lease,'LOCKED','AUTH_REVOKED'),true);
    assert.equal(await port.advance('alice',jobId,lease,'LOCKED','AUTH_REVOKED'),false);
  });
});

test('FB07-B: unsafe or mismatched SDK generation fails closed without deleting any object',async()=>{
  const {auth,db,bucket}=setup();
  const port=createAdminDeletionPort({db,auth,bucket,ticketDigest:statusTicketDigest(ticket()),now:()=>now});
  const jobId=deletionJobId('alice');
  await port.requestLock('alice',jobId);
  const original=bucket.getFiles.bind(bucket);
  bucket.getFiles=async options=>{
    const result=await original(options);
    for (const file of result[0]) file.generation=Number(file.metadata.generation);
    return result;
  };
  await port.withLease('alice',jobId,async lease=>{
    await assert.rejects(()=>port.purgeUserObjects('alice',lease),DeletionDenied);
  });
  assert.deepEqual(bucket.deleted,[]);
  assert(bucket.names.has('users/alice/pets/p/photos/a.jpg'));
});
