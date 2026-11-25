const PHASES=['LOCKED','AUTH_REVOKED','RECORDS_PURGED','PHOTOS_PURGED','AUTH_DELETED','VERIFIED','COMPLETE'];
const encoder=new TextEncoder();
const decoder=new TextDecoder('utf-8',{fatal:true});
const context=encoder.encode('PetWorld web deletion recovery v1');
const iterations=600_000;
const jobValid=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const headerValid=value=>typeof value==='string'&&value.length>=1&&value.length<=8185&&/^[A-Za-z0-9._-]+$/.test(value);
const exact=(value,keys)=>value!==null&&typeof value==='object'&&!Array.isArray(value)&&
  Object.keys(value).sort().join(',')===[...keys].sort().join(',');
const fail=()=>{throw new Error('Deletion result is unavailable');};
const encode=bytes=>btoa(String.fromCharCode(...bytes)).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'');
function decode(text,size) {
  if(typeof text!=='string'||!text.length||text.length>2048||!/^[A-Za-z0-9_-]+$/.test(text))fail();
  const raw=Uint8Array.from(atob(text.replaceAll('-','+').replaceAll('_','/')),c=>c.charCodeAt(0));
  if((size!==undefined&&raw.length!==size)||encode(raw)!==text)fail();
  return raw;
}
function validTicket(ticket) {
  const bytes=decode(ticket,32);
  bytes.fill(0);
}
function passphrase(value) {
  if(typeof value!=='string'||value.length<12||value.length>256||!value.trim())fail();
  return encoder.encode(value);
}
function assertAnchor(anchor) {
  if(!exact(anchor,['jobId','ticket'])||!jobValid(anchor.jobId))fail();
  validTicket(anchor.ticket);
}
async function key(password,salt,usage) {
  const bytes=passphrase(password);
  try {
    const material=await crypto.subtle.importKey('raw',bytes,'PBKDF2',false,['deriveKey']);
    return await crypto.subtle.deriveKey({name:'PBKDF2',hash:'SHA-256',salt,iterations},material,
      {name:'AES-GCM',length:256},false,[usage]);
  } finally {bytes.fill(0);}
}
async function jobId(uid) {
  if(typeof uid!=='string'||!uid.length||uid.length>128||uid.includes('/')||uid==='.'||uid==='..')fail();
  const hash=await crypto.subtle.digest('SHA-256',encoder.encode('petworld-account-deletion-v1\0'+uid));
  return [...new Uint8Array(hash)].map(b=>b.toString(16).padStart(2,'0')).join('');
}

/** The file contains an encrypted capability, never Firebase ID credentials or an email/UID. */
async function encryptRecovery(anchor,password) {
  assertAnchor(anchor);
  const salt=crypto.getRandomValues(new Uint8Array(16)),iv=crypto.getRandomValues(new Uint8Array(12));
  const plain=encoder.encode(JSON.stringify(anchor));
  try{
    const secret=await key(password,salt,'encrypt');
    const encrypted=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:context},secret,plain));
    return JSON.stringify({v:1,salt:encode(salt),iv:encode(iv),ciphertext:encode(encrypted)});
  }finally{plain.fill(0);}
}
async function decryptRecovery(file,password) {
  if(typeof file!=='string'||encoder.encode(file).length>2048)fail();
  const data=JSON.parse(file);
  if(!exact(data,['v','salt','iv','ciphertext'])||data.v!==1)fail();
  const salt=decode(data.salt,16),iv=decode(data.iv,12),ciphertext=decode(data.ciphertext);
  if(ciphertext.length<32||ciphertext.length>256)fail();
  const secret=await key(password,salt,'decrypt');
  const plain=new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM',iv,additionalData:context},secret,ciphertext));
  try{
    const anchor=JSON.parse(decoder.decode(plain));
    assertAnchor(anchor);
    return anchor;
  }finally{plain.fill(0);}
}
function receipt(value,expected,previous) {
  if(!exact(value,['jobId','phase'])||value.jobId!==expected||!PHASES.includes(value.phase)||
      previous!==null&&PHASES.indexOf(value.phase)<PHASES.indexOf(previous))fail();
  return value.phase;
}
function originOf(value) {
  const url=new URL(value);
  if(url.protocol!=='https:'||url.origin!==value||url.port||url.username||url.password||
    !/^(?:[a-z0-9-]+\.)+[a-z][a-z0-9-]*$/.test(url.hostname)||
    url.hostname.endsWith('.localhost')||url.hostname.endsWith('.local'))fail();
  return url.origin;
}

