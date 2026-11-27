import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdtempSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createServer,request as httpsRequest} from 'node:https';
import {once} from 'node:events';
import {initializeApp as initAdmin,deleteApp as deleteAdmin} from 'firebase-admin/app';
import {getAuth as adminAuth} from 'firebase-admin/auth';
import {getFirestore as adminFirestore,FieldPath} from 'firebase-admin/firestore';
import {getStorage as adminStorage} from 'firebase-admin/storage';
import {initializeApp,deleteApp} from 'firebase/app';
import {getAuth,connectAuthEmulator,signInWithCredential,GoogleAuthProvider,signOut} from 'firebase/auth';
import {getFirestore,connectFirestoreEmulator,doc,getDocFromServer,setDoc,terminate} from 'firebase/firestore';
import {getStorage,connectStorageEmulator,ref,uploadBytes,getBytes} from 'firebase/storage';
import {createDeletionHandlers} from '../server/account-deletion-service.mjs';
import {createAccountDeletionHttpHandler} from '../server/account-deletion-http.mjs';
import {createDeletionTlsListener} from '../server/account-deletion-https.mjs';
import {createDevDeletionAdmission,createDeletionRateLimiter} from '../server/account-deletion-admission.mjs';
import {createDeletionBatchRunner,createDeletionJobPageReader} from '../server/account-deletion-runtime.mjs';
import {createDeletionScheduledTick,createDeletionSchedulerStore} from '../server/account-deletion-scheduler.mjs';
import {deletionJobId,DeletionDenied} from '../server/account-deletion.mjs';

const PROJECT='demo-petworld-deletion-qa';
const BUCKET=PROJECT+'.appspot.com';
const HOST='petworld-deletion-qa.example.invalid';
const APP_ID='1:1234567890:android:abcdef123456';
const confirmation='DELETE_MY_PETWORLD_ACCOUNT';
const localToken='synthetic-local-app-check';
const ticket=()=>randomBytes(32).toString('base64url');

function requireDemoEmulators() {
  for (const [name,expected] of [
    ['FIREBASE_AUTH_EMULATOR_HOST','127.0.0.1:9099'],
    ['FIRESTORE_EMULATOR_HOST','127.0.0.1:8080'],
    ['FIREBASE_STORAGE_EMULATOR_HOST','127.0.0.1:9199'],
  ]) assert.equal(process.env[name],expected,'Only fixed local emulator endpoints are allowed');
  for(const key of ['GCLOUD_PROJECT','GOOGLE_CLOUD_PROJECT'])
    if(process.env[key])assert.equal(process.env[key],PROJECT);
  if(process.env.STORAGE_EMULATOR_HOST)
    assert.equal(process.env.STORAGE_EMULATOR_HOST,'http://127.0.0.1:9199');
  assert.equal(process.env.GOOGLE_APPLICATION_CREDENTIALS,undefined);
  assert.equal(process.env.PETWORLD_LOCAL_DELETION_QA,'true');
}

async function fixture(label) {
  requireDemoEmulators();
  const app=initializeApp({projectId:PROJECT,apiKey:'fake-ci-key',
    appId:'1:1234567890:web:abcdef123456',storageBucket:BUCKET},'fixture-'+label+'-'+randomUUID());
  const auth=getAuth(app);
  connectAuthEmulator(auth,'http://127.0.0.1:9099',{disableWarnings:true});
  const db=getFirestore(app);
  connectFirestoreEmulator(db,'127.0.0.1',8080);
  const storage=getStorage(app);
  connectStorageEmulator(storage,'127.0.0.1',9199);
  const subject='qa-'+randomUUID();
  const mock=JSON.stringify({sub:subject,email:subject+'@example.invalid',email_verified:true});
  const {user}=await signInWithCredential(auth,GoogleAuthProvider.credential(mock));
  return {app,auth,db,storage,uid:user.uid,token:await user.getIdToken(true)};
}

function requireFixtureUid(uid,allowed) {
  if(typeof uid!=='string'||!allowed.has(uid))throw new DeletionDenied();
}

