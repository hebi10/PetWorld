package com.haebi.petworld

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haebi.petworld.ui.PetWorldApp
import com.haebi.petworld.ui.theme.PetWorldTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewModel = ViewModelProvider(
            this,
            MainViewModel.factory(applicationContext),
        )[MainViewModel::class.java]

        setContent {
            val themeMode = viewModel.themeMode.collectAsStateWithLifecycle().value
            val state = viewModel.petUiState.collectAsStateWithLifecycle().value
            val care = viewModel.careFeed.collectAsStateWithLifecycle().value

            PetWorldTheme(themeMode = themeMode) {
                PetWorldApp(
                    themeMode = themeMode,
                    petState = state,
                    careFeed = care,
                    photoStore = viewModel.photoStore,
                    onThemeModeChange = viewModel::setThemeMode,
                    onSelectPet = viewModel::selectPet,
                    onSavePet = viewModel::savePet,
                    onArchivedChange = viewModel::setArchived,
                    onDeletePet = viewModel::deletePet,
                    onRetry = viewModel::refreshPets,
                    onSaveCare = viewModel::saveCare,
                    onDeleteCare = viewModel::deleteCare,
                    onRetryCare = viewModel::retryCare,
                )
            }
        }
    }
}
