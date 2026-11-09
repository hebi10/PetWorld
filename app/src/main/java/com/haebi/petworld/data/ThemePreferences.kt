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

    val themeMode = context.petWorldDataStore.data.map { preferences ->
        ThemeMode.fromStoredValue(preferences[themeModeKey])
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.petWorldDataStore.edit { preferences ->
            preferences[themeModeKey] = mode.name
        }
    }
}
