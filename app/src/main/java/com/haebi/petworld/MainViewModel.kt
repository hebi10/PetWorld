package com.haebi.petworld

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.haebi.petworld.data.PetDraft
import com.haebi.petworld.data.PetLoadStatus
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.PetProfile
import com.haebi.petworld.data.PetRepository
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.chooseActivePet
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PetUiState(
    val loadStatus: PetLoadStatus = PetLoadStatus.Loading,
    val profiles: List<PetProfile> = emptyList(),
    val activePet: PetProfile? = null,
) {
    val activeProfiles: List<PetProfile> get() = profiles.filterNot { it.isArchived }
}

class MainViewModel(
    private val preferences: ThemePreferences,
    private val pets: PetRepository,
) : ViewModel() {
    val photoStore: PetPhotoStore = pets.photoStore

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

    suspend fun savePet(draft: PetDraft): Result<PetProfile> = runCatching {
        val saved = pets.save(draft)
        preferences.selectPet(saved.id)
        saved
    }

    suspend fun setArchived(id: String, archived: Boolean): Result<Unit> = runCatching {
        pets.setArchived(id, archived)
        val active = petUiState.value.activePet
        if (archived && active?.id == id) {
            preferences.selectPet(pets.profiles.value.firstOrNull { !it.isArchived }?.id)
        }
        if (!archived) preferences.selectPet(id)
    }

    suspend fun deletePet(id: String): Result<Unit> = runCatching {
        pets.deleteForever(id)
        if (petUiState.value.activePet?.id == id) {
            preferences.selectPet(pets.profiles.value.firstOrNull { !it.isArchived }?.id)
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java))
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(
                        ThemePreferences(context.applicationContext),
                        PetRepository(context.applicationContext),
                    ) as T
                }
            }
    }
}
