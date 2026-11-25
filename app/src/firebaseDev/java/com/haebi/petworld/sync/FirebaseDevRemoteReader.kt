package com.haebi.petworld.sync

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.sync.RemoteSyncDocument
import com.haebi.petworld.data.sync.RemoteSyncPage
import com.haebi.petworld.data.sync.SyncPermission
import com.haebi.petworld.data.sync.SyncReceiveDenied
import com.haebi.petworld.data.sync.validSyncPathId
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Explicit, server-only, bounded collection scan. A cursor is valid only for that collection.
 * C2 will coordinate repeated scans and durable continuation; no app/worker calls this yet.
 */
internal class FirebaseDevRemoteReader(
    context: Context,
    private val permission: SyncPermission = SyncPermission { false },
) {
    private val app = FirebaseApp.getInstance()
    private val auth = FirebaseAuth.getInstance(app)
    private val firestore = FirebaseFirestore.getInstance(app)

    init {
        check(context.applicationContext.packageName == "com.haebi.petworld.dev")
        check(app.options.projectId == "petworld-dev")
    }

    private fun uid(account: LocalAccountScope.SignedIn): String? {
        val user = auth.currentUser ?: return null
        if (user.isAnonymous || user.providerData.none { it.providerId == "google.com" }) return null
        return user.uid.takeIf { LocalAccountScope.fromFirebaseUid(it) == account }
    }

    suspend fun readDocument(
        account: LocalAccountScope.SignedIn,
        kind: String,
        petId: String,
        entityId: String,
    ): RemoteSyncDocument? {
        require(kind in setOf("PET", "CARE") && validSyncPathId(petId) && validSyncPathId(entityId))
        require(kind != "PET" || petId == entityId)
        val expectedUid = uid(account) ?: throw SyncReceiveDenied()
        suspend fun checkSession() {
            if (uid(account) != expectedUid || !permission.permits(account)) throw SyncReceiveDenied()
        }
        checkSession()
        val user = auth.currentUser?.takeIf { it.uid == expectedUid } ?: throw SyncReceiveDenied()
        user.getIdToken(true).awaitReceive()
        checkSession()
        val owner = firestore.collection("users").document(expectedUid)
        val state = owner.get(Source.SERVER).awaitReceive()
        check(state.exists() && !state.metadata.isFromCache && !state.metadata.hasPendingWrites())
        check(state.getString("migrationState") == "ready")
        checkSession()
        val pet = owner.collection("pets").document(petId)
        val target = if (kind == "PET") pet else pet.collection("records").document(entityId)
        val snapshot = target.get(Source.SERVER).awaitReceive()
        checkSession()
        check(!snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites())
        if (!snapshot.exists()) return null
        return RemoteSyncDocument(kind, petId, entityId, requireNotNull(snapshot.data).toMap())
    }

    suspend fun readPage(
        account: LocalAccountScope.SignedIn,
        parentPetId: String? = null,
        afterId: String? = null,
        limit: Int = 50,
    ): RemoteSyncPage {
        require(limit in 1..100)
        require(parentPetId == null || validSyncPathId(parentPetId))
        require(afterId == null || validSyncPathId(afterId))
        val expectedUid = uid(account) ?: throw SyncReceiveDenied()
        suspend fun requireCurrentSession() {
            if (uid(account) != expectedUid || !permission.permits(account)) throw SyncReceiveDenied()
        }
        requireCurrentSession()
        val user = auth.currentUser?.takeIf { it.uid == expectedUid } ?: throw SyncReceiveDenied()
        user.getIdToken(true).awaitReceive()
        requireCurrentSession()
        val owner = firestore.collection("users").document(expectedUid)
        val state = owner.get(Source.SERVER).awaitReceive()
        check(state.exists() && !state.metadata.isFromCache && !state.metadata.hasPendingWrites())
        check(state.getString("migrationState") == "ready") { "Remote baseline is not ready" }
        requireCurrentSession()
        val collection = if (parentPetId == null) owner.collection("pets")
            else owner.collection("pets").document(parentPetId).collection("records")
        var query: Query = collection.orderBy(FieldPath.documentId())
        if (afterId != null) query = query.startAfter(afterId)
        val snapshot = query.limit((limit + 1).toLong()).get(Source.SERVER).awaitReceive()
        requireCurrentSession()
        check(!snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites())
        val docs = snapshot.documents.take(limit).map { item ->
            check(!item.metadata.isFromCache && !item.metadata.hasPendingWrites())
            RemoteSyncDocument(
                kind = if (parentPetId == null) "PET" else "CARE",
                petId = parentPetId ?: item.id, entityId = item.id,
                fields = requireNotNull(item.data).toMap(),
            )
        }
        return RemoteSyncPage(account.key, parentPetId, docs, docs.lastOrNull()?.entityId,
            snapshot.documents.size > limit)
    }
}

private suspend fun <T> Task<T>.awaitReceive(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase read failed"))
    }
}
