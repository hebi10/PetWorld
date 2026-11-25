import {createWebDeletionController,createWebDeletionTransport} from './account-deletion-client.mjs';

/** Requires an already reviewed existing-account-only sign-in implementation.
 * Never call ordinary first-time signInWithPopup here: it may create an account.
 * No live Firebase app/config or App Check provider is initialized by this module. */
export function createReviewedWebDeletionRuntime({
  auth,appCheck,sdk,webAppId,origin,readiness,beginExistingSignIn,
}={}) {
  if(readiness?.webDeletionVerified!==true||readiness?.existingAccountSignInVerified!==true||
    readiness?.memoryAuthVerified!==true||readiness?.appCheckVerified!==true||
    readiness?.sameOriginVerified!==true||readiness?.recoveryVerified!==true||
    typeof beginExistingSignIn!=='function'||globalThis.location?.origin!==origin||
    !/^1:[0-9]+:web:[a-f0-9]+$/.test(webAppId||'')||
    !auth?.app||auth.app!==appCheck?.app||auth.app.options?.projectId!=='petworld-dev'||
    auth.app.options?.appId!==webAppId) return null;
  if(['GoogleAuthProvider','reauthenticateWithPopup','getIdTokenResult','getToken','onAuthStateChanged']
    .some(name=>typeof sdk?.[name]!=='function')) return null;
  const currentAccount=()=>{
    const user=auth.currentUser;
    return user&&!user.isAnonymous&&user.providerData?.some(p=>p.providerId==='google.com')?user.uid:null;
  };
  const reauthenticate=async uid=>{
    const user=auth.currentUser;
    if(!user||currentAccount()!==uid)return null;
    const provider=new sdk.GoogleAuthProvider();
    provider.setCustomParameters({prompt:'select_account'});
    const result=await sdk.reauthenticateWithPopup(user,provider);
    if(result.user?.uid!==uid||currentAccount()!==uid)return null;
    const token=await sdk.getIdTokenResult(result.user,true);
    if(currentAccount()!==uid||token.signInProvider!=='google.com')return null;
    return {uid,idToken:token.token,authTimeSeconds:Math.floor(Date.parse(token.authTime)/1000)};
  };
  const exchange=createWebDeletionTransport({origin,currentOrigin:globalThis.location.origin,
    appCheck:async()=> (await sdk.getToken(appCheck,false)).token});
  const controller=createWebDeletionController({currentAccount,reauthenticate,exchange});
  return Object.freeze({
    controller,currentAccount,
    async connectExisting(){await beginExistingSignIn();return currentAccount()!==null;},
    subscribe(listener){return sdk.onAuthStateChanged(auth,listener);},
  });
}
