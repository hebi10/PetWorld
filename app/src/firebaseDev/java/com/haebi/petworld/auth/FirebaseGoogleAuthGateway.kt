package com.haebi.petworld.auth

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex

sealed interface GoogleLoginOutcome {
    data class SignedIn(val uid: String, val email: String?) : GoogleLoginOutcome
    data object Cancelled : GoogleLoginOutcome
    data object Busy : GoogleLoginOutcome
    data object MissingConfiguration : GoogleLoginOutcome
    data object InvalidCredential : GoogleLoginOutcome
    data object NetworkUnavailable : GoogleLoginOutcome
    data object ProviderUnavailable : GoogleLoginOutcome
    data object AuthenticationRejected : GoogleLoginOutcome
}

class FirebaseGoogleAuthGateway(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val requestGate = Mutex()

    fun currentAccount(): GoogleLoginOutcome.SignedIn? = firebaseAuth.currentUser?.let { user ->
        GoogleLoginOutcome.SignedIn(user.uid, user.email)
    }

    suspend fun signIn(activity: Activity): GoogleLoginOutcome {
        if (!requestGate.tryLock()) return GoogleLoginOutcome.Busy

        try {
            val clientId = webClientId(activity) ?: return GoogleLoginOutcome.MissingConfiguration
            val googleOption = GetSignInWithGoogleOption.Builder(clientId).build()
            val credential = CredentialManager.create(activity).getCredential(
                context = activity,
                request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build(),
            ).credential

            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return GoogleLoginOutcome.InvalidCredential
            }

            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val user = signInWithFirebase(googleIdToken)
                ?: return GoogleLoginOutcome.AuthenticationRejected
            return GoogleLoginOutcome.SignedIn(user.uid, user.email)
        } catch (_: GetCredentialCancellationException) {
            return GoogleLoginOutcome.Cancelled
        } catch (_: GoogleIdTokenParsingException) {
            return GoogleLoginOutcome.InvalidCredential
        } catch (_: FirebaseNetworkException) {
            return GoogleLoginOutcome.NetworkUnavailable
        } catch (_: FirebaseAuthException) {
            return GoogleLoginOutcome.AuthenticationRejected
        } catch (_: GetCredentialException) {
            return GoogleLoginOutcome.ProviderUnavailable
        } catch (_: Exception) {
            return GoogleLoginOutcome.ProviderUnavailable
        } finally {
            requestGate.unlock()
        }
    }

    suspend fun verifyLiveSession(): Boolean {
        val expectedUid = firebaseAuth.currentUser?.uid ?: return false
        return suspendCancellableCoroutine { continuation: CancellableContinuation<Boolean> ->
            val current = firebaseAuth.currentUser
            if (current == null || current.uid != expectedUid) {
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            current.getIdToken(true).addOnCompleteListener { result ->
                if (!continuation.isActive) return@addOnCompleteListener
                continuation.resume(
                    result.isSuccessful &&
                        !result.result?.token.isNullOrBlank() &&
                        firebaseAuth.currentUser?.uid == expectedUid
                )
            }
        }
    }

    suspend fun signOut(activity: Activity) {
        firebaseAuth.signOut()
        runCatching {
            CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
        }
    }

    private fun webClientId(activity: Activity): String? {
        if (activity.packageName != "com.haebi.petworld.dev") return null
        val firebase = FirebaseApp.getApps(activity)
            .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME } ?: return null
        if (firebase.options.projectId != "petworld-dev") return null
        val resourceId = activity.resources.getIdentifier(
            "default_web_client_id",
            "string",
            activity.packageName,
        )
        if (resourceId == 0) return null
        return activity.getString(resourceId).takeIf { it.isNotBlank() }
    }

    private suspend fun signInWithFirebase(idToken: String): FirebaseUser? =
        suspendCancellableCoroutine { continuation: CancellableContinuation<FirebaseUser?> ->
            firebaseAuth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(task.result.user)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("Google authentication failed")
                        )
                    }
                }
        }
}
