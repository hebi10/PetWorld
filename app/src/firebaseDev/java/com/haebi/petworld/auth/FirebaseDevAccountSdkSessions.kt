package com.haebi.petworld.auth

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.haebi.petworld.data.account.AccountCacheTransitionDecision
import com.haebi.petworld.data.account.AccountCacheTransitionEvidence
import com.haebi.petworld.data.account.AccountDeletionJournal
import com.haebi.petworld.data.account.AccountSdkRouteGate
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.accountMemoryAppName
import com.haebi.petworld.sync.FirebaseDevSyncWork
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal enum class IsolatedSdkCloseResult { CLOSED, UNCONFIRMED }

private fun googleUid(auth: FirebaseAuth): String? {
    val user = auth.currentUser ?: return null
    return user.uid.takeIf { !user.isAnonymous && user.providerData.any { p -> p.providerId == "google.com" } }
}

/** Adapter clients are bound at construction, never switched mid-operation. */
internal class FirebaseDevSdkBinding internal constructor(
    val app: FirebaseApp,
    val auth: FirebaseAuth,
    private val routePermits: (LocalAccountScope.SignedIn) -> Boolean,
    private val primaryAuth: FirebaseAuth,
    configuredFirestore: FirebaseFirestore? = null,
) {
    val firestore: FirebaseFirestore by lazy { configuredFirestore ?: FirebaseFirestore.getInstance(app) }
    val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance(app) }

    fun currentUid(account: LocalAccountScope.SignedIn): String? = runCatching {
        val uid = googleUid(auth) ?: return null
        uid.takeIf {
            routePermits(account) && googleUid(primaryAuth) == uid &&
                LocalAccountScope.fromFirebaseUid(uid) == account
        }
    }.getOrNull()
}

internal class FirebaseDevIsolatedSdkSession internal constructor(
    private val context: Context,
    private val lease: AccountSdkRouteGate.Lease,
    private val routes: AccountSdkRouteGate,
    private val primaryAuth: FirebaseAuth,
    private val isolatedAuth: FirebaseAuth,
    private val client: FirebaseFirestore,
    val binding: FirebaseDevSdkBinding,
    private val expectedUid: String,
) {
    private val closeGate = Mutex()
    private var closeResult: IsolatedSdkCloseResult? = null
    private val authListener = FirebaseAuth.AuthStateListener {
        if (!sameAccount()) routes.retire(lease)
    }

    internal fun sameAccount(): Boolean = runCatching {
        googleUid(primaryAuth) == expectedUid && googleUid(isolatedAuth) == expectedUid
    }.getOrDefault(false)

    internal fun observeAuth() {
        primaryAuth.addAuthStateListener(authListener)
        isolatedAuth.addAuthStateListener(authListener)
    }

    internal fun retire() {
        routes.retire(lease)
        primaryAuth.removeAuthStateListener(authListener)
        isolatedAuth.removeAuthStateListener(authListener)
    }

    /**
     * Explicit close before sign-out, while the same account is authenticated.
     * No new requests, wait for actual SDK callbacks and pending writes before
     * terminating only the isolated memory client. Never clear persistence or
     * claim completion on timeout. The retired route cannot reopen this process.
     */
    suspend fun close(): IsolatedSdkCloseResult = closeGate.withLock {
        closeResult?.let { return@withLock it }
        // Set pessimistic result before any suspend: a cancelled close cannot be retried blindly.
        closeResult = IsolatedSdkCloseResult.UNCONFIRMED
        routes.retire(lease)
        try {
            val closed = withTimeoutOrNull(45_000L) {
                if (!sameAccount()) return@withTimeoutOrNull false
                if (!FirebaseDevSyncWork.pauseAndDrainForDeletion(context, lease.account, 20_000L) ||
                    !sameAccount()) return@withTimeoutOrNull false
                client.waitForPendingWrites().awaitLifecycle()
                if (!sameAccount() || !FirebaseDevCloudWork.tracker.snapshot(lease.account).drained)
                    return@withTimeoutOrNull false
                client.terminate().awaitLifecycle()
                if (!sameAccount()) return@withTimeoutOrNull false
                isolatedAuth.signOut()
                isolatedAuth.currentUser == null && googleUid(primaryAuth) == expectedUid
            } == true
            if (closed) closeResult = IsolatedSdkCloseResult.CLOSED
            requireNotNull(closeResult)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IsolatedSdkCloseResult.UNCONFIRMED
        } finally {
            retire()
        }
    }
}

