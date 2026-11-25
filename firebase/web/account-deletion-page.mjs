const phaseText=Object.freeze({
  LOCKED:'삭제 요청 접수 및 접근 차단',AUTH_REVOKED:'인증 접근 차단',
  RECORDS_PURGED:'서버 기록 삭제 확인',PHOTOS_PURGED:'클라우드 사진 삭제 확인',
  AUTH_DELETED:'인증 계정 삭제 확인, 남은 자료 검증 중',
  VERIFIED:'최종 보존 정책 재확인 중',COMPLETE:'서버 삭제 완료',
});
const message=Object.freeze({
  UNKNOWN:'결과를 확인하지 못했습니다. 저장한 복구 파일로 상태를 다시 확인해 주세요.',
  BUSY:'다른 확인 작업이 진행 중입니다.',
  NOT_READY:'동일 계정 인증, 복구 파일 저장 및 확인이 필요합니다.',
  RECOVERY_REQUIRED:'이미 준비한 복구 파일을 사용해 주세요.',
  STATUS_REQUIRED:'기존 요청의 상태를 확인해 주세요.',
  RESTORED:'복구 파일을 확인했습니다. 서버 상태를 조회할 수 있습니다.',
});

/** Only called by a separately configured, reviewed same-origin bootstrap.
 * Default HTML remains disabled. The page does not initiate login on load. */
export function mountDeletionPage(root,runtime) {
  if(!root||!runtime?.controller||typeof runtime.connectExisting!=='function'||
    typeof runtime.currentAccount!=='function'||typeof runtime.subscribe!=='function')return null;
  const by=id=>{const node=root.querySelector('#'+id);if(!node)throw new Error('Missing deletion screen element');return node;};
  const ui={
    availability:by('availability'),account:by('account-status'),connect:by('connect-existing'),
    password:by('recovery-password'),prepare:by('prepare-recovery'),saved:by('saved-recovery'),
    confirm:by('confirm-deletion'),submit:by('submit-deletion'),
    file:by('recovery-file'),restorePassword:by('restore-password'),restore:by('restore-recovery'),
    refresh:by('refresh-status'),status:by('result-status'),
  };
  const c=runtime.controller;
  let busy=false,connected=!!runtime.currentAccount(),uid=runtime.currentAccount();
  let encryptedReady=false,restored=false,disposed=false;
  function sync(){
    const s=c.snapshot(),ready=!busy&&!disposed;
    ui.account.textContent=connected?'기존 Google 계정 인증됨':'로그인하지 않음';
    ui.connect.disabled=!ready||connected;
    ui.password.disabled=!ready||!connected||s.hasRecovery;
    ui.prepare.disabled=!ready||!connected||s.hasRecovery;
    ui.saved.disabled=!ready||!encryptedReady;
    ui.confirm.disabled=!ready||!connected||!(ui.saved.checked||restored)||s.phase!==null;
    ui.submit.disabled=!ready||!connected||!ui.confirm.checked||!(ui.saved.checked||restored)||s.phase!==null;
    ui.file.disabled=!ready;
    ui.restorePassword.disabled=!ready;
    ui.restore.disabled=!ready;
    ui.refresh.disabled=!ready||!s.hasRecovery||!(ui.saved.checked||restored);
  }
  function show(result) {
    ui.status.textContent=result.kind==='CONFIRMED'
      ? (phaseText[result.phase]||message.UNKNOWN)+
        (result.phase==='COMPLETE'?' · 기기 기록과 외부 백업은 별도입니다.':' · 아직 전체 완료가 아닙니다.')
      : message[result.kind]||message.UNKNOWN;
  }
  function download(file){
    const url=URL.createObjectURL(new Blob([file],{type:'application/json'}));
    const link=document.createElement('a');
    link.href=url;link.download='petworld-deletion-recovery.json';
    link.click();
    setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
  async function run(action){
    if(busy||disposed)return;
    busy=true;sync();
    try{await action();}catch(_){show({kind:'UNKNOWN'});}
    finally{busy=false;sync();}
  }
  const listen=(node,event,callback)=>node.addEventListener(event,callback,{signal:events.signal});
  const events=new AbortController();
  ui.availability.textContent='삭제 요청 및 저장된 복구 파일 조회';
  listen(ui.connect,'click',()=>run(async()=>{
    connected=await runtime.connectExisting();
    ui.status.textContent=connected?'기존 계정을 확인했습니다.':'기존 계정을 확인하지 못했습니다.';
  }));
  listen(ui.prepare,'click',()=>run(async()=>{
    const password=ui.password.value;
    ui.password.value='';
    const result=await c.prepare(password);
    if(result.kind==='PREPARED'){
      download(result.file);encryptedReady=true;
      ui.status.textContent='암호화 복구 파일을 저장하고 아래 확인란을 선택해 주세요. 파일과 비밀번호가 모두 필요합니다.';
    }else show(result);
  }));
  listen(ui.saved,'change',()=>{if(ui.saved.checked)c.confirmSaved();sync();});
  listen(ui.confirm,'change',sync);
  listen(ui.submit,'click',()=>run(async()=>{
    if(!(ui.saved.checked||restored)||!ui.confirm.checked)return;
    show(await c.submit(true));
  }));
  listen(ui.restore,'click',()=>run(async()=>{
    const file=ui.file.files?.[0],password=ui.restorePassword.value;
    ui.restorePassword.value='';ui.file.value='';
    if(!file||file.size>2048){show({kind:'UNKNOWN'});return;}
    const result=await c.restore(await file.text(),password);
    restored=result.kind==='RESTORED'||restored;
    show(result);
  }));
  listen(ui.refresh,'click',()=>run(async()=>show(await c.refresh())));
  const unsubscribe=runtime.subscribe(()=>{
    const next=runtime.currentAccount();
    if(next!==uid){
      c.forget();encryptedReady=false;restored=false;
      ui.saved.checked=false;ui.confirm.checked=false;
      ui.password.value='';ui.restorePassword.value='';ui.file.value='';
      ui.status.textContent='계정이 변경되었습니다. 저장한 복구 파일이 있다면 다시 불러와 주세요.';
      uid=next;
    }
    connected=next!==null;sync();
  });
  function dispose(){if(disposed)return;disposed=true;events.abort();unsubscribe?.();c.forget();
    ui.password.value='';ui.restorePassword.value='';ui.file.value='';ui.status.textContent='';
    sync();}
  globalThis.addEventListener?.('pagehide',dispose,{once:true,signal:events.signal});
  sync();
  return dispose;
}
