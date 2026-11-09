package com.haebi.petworld.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Reads and validates an encrypted backup before touching live Room rows or photo files.
 * Live database replacement is one Room transaction. Existing photos are never overwritten.
 *
 * The 1-E-D UI must block concurrent writes while restore is running and refresh its repositories
 * after success. Process-death recovery across Room and DataStore is still an integration task.
 */
class PetWorldBackupRestorer(
    private val context: Context,
    private val database: PetWorldDatabase = PetWorldDatabase.get(context),
    private val preferences: ThemePreferences = ThemePreferences(context),
    private val codec: EncryptedBackupCodec = EncryptedBackupCodec(),
) {
    private val photoPattern = Regex("pet_[a-z0-9-]+\\.jpg")

    suspend fun restoreFromUri(source: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(source)?.use { restoreFromStream(it, password) }
            ?: throw IOException("백업 파일을 열 수 없습니다.")
    }

    suspend fun restoreFromStream(source: InputStream, password: CharArray) = withContext(Dispatchers.IO) {
        val workspace = File(context.cacheDir, "petworld-restore-${java.util.UUID.randomUUID()}")
        check(workspace.mkdirs()) { "복원 임시 폴더를 만들지 못했습니다." }
        try {
            val verified = codec.decryptToVerifiedFile(source, workspace, password)
            val archive = readAndValidateArchive(verified, workspace)
            applyValidatedArchive(archive)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private data class ValidatedArchive(
        val pets: List<PetEntity>,
        val entries: List<CareEntity>,
        val mode: ThemeMode,
        val selectedPet: String?,
        val photos: Map<String, File>,
    )

    private fun readAndValidateArchive(file: File, workspace: File): ValidatedArchive {
        val unpacked = File(workspace, "entries").apply { mkdirs() }
        val files = linkedMapOf<String, File>()
        val maxArchiveSize = 1024L * 1024 * 1024
        val maxFileSize = 50L * 1024 * 1024
        var total = 0L
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name
                require(
                    path == "data.json" || path == "manifest.json" ||
                        (path.startsWith("photos/") && photoPattern.matches(path.removePrefix("photos/")))
                ) { "허용되지 않은 백업 경로입니다." }
                require(!entry.isDirectory && path !in files) { "중복되거나 잘못된 백업 항목입니다." }
                require(files.size < 10_000) { "백업 항목이 너무 많습니다." }
                val destination = File(unpacked, "entry-${files.size}")
                var size = 0L
                destination.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        size += read
                        total += read
                        require(size <= maxFileSize && total <= maxArchiveSize) {
                            "백업 파일이 허용 크기를 초과합니다."
                        }
                        output.write(buffer, 0, read)
                    }
                }
                files[path] = destination
                zip.closeEntry()
            }
        }
        require(files.containsKey("manifest.json") && files.containsKey("data.json")) {
            "백업 데이터 또는 검증 정보가 없습니다."
        }
        val manifest = JSONObject(files.getValue("manifest.json").readText(Charsets.UTF_8))
        require(manifest.getInt("formatVersion") == 1 && manifest.getInt("schemaVersion") == 2) {
            "지원하지 않는 백업 형식입니다."
        }
        val indexed = mutableSetOf<String>()
        val manifestFiles = manifest.getJSONArray("files")
        for (index in 0 until manifestFiles.length()) {
            val record = manifestFiles.getJSONObject(index)
            val path = record.requiredString("path")
            val target = files[path] ?: error("백업 파일 목록이 실제 내용과 다릅니다.")
            require(path != "manifest.json" && indexed.add(path)) { "중복된 검증 항목입니다." }
            require(target.length() == record.getLong("bytes")) { "백업 파일 크기가 일치하지 않습니다." }
            val digest = target.inputStream().use { input ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) hash.update(buffer, 0, read)
                }
                hash.digest().joinToString("") { "%02x".format(it) }
            }
            require(digest.equals(record.requiredString("sha256"), ignoreCase = true)) {
                "백업 파일 무결성이 일치하지 않습니다."
            }
        }
        require(indexed == files.keys.minus("manifest.json")) { "검증되지 않은 백업 항목이 있습니다." }

        val data = JSONObject(files.getValue("data.json").readText(Charsets.UTF_8))
        require(data.getInt("schemaVersion") == 2) { "지원하지 않는 데이터 버전입니다." }
        val pets = data.getJSONArray("pets").toObjects { toPet() }
        val entries = data.getJSONArray("careEntries").toObjects { toCare() }
        val ids = pets.map { it.id }
        require(ids.size == ids.toSet().size && ids.all { it.isNotBlank() }) { "중복된 반려동물 ID입니다." }
        require(entries.map { it.id }.distinct().size == entries.size) { "중복된 기록 ID입니다." }
        require(entries.all { it.petId in ids }) { "연결되지 않은 돌봄 기록이 있습니다." }
        val settings = data.getJSONObject("settings")
        val rawTheme = settings.requiredString("themeMode")
        val mode = ThemeMode.entries.singleOrNull { it.name == rawTheme }
            ?: error("지원하지 않는 테마 설정입니다.")
        val selected = settings.nullableString("selectedPetId")
        require(selected == null || selected in ids) { "선택된 반려동물이 없습니다." }
        val referenced = (pets.mapNotNull { it.photoFile } + entries.mapNotNull { it.photoFile }).toSet()
        require(referenced.all(photoPattern::matches)) { "잘못된 사진 파일명이 있습니다." }
        require(referenced == files.keys.filter { it.startsWith("photos/") }
            .map { it.removePrefix("photos/") }.toSet()) { "참조 사진 구성이 일치하지 않습니다." }
        return ValidatedArchive(
            pets, entries, mode, selected,
            referenced.associateWith { files.getValue("photos/$it") },
        )
    }

    private suspend fun applyValidatedArchive(archive: ValidatedArchive) {
        val photoDirectory = File(context.filesDir, "pet_photos").apply { mkdirs() }
        val newPhotos = mutableListOf<File>()
        val oldMode = preferences.themeMode.first()
        val oldSelected = preferences.selectedPetId.first()
        val oldRecords = database.withTransaction {
            database.petDao().list() to database.careDao().listAll()
        }
        var dbReplaced = false
        var completed = false
        try {
            for ((name, staging) in archive.photos) {
                val target = File(photoDirectory, name)
                if (target.exists()) {
                    require(target.isFile && filesMatch(target, staging)) {
                        "같은 이름의 사진 파일이 다릅니다. 기존 사진을 덮어쓰지 않습니다."
                    }
                } else {
                    staging.copyTo(target, overwrite = false)
                    newPhotos.add(target)
                }
            }
            database.withTransaction {
                database.petDao().deleteAll()
                archive.pets.forEach { database.petDao().insert(it) }
                archive.entries.forEach { database.careDao().insert(it) }
            }
            dbReplaced = true
            preferences.setThemeMode(archive.mode)
            preferences.selectPet(archive.selectedPet)
            completed = true
        } catch (error: Exception) {
            if (dbReplaced) {
                try {
                    database.withTransaction {
                        database.petDao().deleteAll()
                        oldRecords.first.forEach { database.petDao().insert(it) }
                        oldRecords.second.forEach { database.careDao().insert(it) }
                    }
                    preferences.setThemeMode(oldMode)
                    preferences.selectPet(oldSelected)
                } catch (rollback: Exception) {
                    error.addSuppressed(rollback)
                }
            }
            throw error
        } finally {
            if (!completed) newPhotos.forEach { it.delete() }
        }
    }

    private fun filesMatch(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        first.inputStream().buffered().use { left ->
            second.inputStream().buffered().use { right ->
                val a = ByteArray(32 * 1024)
                val b = ByteArray(32 * 1024)
                while (true) {
                    val count = left.read(a)
                    if (count < 0) return right.read() < 0
                    if (right.readNBytes(b, 0, count) != count || !a.copyOf(count).contentEquals(b.copyOf(count))) return false
                }
            }
        }
    }

    private fun JSONObject.requiredString(key: String): String =
        getString(key).also { require(!isNull(key)) { "누락된 백업 필드: $key" } }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key)) null else getInt(key)

    private fun <T> JSONArray.toObjects(block: JSONObject.() -> T): List<T> =
        (0 until length()).map { getJSONObject(it).block() }

    private fun JSONObject.toPet(): PetEntity = PetEntity(
        id = requiredString("id"),
        name = requiredString("name"),
        species = requiredString("species"),
        breed = requiredString("breed"),
        birthday = nullableString("birthday"),
        estimatedAgeYears = nullableInt("estimatedAgeYears"),
        sex = requiredString("sex"),
        neuterStatus = requiredString("neuterStatus"),
        notes = requiredString("notes"),
        photoFile = nullableString("photoFile"),
        isArchived = getBoolean("isArchived"),
        createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"),
    )

    private fun JSONObject.toCare(): CareEntity = CareEntity(
        id = requiredString("id"),
        petId = requiredString("petId"),
        type = requiredString("type"),
        occurredAt = getLong("occurredAt"),
        timezoneId = requiredString("timezoneId"),
        foodKind = nullableString("foodKind"),
        foodProductName = nullableString("foodProductName"),
        foodServedMilli = nullableLong("foodServedMilli"),
        foodConsumedMilli = nullableLong("foodConsumedMilli"),
        foodUnit = nullableString("foodUnit"),
        waterMl = nullableLong("waterMl"),
        eliminationKind = nullableString("eliminationKind"),
        eliminationCount = nullableInt("eliminationCount"),
        activityKind = nullableString("activityKind"),
        activityMinutes = nullableInt("activityMinutes"),
        weightGrams = nullableLong("weightGrams"),
        weightInputUnit = nullableString("weightInputUnit"),
        photoFile = nullableString("photoFile"),
        note = requiredString("note"),
        createdAt = getLong("createdAt"),
        updatedAt = getLong("updatedAt"),
    )
}
