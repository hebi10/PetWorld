package com.haebi.petworld

import android.content.Context
import android.net.Uri
import com.haebi.petworld.data.backup.PetWorldBackupRestorer
import com.haebi.petworld.data.backup.PetWorldRecoveryJournal
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.haebi.petworld.data.CareDraft
import com.haebi.petworld.data.CareRepository
import com.haebi.petworld.data.PetDraft
import com.haebi.petworld.data.PetLoadStatus
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.PetProfile
import com.haebi.petworld.data.PetRepository
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.chooseActivePet
import com.haebi.petworld.data.room.CareEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PetUiState(
    val loadStatus: PetLoadStatus = PetLoadStatus.Loading,
    val profiles: List<PetProfile> = emptyList(),
    val activePet: PetProfile? = null,
) {
    val activeProfiles: List<PetProfile> get() = profiles.filterNot { it.isArchived }
}

sealed interface CareFeedState {
    data object Loading : CareFeedState
    data class Ready(
        val entries: List<CareEntity>,
        val petId: String?,
        val recordCounts: Map<String, Int>,
    ) : CareFeedState
    data class Failed(val message: String) : CareFeedState
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun <T> observeRetryableCare(
    reload: Flow<T>,
    observe: (T) -> Flow<CareFeedState.Ready>,
): Flow<CareFeedState> = reload.flatMapLatest { request ->
    flow<CareFeedState> { emitAll(observe(request)) }
        .onStart { emit(CareFeedState.Loading) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(CareFeedState.Failed(error.message ?: "기록을 불러오지 못했습니다."))
        }
}

internal inline fun <T> resultUnlessCancelled(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

class MainViewModel(
    private val appContext: Context,
    private val preferences: ThemePreferences,
    private val pets: PetRepository,
    private val care: CareRepository,
) : ViewModel() {
    val photoStore: PetPhotoStore = pets.photoStore
    private val reloadCare = MutableStateFlow(0L)
    val restoring = MutableStateFlow(false)
    val recoveryBlocked = MutableStateFlow(false)

    fun retryCare() { reloadCare.value += 1L }

    suspend fun restoreBackup(source: Uri, password: CharArray): Result<Unit> {
        if (restoring.value) {
            return Result.failure(IllegalStateException("복원 작업이 이미 진행 중입니다."))
        }
        val secret = password.copyOf()
        return viewModelScope.async {
            restoring.value = true
            try {
                PetWorldBackupRestorer(appContext).restoreFromUri(source, secret)
                pets.refresh()
                retryCare()
                Result.success(Unit)
            } catch (error: Exception) {
                recoveryBlocked.value = PetWorldRecoveryJournal(appContext).hasPending()
                if (!recoveryBlocked.value) {
                    pets.refresh()
                    retryCare()
                }
                Result.failure(error)
            } finally {
                secret.fill('\u0000')
                restoring.value = false
            }
        }.await()
    }

    suspend fun saveCare(draft: CareDraft): Result<CareEntity> = resultUnlessCancelled {
        care.save(draft)
    }

    suspend fun deleteCare(id: String, petId: String): Result<Unit> = resultUnlessCancelled {
        care.delete(id, petId)
    }

    val themeMode = preferences.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ThemeMode.SYSTEM,
    )

    val petUiState = combine(
        pets.profiles, pets.loadStatus, preferences.selectedPetId
    ) { profiles, status, selected ->
        PetUiState(status, profiles, chooseActivePet(profiles, selected))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = PetUiState(),
    )

    private val activePetId = petUiState.map { it.activePet?.id }.distinctUntilChanged()
    val careFeed = observeRetryableCare(
        combine(reloadCare, activePetId) { generation, petId -> generation to petId },
    ) { (_, petId) ->
        val entries = petId?.let(care::observePet) ?: flowOf(emptyList<CareEntity>())
        combine(entries, care.observeRecordCounts()) { records, counts ->
            CareFeedState.Ready(records, petId, counts)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = CareFeedState.Loading,
    )

    init {
        refreshPets()
    }

    fun refreshPets() {
        viewModelScope.launch { pets.refresh() }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun selectPet(id: String) {
        if (petUiState.value.activeProfiles.none { it.id == id }) return
        viewModelScope.launch { preferences.selectPet(id) }
    }

    suspend fun savePet(draft: PetDraft): Result<PetProfile> = resultUnlessCancelled {
        val saved = pets.save(draft)
        preferences.selectPet(saved.id)
        saved
    }

    suspend fun setArchived(id: String, archived: Boolean): Result<Unit> = resultUnlessCancelled {
        val selectedBefore = preferences.selectedPetId.first()
        pets.setArchived(id, archived)
        if (archived && selectedBefore == id) {
            preferences.selectPet(pets.profiles.value.firstOrNull { !it.isArchived }?.id)
        }
        if (!archived) preferences.selectPet(id)
    }

    suspend fun deletePet(id: String): Result<Unit> = resultUnlessCancelled {
        val selectedBefore = preferences.selectedPetId.first()
        pets.deleteForever(id)
        if (selectedBefore == id) {
            preferences.selectPet(pets.profiles.value.firstOrNull { !it.isArchived }?.id)
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java))
                    @Suppress("UNCHECKED_CAST")
                    val app = context.applicationContext
                    val db = PetWorldDatabase.get(app)
                    return MainViewModel(
                        app,
                        ThemePreferences(app),
                        PetRepository(app, db),
                        CareRepository(app, db),
                    ) as T
                }
            }
    }
}