async function localHttps(handle) {
  const dir=mkdtempSync(join(tmpdir(),'petworld-local-tls-'));
  let server;
  try{
    const keyPath=join(dir,'key.pem'),certPath=join(dir,'cert.pem');
    execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1',
      '-subj','/CN='+HOST,'-addext','subjectAltName=DNS:'+HOST,
      '-keyout',keyPath,'-out',certPath],{stdio:'ignore'});
    const cert=readFileSync(certPath);
    server=createServer({key:readFileSync(keyPath),cert,minVersion:'TLSv1.2'},
      createDeletionTlsListener({origin:'https://'+HOST,handleHttp:handle}));
    server.listen(0,'127.0.0.1');
    await once(server,'listening');
    const port=server.address().port;
    return {
      post(path,bearer,body,appToken=localToken) {
        return new Promise((resolve,reject)=>{
          const raw=Buffer.from(JSON.stringify(body));
          const req=httpsRequest({hostname:'127.0.0.1',port,servername:HOST,ca:cert,
            method:'POST',path,agent:false,minVersion:'TLSv1.2',
            headers:{Host:HOST,Authorization:'Bearer '+bearer,'X-Firebase-AppCheck':appToken,
              'Content-Type':'application/json; charset=utf-8','Content-Length':raw.length}},res=>{
            const chunks=[];let size=0;
            res.on('data',chunk=>{
              size+=chunk.length;
              if(size>2048){req.destroy(new Error('oversized QA response'));return;}
              chunks.push(chunk);
            });
            res.on('error',reject);
            res.on('end',()=>{
              try{resolve({code:res.statusCode,headers:res.headers,body:JSON.parse(Buffer.concat(chunks).toString())});}
              catch(error){reject(error);}
            });
          });
          req.on('error',reject);
          req.setTimeout(15000,()=>req.destroy(new Error('local TLS timeout')));
          req.end(raw);
        });
      },
      async close(){
        const done=new Promise(resolve=>server.close(resolve));
        server.closeAllConnections();
        await done;
        rmSync(dir,{recursive:true,force:true});
      },
    };
  }catch(error){
    server?.close();
    rmSync(dir,{recursive:true,force:true});
    throw error;
  }
}

const denied=error=>['permission-denied','storage/unauthorized','storage/unauthenticated','unauthenticated'].includes(error?.code);

