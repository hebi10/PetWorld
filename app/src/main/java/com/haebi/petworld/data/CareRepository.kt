package com.haebi.petworld.data

import android.content.Context
import androidx.room.withTransaction
import com.haebi.petworld.data.backup.PetWorldMutationGate
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock

/** Every care record references a stable pet ID. Imported photos are cleaned on failed transactions. */
class CareRepository(
    context: Context,
    private val database: PetWorldDatabase,
) {
    private val dao = database.careDao()
    private val pets = database.petDao()
    private val photos = PetPhotoStore(context.applicationContext)
    private val mutex = PetWorldMutationGate.mutex

    fun observeAll(): Flow<List<CareEntity>> = dao.observeAll()

    suspend fun save(draft: CareDraft): CareEntity = mutex.withLock {
        CareValidation.error(draft)?.let { throw IllegalArgumentException(it) }
        val previous = draft.id?.let { dao.find(it) }
        require(draft.id == null || previous != null) { "수정할 기록을 찾을 수 없습니다." }
        require(previous == null || previous.petId == draft.petId) {
            "다른 반려동물의 기록은 수정할 수 없습니다."
        }
        val freshPhoto = if (draft.type == CareType.ELIMINATION)
            draft.newPhotoUri?.let { photos.importPhoto(it) }
        else null
        val photoFile = freshPhoto ?: if (draft.removePhoto) null else previous?.photoFile
        val result = try {
            database.withTransaction {
                val pet = pets.find(draft.petId) ?: error("반려동물을 찾을 수 없습니다.")
                require(!pet.isArchived) { "보관된 반려동물에는 기록할 수 없습니다." }
                val old = draft.id?.let { dao.find(it) }
                require(draft.id == null || old != null) { "수정할 기록을 찾을 수 없습니다." }
                require(old == null || old.petId == draft.petId) {
                    "다른 반려동물의 기록은 수정할 수 없습니다."
                }
                require(old == null || old.type == draft.type.name) {
                    "기록 종류를 변경할 수 없습니다."
                }
                val entity = draft.toEntity(previous = old, photoFile = photoFile)
                if (old == null) dao.insert(entity)
                else check(dao.update(entity) == 1) { "기록이 변경되어 저장하지 못했습니다." }
                entity
            }
        } catch (error: Exception) {
            if (freshPhoto != null) photos.delete(freshPhoto)
            throw error
        }
        if (previous?.photoFile != result.photoFile) photos.delete(previous?.photoFile)
        result
    }

    suspend fun delete(id: String, petId: String): Unit = mutex.withLock {
        require(id.isNotBlank() && petId.isNotBlank()) { "삭제할 기록이 올바르지 않습니다." }
        val removed = database.withTransaction {
            val found = dao.find(id) ?: error("삭제할 기록을 찾을 수 없습니다.")
            require(found.petId == petId) { "다른 반려동물의 기록은 삭제할 수 없습니다." }
            check(dao.delete(id, petId) == 1) { "삭제할 기록을 찾을 수 없습니다." }
            found
        }
        photos.delete(removed.photoFile)
    }
}
