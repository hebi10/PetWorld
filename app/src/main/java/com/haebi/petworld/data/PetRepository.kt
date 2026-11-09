package com.haebi.petworld.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface PetLoadStatus {
    data object Loading : PetLoadStatus
    data object Ready : PetLoadStatus
    data class Failed(val message: String) : PetLoadStatus
}

private class PetDatabase(context: Context) :
    SQLiteOpenHelper(context, "petworld_profiles.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE pets (
               id TEXT PRIMARY KEY NOT NULL,
               name TEXT NOT NULL,
               species TEXT NOT NULL,
               breed TEXT NOT NULL DEFAULT '',
               birthday TEXT,
               estimated_age_years INTEGER,
               sex TEXT NOT NULL,
               neuter_status TEXT NOT NULL,
               notes TEXT NOT NULL DEFAULT '',
               photo_file TEXT,
               is_archived INTEGER NOT NULL DEFAULT 0,
               created_at INTEGER NOT NULL,
               updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_pets_archived_updated ON pets(is_archived, updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("데이터 보존을 위한 명시적 DB 마이그레이션이 필요합니다.")
    }
}

class PetRepository(context: Context) {
    private val database = PetDatabase(context.applicationContext)
    private val photos = PetPhotoStore(context.applicationContext)
    val photoStore: PetPhotoStore get() = photos
    private val mutex = Mutex()
    private val _profiles = MutableStateFlow<List<PetProfile>>(emptyList())
    val profiles = _profiles.asStateFlow()
    private val _loadStatus = MutableStateFlow<PetLoadStatus>(PetLoadStatus.Loading)
    val loadStatus = _loadStatus.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        try {
            _profiles.value = withContext(Dispatchers.IO) { readAll() }
            _loadStatus.value = PetLoadStatus.Ready
        } catch (error: Exception) {
            _loadStatus.value = PetLoadStatus.Failed(error.message ?: "프로필을 불러오지 못했습니다.")
        }
    }

    suspend fun save(draft: PetDraft): PetProfile = mutex.withLock {
        PetValidation.error(draft)?.let { throw IllegalArgumentException(it) }
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            val previous = draft.id?.let { findOne(db, it) }
            require(draft.id == null || previous != null) { "수정할 프로필을 찾을 수 없습니다." }
            val freshPhoto = draft.newPhotoUri?.let { photos.importPhoto(it) }
            val photo = freshPhoto ?: if (draft.removePhoto) null else previous?.photoFile
            val now = System.currentTimeMillis()
            val value = PetProfile(
                id = previous?.id ?: UUID.randomUUID().toString(),
                name = draft.name.trim(),
                species = draft.species,
                breed = draft.breed.trim(),
                birthday = draft.birthday.takeIf { draft.birthMode == BirthMode.EXACT },
                estimatedAgeYears = if (draft.birthMode == BirthMode.ESTIMATED) {
                    draft.estimatedAgeYears.toInt()
                } else null,
                sex = draft.sex,
                neuterStatus = draft.neuterStatus,
                notes = draft.notes.trim(),
                photoFile = photo,
                isArchived = previous?.isArchived ?: false,
                createdAt = previous?.createdAt ?: now,
                updatedAt = now,
            )
            val snapshot = try {
                db.beginTransaction()
                try {
                    val cv = toValues(value)
                    if (previous == null) db.insertOrThrow("pets", null, cv)
                    else check(db.update("pets", cv, "id=?", arrayOf(value.id)) == 1)
                    val next = readAll(db)
                    db.setTransactionSuccessful()
                    next
                } finally {
                    db.endTransaction()
                }
            } catch (error: Exception) {
                if (freshPhoto != null) photos.delete(freshPhoto)
                throw error
            }
            _profiles.value = snapshot
            if (previous?.photoFile != photo) photos.delete(previous?.photoFile)
            value
        }
    }

    suspend fun setArchived(id: String, archived: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("is_archived", if (archived) 1 else 0)
                put("updated_at", System.currentTimeMillis())
            }
            db.beginTransaction()
            val snapshot = try {
                require(db.update("pets", values, "id=?", arrayOf(id)) == 1) { "프로필을 찾을 수 없습니다." }
                val next = readAll(db)
                db.setTransactionSuccessful()
                next
            } finally {
                db.endTransaction()
            }
            _profiles.value = snapshot
        }
    }

    suspend fun deleteForever(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            val target = findOne(db, id) ?: error("프로필을 찾을 수 없습니다.")
            db.beginTransaction()
            val snapshot = try {
                check(db.delete("pets", "id=?", arrayOf(id)) == 1)
                val next = readAll(db)
                db.setTransactionSuccessful()
                next
            } finally {
                db.endTransaction()
            }
            _profiles.value = snapshot
            photos.delete(target.photoFile)
        }
    }

    private fun readAll(db: SQLiteDatabase = database.readableDatabase): List<PetProfile> =
        db.query(
            "pets", null, null, null, null, null, "is_archived ASC, created_at ASC"
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toPetProfile()) }
        }

    private fun findOne(db: SQLiteDatabase, id: String): PetProfile? =
        db.query("pets", null, "id=?", arrayOf(id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPetProfile() else null
        }

    private fun Cursor.toPetProfile(): PetProfile = PetProfile(
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        species = enumValueOf(getString(getColumnIndexOrThrow("species"))),
        breed = getString(getColumnIndexOrThrow("breed")),
        birthday = nullableString("birthday"),
        estimatedAgeYears = getColumnIndexOrThrow("estimated_age_years").let {
            if (isNull(it)) null else getInt(it)
        },
        sex = enumValueOf(getString(getColumnIndexOrThrow("sex"))),
        neuterStatus = enumValueOf(getString(getColumnIndexOrThrow("neuter_status"))),
        notes = getString(getColumnIndexOrThrow("notes")),
        photoFile = nullableString("photo_file"),
        isArchived = getInt(getColumnIndexOrThrow("is_archived")) == 1,
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )

    private fun Cursor.nullableString(name: String): String? =
        getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }

    private fun toValues(pet: PetProfile): ContentValues = ContentValues().apply {
        put("id", pet.id)
        put("name", pet.name)
        put("species", pet.species.name)
        put("breed", pet.breed)
        put("birthday", pet.birthday)
        if (pet.estimatedAgeYears == null) putNull("estimated_age_years")
        else put("estimated_age_years", pet.estimatedAgeYears)
        put("sex", pet.sex.name)
        put("neuter_status", pet.neuterStatus.name)
        put("notes", pet.notes)
        put("photo_file", pet.photoFile)
        put("is_archived", if (pet.isArchived) 1 else 0)
        put("created_at", pet.createdAt)
        put("updated_at", pet.updatedAt)
    }
}
