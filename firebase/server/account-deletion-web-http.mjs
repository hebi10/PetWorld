import {TextDecoder} from 'node:util';
import {DeletionDenied} from './account-deletion.mjs';

export const WEB_AUTH_PATH='/account-deletion/web-auth';
const phases=new Set(['LOCKED','AUTH_REVOKED','RECORDS_PURGED','PHOTOS_PURGED','AUTH_DELETED','VERIFIED','COMPLETE']);
const hex=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const exact=(value,keys)=>value&&typeof value==='object'&&!Array.isArray(value)&&
  Object.keys(value).sort().join(',')===[...keys].sort().join(',');
const bearer=value=>typeof value==='string'&&value.length<=8192&&/^Bearer [A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(value);
const reply=(statusCode,body)=>({statusCode,body,headers:{
  'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store, private, max-age=0',
  'Pragma':'no-cache','Referrer-Policy':'no-referrer','X-Content-Type-Options':'nosniff',
}});
const error=(statusCode,code)=>reply(statusCode,{code});

/** Distinct, opt-in browser identity endpoint; the original Firebase Android
 * request and status routes are unchanged. All actions require web admission. */
export function createWebExistingAuthHandler({service,admit,fallback}={}) {
  if(typeof fallback!=='function')throw new DeletionDenied();
  return async input=>{
    if(input?.path!==WEB_AUTH_PATH)return fallback(input);
    if(!service||typeof admit!=='function')return error(404,'NOT_FOUND');
    if(input.method!=='POST')return {...error(405,'METHOD_NOT_ALLOWED'),headers:{...error(405,'METHOD_NOT_ALLOWED').headers,Allow:'POST'}};
    if(!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(input.contentType||''))return error(415,'JSON_REQUIRED');
    if(!Buffer.isBuffer(input.rawBody)||input.rawBody.length===0||input.rawBody.length>2048)return error(400,'INVALID_BODY');
    let body;
    try{body=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(input.rawBody));}
    catch(_){return error(400,'INVALID_BODY');}
    const action=body?.action;
    if(action==='challenge'){
      if(!exact(body,['action','purpose','jobId'])||
        !(body.purpose==='identify'&&body.jobId===null||body.purpose==='submit'&&hex(body.jobId))||
        input.authorization!==undefined)return error(400,'INVALID_BODY');
    }else if(action==='identify'){
      if(!exact(body,['action']))return error(400,'INVALID_BODY');
    }else if(action==='submit'){
      if(!exact(body,['action','jobId','confirmation','statusTicket'])||!hex(body.jobId)||
        body.confirmation!=='DELETE_MY_PETWORLD_ACCOUNT'||typeof body.statusTicket!=='string'||
        !/^[A-Za-z0-9_-]{43}$/.test(body.statusTicket))return error(400,'INVALID_BODY');
    }else return error(400,'INVALID_BODY');
    if(action!=='challenge'&&!bearer(input.authorization))return error(401,'AUTHORIZATION_REQUIRED');
    try{
      const admission=await admit({route:'web-auth',appCheckToken:input.appCheckToken,ingress:input.ingress});
      if(admission==='RATE_LIMITED')return error(429,'RATE_LIMITED');
      if(admission!=='ALLOW')return error(403,'DENIED');
      if(action==='challenge'){
        const result=await service.challenge({purpose:body.purpose,jobId:body.jobId});
        if(typeof result?.nonce!=='string'||!/^[A-Za-z0-9_-]{43}$/.test(result.nonce))return error(503,'RESULT_UNKNOWN');
        return reply(200,{nonce:result.nonce});
      }
      if(action==='identify'){
        const result=await service.identify(input.authorization.slice(7));
        return hex(result?.jobId)?reply(200,{jobId:result.jobId}):error(503,'RESULT_UNKNOWN');
      }
      const result=await service.submit(input.authorization.slice(7),{
        jobId:body.jobId,confirmation:body.confirmation,statusTicket:body.statusTicket,
      });
      if(result?.jobId!==body.jobId||!phases.has(result?.phase))return error(503,'RESULT_UNKNOWN');
      return reply(202,{jobId:result.jobId,phase:result.phase});
    }catch(failure){
      return failure instanceof DeletionDenied?error(403,'DENIED'):error(503,'RESULT_UNKNOWN');
    }
  };
}