/**
 * Opt-in dev bootstrap only. Current UI does not construct/authenticate a
 * named app or supply reviewed migration evidence. All default adapters keep
 * their original route until an explicit session is reserved.
 */
internal object FirebaseDevAccountSdkSessions {
    private val routes = AccountSdkRouteGate()
    private val lock = Any()
    private val sessions = mutableMapOf<LocalAccountScope.SignedIn, FirebaseDevIsolatedSdkSession>()

    fun binding(): FirebaseDevSdkBinding {
        val app = FirebaseApp.getInstance()
        val primaryAuth = FirebaseAuth.getInstance(app)
        val scope = googleUid(primaryAuth)?.let(LocalAccountScope::fromFirebaseUid) as? LocalAccountScope.SignedIn
        val named = synchronized(lock) { scope?.let { sessions[it] } }
        return named?.binding ?: FirebaseDevSdkBinding(app, primaryAuth, routes::permitsLegacy, primaryAuth)
    }

    fun canUseCloud(account: LocalAccountScope.SignedIn): Boolean =
        routes.permitsLegacy(account) ||
            synchronized(lock) { sessions[account]?.binding }?.currentUid(account) != null

    private fun idle(account: LocalAccountScope.SignedIn): Boolean =
        FirebaseDevCloudWork.tracker.snapshot(account).let {
            !it.blocked && it.workers == 0 && it.imports == 0 && it.reviews == 0 && it.sdkTasks == 0
        }

    /** The named app must already have undergone an explicit, reviewed same-UID
     * Google sign-in. Never copy credentials, create an account or auto-sign-in. */
    suspend fun open(
        context: Context,
        account: LocalAccountScope.SignedIn,
        isolatedApp: FirebaseApp,
        evidence: AccountCacheTransitionEvidence,
    ): FirebaseDevIsolatedSdkSession? {
        val appContext = context.applicationContext
        if (appContext.packageName != "com.haebi.petworld.dev" ||
            evidence.decide(account) != AccountCacheTransitionDecision.PREPARE_ISOLATED_MEMORY_CLIENT)
            return null
        val primary = FirebaseApp.getInstance()
        if (primary.options.projectId != "petworld-dev" ||
            isolatedApp.name != accountMemoryAppName(account) ||
            isolatedApp.options.projectId != "petworld-dev" ||
            isolatedApp.options != primary.options) return null
        val primaryAuth = FirebaseAuth.getInstance(primary)
        val isolatedAuth = FirebaseAuth.getInstance(isolatedApp)
        val uid = googleUid(primaryAuth) ?: return null
        if (LocalAccountScope.fromFirebaseUid(uid) != account ||
            googleUid(isolatedAuth) != uid ||
            AccountDeletionJournal(appContext).blocksCloud(account) || !idle(account)) return null
        val lease = routes.reserve(account) ?: return null
        var prepared: FirebaseDevIsolatedSdkSession? = null
        var active = false
        try {
            if (!idle(account)) return null
            val client = FirebaseDevAccountMemoryCache.prepare(appContext,account,isolatedApp,evidence)
                ?: return null
            val binding = FirebaseDevSdkBinding(isolatedApp,isolatedAuth,
                { routes.permits(lease,it) },primaryAuth,client)
            val session = FirebaseDevIsolatedSdkSession(appContext,lease,routes,primaryAuth,isolatedAuth,client,binding,uid)
            prepared = session
            session.observeAuth()
            val verified = withTimeoutOrNull(20_000L) {
                for (auth in listOf(primaryAuth,isolatedAuth)) {
                    if (!session.sameAccount()) return@withTimeoutOrNull false
                    val user = auth.currentUser ?: return@withTimeoutOrNull false
                    val token = awaitTrackedFirebaseTask<GetTokenResult>(account,
                        permitted = { session.sameAccount() && !AccountDeletionJournal(appContext).blocksCloud(account) }
                    ) { user.getIdToken(true) }
                    if (token.token.isNullOrBlank() || !session.sameAccount()) return@withTimeoutOrNull false
                }
                true
            } == true
            if (!verified || !idle(account) || !session.sameAccount() ||
                AccountDeletionJournal(appContext).blocksCloud(account)) return null
            synchronized(lock) {
                if (!routes.activate(lease)) return null
                sessions[account] = session
                active = true
            }
            return session
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            if (!active) {
                routes.retire(lease)
                prepared?.retire()
            }
        }
    }
}

/** A timeout cancels only this waiter, not the underlying SDK operation. */
private suspend fun <T> Task<T>.awaitLifecycle(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(Executor { it.run() }) { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(IllegalStateException("Firebase lifecycle result unconfirmed"))
    }
}
