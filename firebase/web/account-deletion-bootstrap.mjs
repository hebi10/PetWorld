import {createExistingGoogleWebRuntime,createGoogleCredentialPrompt} from './account-deletion-google.mjs';
import {mountDeletionPage} from './account-deletion-page.mjs';

const mounted=new WeakSet();

/** Explicit, one-time mount by a reviewed same-origin host. The default static
 * preview imports no script. No configuration is accepted from the URL,
 * localStorage, a form field, or a query parameter. No live SDK is initialized
 * here, and absence of any reviewed prerequisite leaves the preview disabled. */
export function mountReviewedWebDeletionPage({root,gis,appCheck,sdk,webAppId,googleClientId,
  origin,readiness}={}) {
  if(!root||typeof root.querySelector!=='function'||!root.isConnected||
    globalThis.isSecureContext!==true||globalThis.location?.origin!==origin||
    readiness?.webHostHeadersVerified!==true||mounted.has(root))return null;
  let runtime;
  try{
    const acquireGoogleCredential=createGoogleCredentialPrompt({gis,root});
    runtime=createExistingGoogleWebRuntime({appCheck,sdk,webAppId,googleClientId,
      origin,readiness,acquireGoogleCredential});
    if(!runtime)return null;
    const dispose=mountDeletionPage(root,runtime);
    if(typeof dispose!=='function'){runtime.dispose();return null;}
    mounted.add(root);
    return dispose;
  }catch(_){
    runtime?.dispose();
    return null;
  }
}
