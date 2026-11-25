package com.haebi.petworld.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.backup.PetWorldMutationGate
import com.haebi.petworld.data.room.PetWorldDatabase
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SyncPhotoKey(val petId: String, val fileName: String) {
    init {
        require(validSyncPathId(petId) && validCloudPhotoName(fileName))
    }
}

internal data class RemotePhotoMetadata(
    val sizeBytes: Long,
    val contentType: String?,
    val sha256: String?,
    val md5: String?,
)

internal interface SyncPhotoStorage {
    suspend fun metadata(key: SyncPhotoKey): RemotePhotoMetadata?
    suspend fun create(key: SyncPhotoKey, photo: VerifiedPhoto)
    suspend fun download(key: SyncPhotoKey): ByteArray
}

internal class SyncPhotoRejected : IllegalStateException("Photo requires review")

internal class SyncPhotoTransfer(
    context: Context,
    private val db: PetWorldDatabase,
    private val account: LocalAccountScope.SignedIn,
    private val remote: SyncPhotoStorage,
    private val permission: SyncPermission,
) {
    private val files = PetPhotoStore(context.applicationContext, account)
    private val journal = db.syncPhotoDao()

    suspend fun upload(row: SyncOutboxEntry, fileName: String) {
        require(row.accountKey == account.key)
        if (!validCloudPhotoName(fileName)) throw SyncPhotoRejected()
        val key = SyncPhotoKey(row.petId, fileName)
        val photo = PetWorldMutationGate.mutex.withLock {
            requirePermission()
            if (db.syncOutboxDao().firstOutstanding(account.key) != row) return
            val local = readLocal(key) ?: throw SyncPhotoRejected()
            val entry = localEntry(key, local.evidence)
            if (entry?.status == "REMOTE_VERIFIED") return
            local
        }
        val metadata = checkSession { remote.metadata(key) } ?: run {
            val failure = try {
                checkSession { remote.create(key, photo) }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A simultaneous or interrupted create can leave an object behind.
                failure
            }
            checkSession { remote.metadata(key) }
                ?: throw (failure ?: IllegalStateException("Uploaded photo is not yet readable"))
        }
        verifyRemote(key, metadata, photo.evidence)
        markUploaded(row, key, photo.evidence)
    }

    private suspend fun markUploaded(row: SyncOutboxEntry, key: SyncPhotoKey, evidence: PhotoEvidence) {
        PetWorldMutationGate.mutex.withLock {
            requirePermission()
            if (db.syncOutboxDao().firstOutstanding(account.key) != row) return
            if (readLocal(key)?.evidence != evidence) throw SyncPhotoRejected()
            saveVerified(key, evidence)
        }
    }

    suspend fun download(key: SyncPhotoKey) {
        PetWorldMutationGate.mutex.withLock {
            requirePermission()
            val evidence = readLocal(key)?.evidence
            if (evidence != null && localEntry(key, evidence)?.status == "REMOTE_VERIFIED") return
        }
        val metadata = checkSession { remote.metadata(key) } ?: throw SyncPhotoRejected()
        val photo = verifyRemote(key, metadata, expected = null)
        PetWorldMutationGate.mutex.withLock {
            requirePermission()
            localEntry(key, photo.evidence)
            if (!files.installVerifiedJpeg(key.fileName, photo.bytes, photo.evidence.sha256)) {
                throw SyncPhotoRejected()
            }
            saveVerified(key, photo.evidence)
        }
    }

    private suspend fun verifyRemote(
        key: SyncPhotoKey,
        metadata: RemotePhotoMetadata,
        expected: PhotoEvidence?,
    ): VerifiedPhoto {
        if (metadata.sizeBytes !in 1L..CLOUD_PHOTO_MAX_BYTES ||
            metadata.contentType != CLOUD_PHOTO_MIME ||
            (metadata.sha256 != null && !metadata.sha256.matches(Regex("[0-9a-f]{64}"))) ||
            (expected != null && (metadata.sizeBytes != expected.sizeBytes ||
                (metadata.sha256 != null && metadata.sha256 != expected.sha256))) ||
            (expected == null && metadata.sha256 == null && metadata.md5.isNullOrBlank())
        ) throw SyncPhotoRejected()

        val bytes = checkSession { remote.download(key) }
        if (bytes.size.toLong() != metadata.sizeBytes) throw SyncPhotoRejected()
        val evidence = try {
            withContext(Dispatchers.IO) {
                val result = cloudJpegEvidence(bytes)
                // FB-04 photos have no custom SHA-256. Verify the Storage MD5, then record SHA-256.
                if (expected == null && metadata.sha256 == null) {
                    val md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))
                    if (md5 != metadata.md5) throw SyncPhotoRejected()
                }
                result
            }
        } catch (_: IllegalArgumentException) {
            throw SyncPhotoRejected()
        }
        if ((expected != null && evidence != expected) ||
            (metadata.sha256 != null && evidence.sha256 != metadata.sha256)
        ) throw SyncPhotoRejected()
        return VerifiedPhoto(bytes, evidence)
    }

    private suspend fun readLocal(key: SyncPhotoKey): VerifiedPhoto? {
        val file = requireNotNull(files.fileFor(key.fileName))
        return try {
            if (file.exists()) readCloudJpeg(file) else null
        } catch (_: IllegalArgumentException) {
            throw SyncPhotoRejected()
        }
    }

    private suspend fun localEntry(key: SyncPhotoKey, evidence: PhotoEvidence): SyncPhotoEntry? {
        val entry = journal.find(account.key, key.petId, key.fileName)
        if (entry?.status == "CONTENT_CONFLICT" ||
            (entry?.sha256 != null && !entry.matches(evidence))
        ) throw SyncPhotoRejected()
        return entry
    }

    private suspend fun saveVerified(key: SyncPhotoKey, evidence: PhotoEvidence) = db.withTransaction {
        requirePermission()
        val old = localEntry(key, evidence)
        journal.save(SyncPhotoEntry(
            accountKey = account.key, petId = key.petId, fileName = key.fileName,
            sha256 = evidence.sha256, sizeBytes = evidence.sizeBytes,
            width = evidence.width, height = evidence.height,
            status = "REMOTE_VERIFIED", retired = old?.retired ?: false,
        ))
        requirePermission()
    }

    private suspend fun <T> checkSession(action: suspend () -> T): T {
        requirePermission()
        val result = action()
        requirePermission()
        return result
    }

    private suspend fun requirePermission() {
        if (!permission.permits(account)) throw SyncReceiveDenied()
    }
}