test('FB07-C2-C2-04: local HTTPS, real Auth/Firestore/Storage, scheduled deletion and retained-data gate',
  {timeout:180_000},async()=>{
  requireDemoEmulators();
  const app=initAdmin({projectId:PROJECT,storageBucket:BUCKET},'deletion-local-qa');
  const auth=adminAuth(app),db=adminFirestore(app),bucket=adminStorage(app).bucket(BUCKET);
  const clients=[];
  let tls;
  try{
    const a=await fixture('a');clients.push(a);
    const b=await fixture('b');clients.push(b);
    assert.notEqual(a.uid,b.uid);
    const allowed=new Set([a.uid,b.uid]);
    const aPath='users/'+a.uid,bPath='users/'+b.uid;
    const aPhoto=aPath+'/pets/p/photos/pet_11111111-1111-4111-8111-111111111111.jpg';
    const bPhoto=bPath+'/pets/p/photos/pet_22222222-2222-4222-8222-222222222222.jpg';
    const aBytes=new Uint8Array([255,216,1,255,217]),bBytes=new Uint8Array([255,216,2,255,217]);
    const user={schemaVersion:1,createdAtMs:1700000000000,migrationState:'pending',revision:0};
    await db.doc(aPath).set(user);await db.doc(bPath).set(user);
    // Include descendants below a missing parent and other account records.
    const aChild=db.doc(aPath+'/pets/missing/records/r');
    const bChild=db.doc(bPath+'/pets/p/records/r');
    await aChild.set({fixture:'a'});await bChild.set({fixture:'b'});
    await uploadBytes(ref(a.storage,aPhoto),aBytes,{contentType:'image/jpeg'});
    await uploadBytes(ref(b.storage,bPhoto),bBytes,{contentType:'image/jpeg'});
    assert.equal((await getDocFromServer(doc(a.db,aPath))).exists(),true);
    assert.deepEqual(new Uint8Array(await getBytes(ref(a.storage,aPhoto))),aBytes);

    // Live objects use the actual local Storage emulator. Version/soft-delete
    // retention is not a verified emulator capability; model only that portion.
    // Never export this adapter or use it with actual Firebase projects.
    let retained=false,audit=false;
    const localBucket={
      async getFiles(options){
        requireDemoEmulators();
        const uid=options.prefix?.match(/^users\/([^/]+)\/$/)?.[1];
        requireFixtureUid(uid,allowed);
        if(options.softDeleted){
          return [retained&&uid===a.uid?[{name:aPhoto}]:[],null];
        }
        assert.equal(options.versions,true);
        return bucket.getFiles({prefix:options.prefix,autoPaginate:false,maxResults:options.maxResults});
      },
    };
    const service=createDeletionHandlers({db,auth,bucket:localBucket,
      retentionAudit:async({uid,jobId})=>{
        requireFixtureUid(uid,allowed);
        assert.equal(jobId,deletionJobId(uid));
        return audit;
      }});
    const take=createDeletionRateLimiter({db,appIds:[APP_ID],requestLimit:20,statusLimit:100});
    const admit=createDevDeletionAdmission({appIds:[APP_ID],take,appCheck:{
      async verifyToken(value){
        if(value!==localToken)throw new Error('synthetic App Check rejection');
        return {appId:APP_ID};
      },
    }});
    tls=await localHttps(createAccountDeletionHttpHandler({service,admit}));
    const secret=ticket(),body={confirmation,statusTicket:secret};
    const jobId=deletionJobId(a.uid);
    const read=()=>tls.post('/account-deletion/status',secret,{jobId});
    const checkPhase=async phase=>{
      const result=await read();
      assert.equal(result.code,200);
      assert.deepEqual(result.body,{jobId,phase});
      assert.match(result.headers['cache-control'],/no-store/);
    };
    assert.equal((await tls.post('/account-deletion/request',a.token,body,'invalid')).code,403);
    assert.equal((await db.doc('accountDeletionLocks/'+a.uid).get()).exists,false);
    assert.equal((await tls.post('/account-deletion/request',a.token,{...body,uid:b.uid})).code,400);
    const accepted=await tls.post('/account-deletion/request',a.token,body);
    assert.equal(accepted.code,202);
    assert.deepEqual(accepted.body,{jobId,phase:'LOCKED'});
    assert.deepEqual((await tls.post('/account-deletion/request',a.token,body)).body,accepted.body);
    assert.equal((await tls.post('/account-deletion/request',a.token,{...body,statusTicket:ticket()})).code,403);
    assert.equal((await tls.post('/account-deletion/status',ticket(),{jobId})).code,403);
    assert.equal((await tls.post('/account-deletion/status',secret,{jobId:deletionJobId(b.uid)})).code,403);
    assert.equal((await tls.post('/account-deletion/advance',secret,{jobId})).code,404);
    await assert.rejects(()=>getDocFromServer(doc(a.db,aPath)),denied);
    await assert.rejects(()=>setDoc(doc(a.db,aPath),{...user,revision:1}),denied);
    await assert.rejects(()=>getBytes(ref(a.storage,aPhoto)),denied);
    assert.equal((await getDocFromServer(doc(b.db,bPath))).exists(),true);

    const readPage=createDeletionJobPageReader({db,FieldPath});
    const runTrustedBatch=createDeletionBatchRunner({service:{
      advanceTrusted:async input=>{
        requireFixtureUid(input.uid,new Set([a.uid]));
        return service.advanceTrusted(input);
      },
    },readPage});
    const tick=()=>createDeletionScheduledTick({
      store:createDeletionSchedulerStore({db}),runTrustedBatch,workerDeadlineMs:600_000,
    })();
    async function progress(phase){
      const result=await tick();
      assert.equal(result.status,'CHECKPOINTED');
      assert.equal(result.attempted,1);
      assert.equal(result.progressed,1);
      assert.equal(result.retry,0);
      await checkPhase(phase);
    }
    await progress('AUTH_REVOKED');
    assert.equal((await auth.getUser(a.uid)).disabled,true);
    await assert.rejects(()=>auth.verifyIdToken(a.token,true));
    await progress('RECORDS_PURGED');
    assert.equal((await db.doc(aPath).get()).exists,false);
    assert.equal((await aChild.get()).exists,false);
    assert.deepEqual((await bChild.get()).data(),{fixture:'b'});
    await progress('PHOTOS_PURGED');
    assert.equal((await bucket.file(aPhoto).exists())[0],false);
    assert.equal((await bucket.file(bPhoto).exists())[0],true);
    await progress('AUTH_DELETED');
    await assert.rejects(()=>auth.getUser(a.uid),error=>error.code==='auth/user-not-found');
    assert.equal((await auth.getUser(b.uid)).disabled,false);
    // A's Firebase credential is gone, but its private status capability works.
    await checkPhase('AUTH_DELETED');
    assert.equal((await tick()).pending,1);
    await checkPhase('AUTH_DELETED');
    audit=true;retained=true;
    assert.equal((await tick()).pending,1);
    await checkPhase('AUTH_DELETED');
    retained=false;
    await progress('VERIFIED');
    retained=true;
    assert.equal((await tick()).pending,1);
    await checkPhase('VERIFIED');
    retained=false;
    await progress('COMPLETE');
    assert.equal((await tick()).attempted,0);
    await checkPhase('COMPLETE');
    assert.equal((await db.doc('accountDeletionLocks/'+a.uid).get()).exists,true);
    assert.equal((await db.doc('accountDeletionLocks/'+b.uid).get()).exists,false);
    assert.equal((await db.doc(bPath).get()).exists,true);
    assert.deepEqual(new Uint8Array(await getBytes(ref(b.storage,bPhoto))),bBytes);
    assert.deepEqual((await bChild.get()).data(),{fixture:'b'});
  }finally{
    if(tls)await tls.close();
    for(const client of clients){
      await signOut(client.auth);
      await terminate(client.db);
      await deleteApp(client.app);
    }
    await db.terminate();
    await deleteAdmin(app);
  }
});
