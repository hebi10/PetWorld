package com.haebi.petworld.data

import androidx.room.withTransaction
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All care writes are keyed by pet ID; records cannot be moved between animals by editing. */
class CareRepository(private val database: PetWorldDatabase) {
    private val dao = database.careDao()
    private val pets = database.petDao()
    private val mutex = Mutex()

    fun observeAll(): Flow<List<CareEntity>> = dao.observeAll()

    suspend fun save(draft: CareDraft): CareEntity = mutex.withLock {
        CareValidation.error(draft)?.let { throw IllegalArgumentException(it) }
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
            val entity = draft.toEntity(previous = old)
            if (old == null) dao.insert(entity)
            else check(dao.update(entity) == 1) { "기록이 변경되어 저장하지 못했습니다." }
            entity
        }
    }

    suspend fun delete(id: String, petId: String): Unit = mutex.withLock {
        require(id.isNotBlank() && petId.isNotBlank()) { "삭제할 기록이 올바르지 않습니다." }
        database.withTransaction {
            check(dao.delete(id, petId) == 1) {
                "삭제할 기록을 찾을 수 없습니다."
            }
        }
    }
}
