package com.haebi.petworld.data

import android.content.Context
import androidx.room.withTransaction
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.data.room.toDomain
import com.haebi.petworld.data.room.toEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface PetLoadStatus {
    data object Loading : PetLoadStatus
    data object Ready : PetLoadStatus
    data class Failed(val message: String) : PetLoadStatus
}

/**
 * Uses the original petworld_profiles.db (legacy v1) through Room v2.
 * UUIDs and pet photos remain unchanged. Never use destructive migration.
 */
class PetRepository(
    context: Context,
    private val database: PetWorldDatabase = PetWorldDatabase.get(context),
) {
    private val dao = database.petDao()
    private val photos = PetPhotoStore(context.applicationContext)
    val photoStore: PetPhotoStore get() = photos
    private val mutex = Mutex()

    private val _profiles = MutableStateFlow<List<PetProfile>>(emptyList())
    val profiles = _profiles.asStateFlow()
    private val _loadStatus = MutableStateFlow<PetLoadStatus>(PetLoadStatus.Loading)
    val loadStatus = _loadStatus.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        try {
            _profiles.value = dao.list().map { it.toDomain() }
            _loadStatus.value = PetLoadStatus.Ready
        } catch (error: Exception) {
            _loadStatus.value = PetLoadStatus.Failed(
                error.message ?: "프로필을 불러오지 못했습니다."
            )
        }
    }

    suspend fun save(draft: PetDraft): PetProfile = mutex.withLock {
        PetValidation.error(draft)?.let { throw IllegalArgumentException(it) }
        val previous = draft.id?.let { dao.find(it)?.toDomain() }
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
            database.withTransaction {
                if (previous == null) dao.insert(value.toEntity())
                else check(dao.update(value.toEntity()) == 1) { "수정할 프로필을 찾을 수 없습니다." }
                dao.list().map { it.toDomain() }
            }
        } catch (error: Exception) {
            if (freshPhoto != null) photos.delete(freshPhoto)
            throw error
        }
        _profiles.value = snapshot
        _loadStatus.value = PetLoadStatus.Ready
        if (previous?.photoFile != photo) photos.delete(previous?.photoFile)
        value
    }

    suspend fun setArchived(id: String, archived: Boolean) = mutex.withLock {
        val snapshot = database.withTransaction {
            val previous = dao.find(id) ?: error("프로필을 찾을 수 없습니다.")
            check(dao.update(previous.copy(
                isArchived = archived,
                updatedAt = System.currentTimeMillis(),
            )) == 1)
            dao.list().map { it.toDomain() }
        }
        _profiles.value = snapshot
    }

    /** Care entries are deleted only through the user's two-stage delete confirmation. */
    suspend fun deleteForever(id: String) = mutex.withLock {
        val target = dao.find(id) ?: error("프로필을 찾을 수 없습니다.")
        val snapshot = database.withTransaction {
            check(dao.delete(id) == 1)
            dao.list().map { it.toDomain() }
        }
        _profiles.value = snapshot
        photos.delete(target.photoFile)
    }
}
