package com.haebi.petworld

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.data.backup.PetWorldRecoveryJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haebi.petworld.ui.PetWorldApp
import com.haebi.petworld.ui.theme.PetWorldTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PetWorldTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val app = applicationContext
                    PetWorldRecoveryJournal(app).recoverIfPending(
                        PetWorldDatabase.get(app), ThemePreferences(app)
                    )
                }
            } catch (error: Exception) {
                setContent {
                    PetWorldTheme {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "이전 데이터 복구를 완료하지 못했습니다. 앱 데이터를 변경하지 않고 종료해 주세요. " +
                                    (error.message ?: "복구 저널을 확인할 수 없습니다."),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                return@launch
            }
            val viewModel = ViewModelProvider(
                this@MainActivity,
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
}
