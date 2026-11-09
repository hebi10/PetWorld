package com.haebi.petworld.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a password-encrypted logical Room snapshot with referenced photos and settings.
 * There is no plaintext archive on disk; the ZIP stream is encrypted as it is produced.
 * The receiving UI must delete an incomplete destination document when this method fails.
 */
class PetWorldBackupExporter(
    private val context: Context,
    private val database: PetWorldDatabase = PetWorldDatabase.get(context),
    private val preferences: ThemePreferences = ThemePreferences(context),
    private val codec: EncryptedBackupCodec = EncryptedBackupCodec(),
) {
    suspend fun exportToUri(destination: Uri, password: CharArray) {
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("백업 파일을 열 수 없습니다.")
            output.use { exportToStream(it, password) }
        }
    }

    suspend fun exportToStream(output: OutputStream, password: CharArray) =
        PetWorldMutationGate.mutex.withLock { exportUnderGate(output, password) }

    /** Caller must already own the mutation gate (used by the recovery journal). */
    internal suspend fun exportUnderGate(output: OutputStream, password: CharArray) = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "백업 암호를 입력해 주세요." }

        val (pets, entries) = database.withTransaction {
            database.petDao().list() to database.careDao().listAll()
        }
        val themeMode = preferences.themeMode.first().name
        val selectedPetId = preferences.selectedPetId.first()
        val payload = JSONObject().apply {
            put("schemaVersion", 2)
            put("pets", JSONArray().apply { pets.forEach { put(petJson(it)) } })
            put("careEntries", JSONArray().apply { entries.forEach { put(careJson(it)) } })
            put("settings", JSONObject().apply {
                put("themeMode", themeMode)
                putValue("selectedPetId", selectedPetId)
            })
        }.toString().toByteArray(Charsets.UTF_8)

        val referencedPhotos = (pets.mapNotNull { it.photoFile } +
            entries.mapNotNull { it.photoFile }).distinct().sorted()
        val photoDirectory = File(context.filesDir, "pet_photos")
        val photos = referencedPhotos.map { name ->
            require(Regex("pet_[a-z0-9-]+\\.jpg").matches(name)) {
                "올바르지 않은 사진 파일명입니다."
            }
            val file = File(photoDirectory, name)
            require(file.isFile && file.canRead()) { "참조된 사진을 찾을 수 없습니다: $name" }
            name to file
        }

        coroutineScope {
            val pipedInput = PipedInputStream(64 * 1024)
            val producer = async(Dispatchers.IO) {
                PipedOutputStream(pipedInput).use { pipe ->
                    writeArchive(pipe, payload, photos)
                }
            }
            try {
                codec.encrypt(pipedInput, output, password)
                producer.await()
            } finally {
                pipedInput.close()
                producer.cancel()
            }
        }
    }

    private fun writeArchive(
        output: OutputStream,
        payload: ByteArray,
        photos: List<Pair<String, File>>,
    ) {
        val items = JSONArray()
        ZipOutputStream(output).use { zip ->
            fun addFile(name: String, source: () -> java.io.InputStream) {
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                source().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        zip.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        bytes += count
                    }
                }
                zip.closeEntry()
                items.put(JSONObject().apply {
                    put("path", name)
                    put("bytes", bytes)
                    put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                })
            }
            addFile("data.json") { payload.inputStream() }
            photos.forEach { (name, file) ->
                addFile("photos/$name") { file.inputStream() }
            }
            val manifest = JSONObject().apply {
                put("formatVersion", 1)
                put("schemaVersion", 2)
                put("createdAt", System.currentTimeMillis())
                put("files", items)
            }.toString().toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0L })
            zip.write(manifest)
            zip.closeEntry()
        }
    }

    private fun JSONObject.putValue(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun petJson(value: PetEntity): JSONObject = JSONObject().apply {
            putValue("id", value.id)
            putValue("name", value.name)
            putValue("species", value.species)
            putValue("breed", value.breed)
            putValue("birthday", value.birthday)
            putValue("estimatedAgeYears", value.estimatedAgeYears)
            putValue("sex", value.sex)
            putValue("neuterStatus", value.neuterStatus)
            putValue("notes", value.notes)
            putValue("photoFile", value.photoFile)
            putValue("isArchived", value.isArchived)
            putValue("createdAt", value.createdAt)
            putValue("updatedAt", value.updatedAt)
    }

    private fun careJson(value: CareEntity): JSONObject = JSONObject().apply {
            putValue("id", value.id)
            putValue("petId", value.petId)
            putValue("type", value.type)
            putValue("occurredAt", value.occurredAt)
            putValue("timezoneId", value.timezoneId)
            putValue("foodKind", value.foodKind)
            putValue("foodProductName", value.foodProductName)
            putValue("foodServedMilli", value.foodServedMilli)
            putValue("foodConsumedMilli", value.foodConsumedMilli)
            putValue("foodUnit", value.foodUnit)
            putValue("waterMl", value.waterMl)
            putValue("eliminationKind", value.eliminationKind)
            putValue("eliminationCount", value.eliminationCount)
            putValue("activityKind", value.activityKind)
            putValue("activityMinutes", value.activityMinutes)
            putValue("weightGrams", value.weightGrams)
            putValue("weightInputUnit", value.weightInputUnit)
            putValue("photoFile", value.photoFile)
            putValue("note", value.note)
            putValue("createdAt", value.createdAt)
            putValue("updatedAt", value.updatedAt)
    }
}
