package com.haebi.petworld

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import com.haebi.petworld.ui.theme.ThemeMode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.LocalAccountScopeResolver
import com.haebi.petworld.data.account.isGuest
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
    private var activeScope: LocalAccountScope = LocalAccountScope.Guest
    private var accountReady = false

    override fun onResume() {
        super.onResume()
        if (!accountReady) return
        val resolved = runCatching { LocalAccountScopeResolver.resolve(applicationContext) }
        if (resolved.isFailure) {
            accountReady = false
            showAccountScopeFailure()
            return
        }
        if (resolved.getOrThrow() != activeScope) {
            restartForAccountSwitch()
        }
    }

    private fun restartForAccountSwitch() {
        accountReady = false
        viewModelStore.clear()
        PetWorldDatabase.closeScope(activeScope)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showAccountScopeFailure() {
        setContent {
            PetWorldTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "계정별 저장소를 확인할 수 없습니다. 앱을 종료한 후 다시 실행해 주세요.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PetWorldTheme {
                PetWorldSystemBarContrast(ThemeMode.SYSTEM)
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
                        PetWorldSystemBarContrast(ThemeMode.SYSTEM)
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
            val resolvedScope = runCatching {
                LocalAccountScopeResolver.resolve(applicationContext)
            }.getOrElse {
                showAccountScopeFailure()
                return@launch
            }
            activeScope = resolvedScope
            accountReady = true
            val viewModel = ViewModelProvider(
                this@MainActivity,
                MainViewModel.factory(applicationContext, resolvedScope),
            )[MainViewModel::class.java]

            setContent {
                val themeMode = viewModel.themeMode.collectAsStateWithLifecycle().value
                val state = viewModel.petUiState.collectAsStateWithLifecycle().value
                val care = viewModel.careFeed.collectAsStateWithLifecycle().value
                val restoring = viewModel.restoring.collectAsStateWithLifecycle().value
                val recoveryBlocked = viewModel.recoveryBlocked.collectAsStateWithLifecycle().value

                PetWorldTheme(themeMode = themeMode) {
                    PetWorldSystemBarContrast(themeMode)
                    if (recoveryBlocked) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "복원 후 데이터를 안전하게 되돌리지 못했습니다. " +
                                    "앱을 다시 실행하여 자동 복구를 진행해 주세요. " +
                                    "복구에 실패한다면 데이터를 변경하지 마세요.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        PetWorldApp(
                            themeMode = themeMode,
                            guestScope = resolvedScope.isGuest,
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
                            restoring = restoring,
                            onRestoreBackup = viewModel::restoreBackup,
                        )
                    }
                }
            }
        }
    }
    @Composable
    private fun PetWorldSystemBarContrast(mode: ThemeMode) {
        val dark = mode.shouldUseDark(isSystemInDarkTheme())
        SideEffect {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

}