/** Same-origin only, no redirects/cookies/browser persistence or secret-bearing error text. */
export function createWebDeletionTransport({origin,currentOrigin,appCheck,fetcher=globalThis.fetch}={}) {
  if(originOf(origin)!==currentOrigin||typeof appCheck!=='function'||typeof fetcher!=='function')fail();
  return async function exchange(route,bearer,body,expected) {
    if(!['request','status'].includes(route)||!headerValid(bearer)||!jobValid(expected))fail();
    const controller=new AbortController();
    const timer=setTimeout(()=>controller.abort(),15000);
    try{
      const appToken=await appCheck();
      if(!headerValid(appToken)||controller.signal.aborted)fail();
      const raw=JSON.stringify(body);
      if(encoder.encode(raw).length>2048)fail();
      const response=await fetcher(origin+'/account-deletion/'+route,{
        method:'POST',redirect:'error',credentials:'omit',cache:'no-store',referrerPolicy:'no-referrer',
        headers:{'Content-Type':'application/json; charset=utf-8','Authorization':'Bearer '+bearer,
          'X-Firebase-AppCheck':appToken},body:raw,signal:controller.signal,
      });
      if(response.status!==(route==='request'?202:200)||response.redirected||
          !/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(response.headers.get('content-type')||'')||
          !response.headers.get('cache-control')?.split(',').some(v=>v.trim().toLowerCase()==='no-store')||
          !response.body)fail();
      const reader=response.body.getReader();
      const bytes=new Uint8Array(2048);let length=0;
      try{
        while(true){
          const {done,value}=await reader.read();
          if(done)break;
          if(length+value.length>bytes.length)fail();
          bytes.set(value,length);length+=value.length;
        }
        const value=JSON.parse(decoder.decode(bytes.subarray(0,length)));
        receipt(value,expected,null);
        return value;
      }finally{
        bytes.fill(0);
        await reader.cancel().catch(()=>{});
      }
    }finally{clearTimeout(timer);}
  };
}

/**
 * Trusted runtime only: existing Google account session, fresh reauth, same-origin
 * App Check transport. No automatic sign-in, account creation, localStorage or new
 * recovery ticket after an uncertain request. UI sees phase and encrypted file only.
 */
export function createWebDeletionController({currentAccount,reauthenticate,exchange,now=Date.now}={}) {
  if([currentAccount,reauthenticate,exchange,now].some(fn=>typeof fn!=='function'))fail();
  let anchor=null,phase=null,busy=false,saved=false,attempted=false,version=0;
  const snapshot=()=>Object.freeze({hasRecovery:anchor!==null,phase,attempted,busy});
  const operation=async action=>{
    if(busy)return {kind:'BUSY'};
    busy=true;
    const token=version;
    try{return await action(()=>token===version);}
    catch(_){return {kind:'UNKNOWN'};}
    finally{busy=false;}
  };
  return Object.freeze({
    snapshot,
    // Invalidate in-memory data when the document exits. No credentials persisted.
    forget(){version++;anchor=null;phase=null;saved=false;attempted=false;},
    prepare(password){return operation(async valid=>{
      if(anchor!==null)return {kind:'RECOVERY_REQUIRED'};
      const uid=currentAccount();
      const id=await jobId(uid);
      const bytes=crypto.getRandomValues(new Uint8Array(32));
      const next={jobId:id,ticket:encode(bytes)};
      bytes.fill(0);
      const file=await encryptRecovery(next,password);
      if(!valid()||currentAccount()!==uid)return {kind:'UNKNOWN'};
      anchor=next;
      return {kind:'PREPARED',file};
    });},
    restore(file,password){return operation(async valid=>{
      const next=await decryptRecovery(file,password);
      if(!valid()||anchor!==null&&(anchor.jobId!==next.jobId||anchor.ticket!==next.ticket))
        return {kind:'UNKNOWN'};
      anchor=next;saved=true;attempted=true;
      return {kind:'RESTORED'};
    });},
    confirmSaved(){if(!busy&&anchor!==null)saved=true;},
    submit(confirmed){return operation(async valid=>{
      if(!confirmed||!saved||anchor===null)return {kind:'NOT_READY'};
      if(phase!==null)return {kind:'STATUS_REQUIRED'};
      const target=anchor,uid=currentAccount();
      if(await jobId(uid)!==target.jobId||!valid()||currentAccount()!==uid)return {kind:'NOT_READY'};
      const auth=await reauthenticate(uid);
      const seconds=Math.floor(now()/1000);
      if(!valid()||currentAccount()!==uid||auth?.uid!==uid||!headerValid(auth.idToken)||
        !Number.isSafeInteger(auth.authTimeSeconds)||auth.authTimeSeconds>seconds||
        seconds-auth.authTimeSeconds>300)return {kind:'NOT_READY'};
      attempted=true;
      const next=await exchange('request',auth.idToken,
        {confirmation:'DELETE_MY_PETWORLD_ACCOUNT',statusTicket:target.ticket},target.jobId);
      if(!valid()||currentAccount()!==uid)return {kind:'UNKNOWN'};
      phase=receipt(next,target.jobId,phase);
      return {kind:'CONFIRMED',phase};
    });},
    refresh(){return operation(async valid=>{
      if(anchor===null||!saved)return {kind:'NOT_READY'};
      const target=anchor;
      const next=await exchange('status',target.ticket,{jobId:target.jobId},target.jobId);
      if(!valid())return {kind:'UNKNOWN'};
      phase=receipt(next,target.jobId,phase);
      return {kind:'CONFIRMED',phase};
    });},
  });
}
