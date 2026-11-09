package com.haebi.petworld.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    suspend fun setThemeMode(mode: ThemeMode) {
        context.petWorldDataStore.edit { preferences ->
            preferences[themeModeKey] = mode.name
        }
    }

    suspend fun selectPet(id: String?) {
        context.petWorldDataStore.edit { preferences ->
            if (id == null) preferences.remove(selectedPetKey)
            else preferences[selectedPetKey] = id
        }
    }
}
