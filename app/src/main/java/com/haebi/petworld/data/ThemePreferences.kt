package com.haebi.petworld.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.haebi.petworld.data.backup.PetWorldMutationGate
import kotlinx.coroutines.sync.withLock
import com.haebi.petworld.ui.theme.ThemeMode
import kotlinx.coroutines.flow.map

private val Context.petWorldDataStore by preferencesDataStore(name = "petworld_settings")

class ThemePreferences(private val context: Context) {
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val selectedPetKey = stringPreferencesKey("selected_pet_id")

    val themeMode = context.petWorldDataStore.data.map { preferences ->
        ThemeMode.fromStoredValue(preferences[themeModeKey])
    }

    val selectedPetId = context.petWorldDataStore.data.map { preferences ->
        preferences[selectedPetKey]
    }

    suspend fun setThemeMode(mode: ThemeMode) = PetWorldMutationGate.mutex.withLock {
        applyThemeWithoutGate(mode)
    }

    private suspend fun applyThemeWithoutGate(mode: ThemeMode) {
        context.petWorldDataStore.edit { preferences -> preferences[themeModeKey] = mode.name }
    }

    suspend fun selectPet(id: String?) = PetWorldMutationGate.mutex.withLock {
        context.petWorldDataStore.edit { preferences ->
            if (id == null) preferences.remove(selectedPetKey)
            else preferences[selectedPetKey] = id
        }
    }

    /** Called only while the global mutation gate is already held. */
    suspend fun applyRestoredSettings(mode: ThemeMode, id: String?) {
        context.petWorldDataStore.edit { preferences ->
            preferences[themeModeKey] = mode.name
            if (id == null) preferences.remove(selectedPetKey)
            else preferences[selectedPetKey] = id
        }
    }
}
