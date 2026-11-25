package com.haebi.petworld.data.sync

import android.graphics.BitmapFactory
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.MAX_PHOTO_EDGE
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.room.PetWorldDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val CLOUD_PHOTO_MAX_BYTES = 5L * 1024 * 1024

internal fun validCloudPhotoName(name: String): Boolean =
    Regex("pet_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]jpg").matches(name)

internal fun photoSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

@Entity(tableName = "sync_photos", primaryKeys = ["account_key", "pet_id", "file_name"])
data class SyncPhotoEntry(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "pet_id") val petId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    val sha256: String? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val status: String,
    val retired: Boolean = false,
)

@Dao
interface SyncPhotoDao {
    @Query("SELECT * FROM sync_photos WHERE account_key = :key AND pet_id = :pet AND file_name = :name LIMIT 1")
    suspend fun find(key: String, pet: String, name: String): SyncPhotoEntry?

    @Query("SELECT * FROM sync_photos WHERE account_key = :key ORDER BY pet_id, file_name LIMIT :limit")
    suspend fun list(key: String, limit: Int): List<SyncPhotoEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: SyncPhotoEntry)
}

internal data class PhotoEvidence(val sha256: String, val sizeBytes: Long, val width: Int, val height: Int)

internal suspend fun inspectCloudJpeg(file: File): PhotoEvidence = withContext(Dispatchers.IO) {
    require(file.isFile && file.length() in 1L..CLOUD_PHOTO_MAX_BYTES)
    val bytes = file.readBytes()
    require(bytes.size.toLong() in 1L..CLOUD_PHOTO_MAX_BYTES && bytes.size.toLong() == file.length())
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outMimeType == "image/jpeg" &&
        bounds.outWidth in 1..MAX_PHOTO_EDGE && bounds.outHeight in 1..MAX_PHOTO_EDGE)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: error("Invalid JPEG data")
    decoded.recycle()
    PhotoEvidence(photoSha256(bytes), bytes.size.toLong(), bounds.outWidth, bounds.outHeight)
}

/**
 * Record before and after photo identity in the same Room transaction as the domain
 * mutation and outbox. Keep even retired bytes until a future verified cleanup policy.
 * Legacy, missing or malformed files are recorded for review, not silently deleted.
 */
internal class LocalPhotoJournal(
    private val db: PetWorldDatabase,
    private val scope: LocalAccountScope,
    private val store: PetPhotoStore,
) {
    suspend fun changed(petId: String, before: String?, after: String?) {
        val account = scope as? LocalAccountScope.SignedIn ?: return
        check(db.inTransaction())
        require(validSyncPathId(petId))
        if (before != null && before != after) record(account.key, petId, before, retired = true)
        if (after != null) record(account.key, petId, after, retired = false)
    }

    private suspend fun record(key: String, pet: String, name: String, retired: Boolean) {
        val dao = db.syncPhotoDao()
        val old = dao.find(key, pet, name)
        if (old != null && old.retired == retired && old.status in setOf("LOCAL_VERIFIED", "REMOTE_VERIFIED")) return
        val evidence = if (!validCloudPhotoName(name)) null else {
            val file = store.fileFor(name)
            if (file == null) null else try {
                inspectCloudJpeg(file)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        val status = when {
            evidence == null -> "REVIEW_REQUIRED"
            old?.sha256 != null && old.sha256 != evidence.sha256 -> "CONTENT_CONFLICT"
            old?.status == "REMOTE_VERIFIED" -> "REMOTE_VERIFIED"
            else -> "LOCAL_VERIFIED"
        }
        val preserved = old?.takeIf { it.sha256 != null && status == "CONTENT_CONFLICT" }
        dao.save(SyncPhotoEntry(key, pet, name,
            preserved?.sha256 ?: evidence?.sha256,
            preserved?.sizeBytes ?: evidence?.sizeBytes,
            preserved?.width ?: evidence?.width,
            preserved?.height ?: evidence?.height,
            status, retired))
    }
}
