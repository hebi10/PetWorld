package com.haebi.petworld.sync

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.haebi.petworld.data.sync.PhotoEvidence
import com.haebi.petworld.data.sync.SyncPhotoProof
import com.haebi.petworld.data.migration.FirstImportPayloads
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.AccountDeletionJournal
import com.haebi.petworld.auth.awaitTrackedFirebaseTask
import com.haebi.petworld.data.sync.CLOUD_PHOTO_MAX_BYTES
import com.haebi.petworld.data.sync.CLOUD_PHOTO_MIME
import com.haebi.petworld.data.sync.RemotePhotoMetadata
import com.haebi.petworld.data.sync.SyncPermission
import com.haebi.petworld.data.sync.SyncPhotoKey
import com.haebi.petworld.data.sync.SyncPhotoRejected
import com.haebi.petworld.data.sync.SyncPhotoStorage
import com.haebi.petworld.data.sync.SyncReceiveDenied
import com.haebi.petworld.data.sync.VerifiedPhoto
import com.haebi.petworld.data.sync.validSyncPathId

internal class FirebaseDevPhotoStorage(
    context: Context,
    private val account: LocalAccountScope.SignedIn,
    private val permission: SyncPermission,
) : SyncPhotoStorage {
    private val journal = AccountDeletionJournal(context.applicationContext)
    private val app = FirebaseApp.getInstance()
    private val auth = FirebaseAuth.getInstance(app)

    init {
        check(context.applicationContext.packageName == "com.haebi.petworld.dev")
        check(app.options.projectId == "petworld-dev")
    }

    override suspend fun metadata(key: SyncPhotoKey): RemotePhotoMetadata? =
        request(key, allowMissing = true) { ref ->
            val uid = currentUid() ?: throw SyncReceiveDenied()
            val metadata = sdk(uid) { ref.metadata }
            if (!permission.permits(account)) throw SyncReceiveDenied()
            val manifest = sdk(uid) { FirebaseFirestore.getInstance(app).collection("users").document(uid)
                .collection("pets").document(key.petId).collection("photoManifests").document(key.fileName)
                .get(Source.SERVER) }
            if (currentUid() != uid || !permission.permits(account)) throw SyncReceiveDenied()
            if (manifest.metadata.isFromCache || manifest.metadata.hasPendingWrites()) throw SyncPhotoRejected()
            val evidence = if (!manifest.exists()) null else try {
                val fields = requireNotNull(manifest.data)
                val width = requireNotNull(manifest.getLong("width"))
                val height = requireNotNull(manifest.getLong("height"))
                require(width in 1L..1200L && height in 1L..1200L)
                val proof = SyncPhotoProof(key.petId, key.fileName, PhotoEvidence(
                    requireNotNull(manifest.getString("sha256")),
                    requireNotNull(manifest.getLong("sizeBytes")), width.toInt(), height.toInt()))
                require(FirstImportPayloads.sameRemote(proof.manifest(), fields))
                proof.evidence
            } catch (_: IllegalArgumentException) {
                throw SyncPhotoRejected()
            }
            RemotePhotoMetadata(
                metadata.sizeBytes, metadata.contentType,
                metadata.getCustomMetadata("sha256"), metadata.md5Hash, evidence,
            )
        }

    override suspend fun create(key: SyncPhotoKey, photo: VerifiedPhoto) {
        request(key) { ref ->
            val metadata = StorageMetadata.Builder()
                .setContentType(CLOUD_PHOTO_MIME)
                .setCustomMetadata("sha256", photo.evidence.sha256)
                .build()
            val uid = currentUid() ?: throw SyncReceiveDenied()
            sdk(uid, cancelStorage = true) { ref.putBytes(photo.bytes, metadata) }
        }
    }

    override suspend fun download(key: SyncPhotoKey): ByteArray =
        requireNotNull(request(key) { ref ->
            val uid = currentUid() ?: throw SyncReceiveDenied()
            sdk(uid) { ref.getBytes(CLOUD_PHOTO_MAX_BYTES) }
        })

    private suspend fun <T : Any> request(
        key: SyncPhotoKey,
        allowMissing: Boolean = false,
        action: suspend (StorageReference) -> T,
    ): T? {
        val uid = currentUid() ?: throw SyncReceiveDenied()
        if (!permission.permits(account) || currentUid() != uid) throw SyncReceiveDenied()
        if (app.options.storageBucket.isNullOrBlank()) throw SyncPhotoRejected()
        val path = "users/$uid/pets/${key.petId}/photos/${key.fileName}"
        val ref = FirebaseStorage.getInstance(app).reference.child(path)
        return try {
            action(ref)
        } catch (failure: StorageException) {
            when (failure.errorCode) {
                StorageException.ERROR_OBJECT_NOT_FOUND -> if (allowMissing) null else throw SyncPhotoRejected()
                StorageException.ERROR_NOT_AUTHENTICATED, StorageException.ERROR_NOT_AUTHORIZED,
                StorageException.ERROR_BUCKET_NOT_FOUND, StorageException.ERROR_PROJECT_NOT_FOUND,
                StorageException.ERROR_QUOTA_EXCEEDED, StorageException.ERROR_INVALID_CHECKSUM ->
                    throw SyncPhotoRejected()
                else -> throw failure
            }
        }
    }

    private fun currentUid(): String? {
        val user = auth.currentUser ?: return null
        if (user.isAnonymous || user.providerData.none { it.providerId == "google.com" }) return null
        return user.uid.takeIf { validSyncPathId(it) && LocalAccountScope.fromFirebaseUid(it) == account }
    }
    private suspend fun <T> sdk(uid: String, cancelStorage: Boolean = false, start: () -> Task<T>): T {
        if (!permission.permits(account)) throw SyncReceiveDenied()
        return awaitTrackedFirebaseTask(account, permitted = {
            currentUid() == uid && !journal.blocksCloud(account)
        }, cancelStorage = cancelStorage, start = start)
    }
}
