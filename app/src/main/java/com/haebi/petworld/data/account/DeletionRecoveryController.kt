package com.haebi.petworld.data.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun deletionJobId(uid: String): String {
    require(uid.isNotBlank() && uid.length <= 128 && '/' !in uid && uid != "." && uid != "..")
    return MessageDigest.getInstance("SHA-256")
        .digest(("petworld-account-deletion-v1\u0000" + uid).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Secret-bearing values must not appear in UI, logs or persisted plaintext. */
internal class DeletionRequestState private constructor(
    val account: LocalAccountScope.SignedIn,
    val jobId: String,
    internal val statusTicket: String,
    val receipt: AccountDeletionReceipt?,
) {
    override fun toString() = "DeletionRequestState(redacted)"

    companion object {
        fun new(uid: String): DeletionRequestState {
            val account = LocalAccountScope.fromFirebaseUid(uid) as LocalAccountScope.SignedIn
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            val ticket = try { Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) }
                finally { bytes.fill(0) }
            return DeletionRequestState(account, deletionJobId(uid), ticket, null)
        }

        fun restore(account: LocalAccountScope.SignedIn, jobId: String, ticket: String,
            phase: String?): DeletionRequestState {
            require(jobId.matches(Regex("[0-9a-f]{64}")))
            require(ticket.matches(Regex("[A-Za-z0-9_-]{43}")))
            val bytes = Base64.getUrlDecoder().decode(ticket)
            try {
                require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == ticket)
            } finally { bytes.fill(0) }
            val receipt = phase?.let { requireNotNull(AccountDeletionReceipt.fromServer(jobId, it)) }
            return DeletionRequestState(account, jobId, ticket, receipt)
        }
    }

    fun accept(next: AccountDeletionReceipt): DeletionRequestState? {
        if (next.jobId != jobId || (receipt != null && receipt.accept(next) == null)) return null
        return DeletionRequestState(account, jobId, statusTicket, next)
    }
}

internal interface DeletionStateStore {
    /** Missing is null; unreadable state must throw, never be treated as a new request. */
    fun read(account: LocalAccountScope.SignedIn): DeletionRequestState?
    fun write(state: DeletionRequestState)
    fun blocksCloud(account: LocalAccountScope.SignedIn): Boolean
}

internal interface DeletionRequestTransport {
    suspend fun request(freshIdToken: String, statusTicket: String): AccountDeletionReceipt
    suspend fun status(jobId: String, statusTicket: String): AccountDeletionReceipt
}

internal sealed interface DeletionRecoveryResult {
    data object NotReady : DeletionRecoveryResult
    data object StateUnknown : DeletionRecoveryResult
    data class Confirmed(val receipt: AccountDeletionReceipt) : DeletionRecoveryResult
}

/**
 * Inactive controller contract: callers must provide independently verified readiness,
 * same-account recent auth, a secure store, and a verified HTTPS transport.
 * State is durable before transmission; uncertain failures reuse the exact same ticket.
 * No local purge is performed. A confirmed COMPLETE is server completion only.
 */
internal class DeletionRecoveryController(
    private val store: DeletionStateStore,
    private val transport: DeletionRequestTransport,
    private val enrollment: DeletionRecoveryEnrollment = DeletionRecoveryEnrollment { false },
) {
    private val mutex = Mutex()

    suspend fun submit(uid: String, freshIdToken: String, acknowledged: Boolean,
        recentlyReauthenticated: Boolean, readiness: AccountDeletionReadiness,
        currentAccount: () -> LocalAccountScope?): DeletionRecoveryResult = mutex.withLock {
        val account = LocalAccountScope.fromFirebaseUid(uid) as LocalAccountScope.SignedIn
        if (!readiness.canRequest(account, acknowledged, recentlyReauthenticated) ||
            currentAccount() != account || freshIdToken.isBlank()) return@withLock DeletionRecoveryResult.NotReady
        try {
            val existing = store.read(account)
            val state = existing ?: DeletionRequestState.new(uid)
            if (state.account != account || state.jobId != deletionJobId(uid) ||
                currentAccount() != account || (existing != null && !store.blocksCloud(account)))
                return@withLock DeletionRecoveryResult.StateUnknown
            // Reserve the one device-authenticated recovery slot before transmission.
            // Another account's slot cannot be replaced; no new journal is written on denial.
            if (!enrollment.enroll(DeletionRecoveryAnchor(account, state.jobId)) ||
                currentAccount() != account) return@withLock DeletionRecoveryResult.NotReady
            if (existing == null) store.write(state)
            if (!store.blocksCloud(account) || currentAccount() != account)
                return@withLock DeletionRecoveryResult.StateUnknown
            // Never resubmit an acknowledged job. Recover status using the saved ticket.
            val next = if (state.receipt != null) transport.status(state.jobId, state.statusTicket)
                else transport.request(freshIdToken, state.statusTicket)
            val accepted = state.accept(next) ?: return@withLock DeletionRecoveryResult.StateUnknown
            store.write(accepted)
            if (currentAccount() != account) return@withLock DeletionRecoveryResult.StateUnknown
            DeletionRecoveryResult.Confirmed(next)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DeletionRecoveryResult.StateUnknown }
    }

    /** Explicit recovery after Auth deletion. Caller must provide the selected local account,
     * not discover or enumerate another user's ticket. Never create new state here. */
    suspend fun refresh(account: LocalAccountScope.SignedIn,
        verifiedEndpoint: Boolean, expectedJobId: String? = null,
        stillAuthorized: () -> Boolean = { false }): DeletionRecoveryResult = mutex.withLock {
        if (!verifiedEndpoint || !stillAuthorized()) return@withLock DeletionRecoveryResult.NotReady
        try {
            val state = store.read(account) ?: return@withLock DeletionRecoveryResult.StateUnknown
            if (state.account != account || !store.blocksCloud(account) ||
                (expectedJobId != null && expectedJobId != state.jobId) || !stillAuthorized())
                return@withLock DeletionRecoveryResult.StateUnknown
            val next = transport.status(state.jobId, state.statusTicket)
            val accepted = state.accept(next) ?: return@withLock DeletionRecoveryResult.StateUnknown
            if (!stillAuthorized()) return@withLock DeletionRecoveryResult.StateUnknown
            store.write(accepted)
            if (!stillAuthorized()) return@withLock DeletionRecoveryResult.StateUnknown
            DeletionRecoveryResult.Confirmed(next)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DeletionRecoveryResult.StateUnknown }
    }
}
