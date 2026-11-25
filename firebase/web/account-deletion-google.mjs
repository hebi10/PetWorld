import {createWebDeletionController,createExistingGoogleWebTransport} from './account-deletion-client.mjs';

const validJob=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const clientValid=value=>typeof value==='string'&&/^[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$/.test(value);
function untrustedClaims(token){
  if(typeof token!=='string'||token.length>8185||!/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token))
    return null;
  try{
    const part=token.split('.')[1];
    const bytes=Uint8Array.from(atob(part.replaceAll('-','+').replaceAll('_','/')),ch=>ch.charCodeAt(0));
    return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));
  }catch(_){return null;}
}

/**
 * Only mount after reviewing the dedicated GIS OAuth client, actual auth_time
 * claims, App Check, same-origin security headers and server bootstrap.
 * The browser retains an opaque deletion job ID, not a Firebase Auth session.
 */
export function createExistingGoogleWebRuntime({appCheck,sdk,webAppId,googleClientId,
  origin,readiness,acquireGoogleCredential,currentOrigin=globalThis.location?.origin,fetcher}={}) {
  if(['webDeletionVerified','existingAccountSignInVerified','appCheckVerified',
    'sameOriginVerified','recoveryVerified','googleAuthTimeVerified','googleCredentialUiVerified']
    .some(flag=>readiness?.[flag]!==true)||
    !clientValid(googleClientId)||!/^1:[0-9]+:web:[a-f0-9]+$/.test(webAppId||'')||
    appCheck?.app?.options?.projectId!=='petworld-dev'||appCheck.app.options.appId!==webAppId||
    typeof sdk?.getToken!=='function'||typeof acquireGoogleCredential!=='function'||currentOrigin!==origin)
    return null;
  const transport=createExistingGoogleWebTransport({origin,currentOrigin,fetcher,
    appCheck:async()=> (await sdk.getToken(appCheck,false)).token});
  let identity=null,closed=false,busy=false,generation=0;
  const observers=new Set();
  const notify=()=>observers.forEach(listener=>listener());
  const currentAccount=()=>identity;
  async function googleProof(purpose,expected,allowed){
    const nonce=await transport.challenge(purpose,expected,allowed);
    if(!allowed())return null;
    const token=await acquireGoogleCredential({clientId:googleClientId,nonce,purpose});
    if(!allowed())return null;
    const claims=untrustedClaims(token);
    // Advisory browser checks only. The server independently verifies Google's
    // signature, nonce, audience, auth_time and existing provider mapping.
    if(!claims||claims.nonce!==nonce||claims.aud!==googleClientId)return null;
    return {token,claims};
  }
  const controller=createWebDeletionController({currentAccount,
    resolveJobId:async job=>validJob(job)?job:null,
    matchesAuth:(auth,job)=>auth?.jobId===job,
    reauthenticate:async job=>{
      const version=generation;
      const allowed=()=>!closed&&version===generation&&identity===job;
      if(!allowed())return null;
      const proof=await googleProof('submit',job,allowed);
      if(!proof||!Number.isSafeInteger(proof.claims.auth_time))return null;
      return {jobId:job,idToken:proof.token,authTimeSeconds:proof.claims.auth_time};
    },
    exchange:transport.exchange,
  });
  return Object.freeze({
    controller,currentAccount,
    async connectExisting(){
      if(closed||busy||identity!==null)return false;
      busy=true;
      const version=generation,allowed=()=>!closed&&version===generation&&identity===null;
      try{
        const proof=await googleProof('identify',null,allowed);
        if(!proof||!allowed())return false;
        const job=await transport.identify(proof.token,allowed);
        if(!allowed()||!validJob(job))return false;
        identity=job;notify();return true;
      }catch(_){return false;}
      finally{busy=false;}
    },
    subscribe(listener){observers.add(listener);return ()=>observers.delete(listener);},
    dispose(){closed=true;generation++;identity=null;controller.forget();observers.clear();},
  });
}

/** Explicit user action via Google's rendered button, no One Tap or automatic
 * sign-in. A stale callback cannot satisfy the next nonce. The host supplies
 * the GIS library only after reviewing its CSP and authorized domain. */
export function createGoogleCredentialPrompt({gis,root,document=globalThis.document}={}) {
  if(!root||!document||typeof gis?.initialize!=='function'||typeof gis?.renderButton!=='function')
    throw new Error('Google credential UI unavailable');
  let pending=false;
  return ({clientId,nonce,purpose})=>new Promise(resolve=>{
    if(pending||!clientValid(clientId)||!/^[A-Za-z0-9_-]{43}$/.test(nonce)||
      !['identify','submit'].includes(purpose)){resolve(null);return;}
    pending=true;
    const dialog=document.createElement('dialog'),title=document.createElement('h2');
    const detail=document.createElement('p'),container=document.createElement('div');
    const cancel=document.createElement('button');
    title.textContent=purpose==='submit'?'Google 계정 다시 인증':'기존 Google 계정 확인';
    detail.textContent=purpose==='submit'
      ?'최근 Google 인증이 확인되어야 삭제 요청을 할 수 있습니다. 다른 계정을 선택하면 요청하지 않습니다.'
      :'이미 PetWorld에 연결된 Google 계정만 확인합니다. 새 계정을 만들지 않습니다.';
    cancel.type='button';cancel.textContent='취소';
    dialog.append(title,detail,container,cancel);root.append(dialog);
    let done=false,timer;
    const finish=value=>{
      if(done)return;
      done=true;pending=false;clearTimeout(timer);
      globalThis.removeEventListener?.('pagehide',abort);
      if(dialog.open)dialog.close();
      dialog.remove();
      resolve(value);
    };
    const abort=()=>finish(null);
    cancel.addEventListener('click',abort);
    dialog.addEventListener('cancel',event=>{event.preventDefault();abort();});
    globalThis.addEventListener?.('pagehide',abort,{once:true});
    timer=setTimeout(abort,240000);
    try{
      gis.initialize({client_id:clientId,nonce,ux_mode:'popup',auto_select:false,
        essential_claims:'auth_time, amr',callback:response=>{
          const token=response?.credential;
          if(untrustedClaims(token)?.nonce===nonce)finish(token);
        }});
      gis.renderButton(container,{type:'standard',theme:'outline',size:'large',text:'continue_with',locale:'ko'});
      dialog.showModal();
    }catch(_){finish(null);}
  });
}
