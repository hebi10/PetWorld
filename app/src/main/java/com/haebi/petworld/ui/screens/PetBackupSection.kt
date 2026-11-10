package com.haebi.petworld.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.haebi.petworld.data.backup.PetWorldBackupExporter
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.theme.PetSpacing
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun PetBackupSection(
    restoring: Boolean,
    onRestore: suspend (Uri, CharArray) -> Result<Unit>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var selectedRestoreFile by remember { mutableStateOf<Uri?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var showRestoreConfirmation by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination: Uri? ->
        if (destination == null) {
            password = ""
            confirmation = ""
        } else {
            val secret = password.toCharArray()
            password = ""
            confirmation = ""
            busy = true
            scope.launch {
                try {
                    PetWorldBackupExporter(context.applicationContext).exportToUri(destination, secret)
                    error = false
                    message = "암호화 백업 파일을 저장했습니다."
                } catch (failure: Exception) {
                    runCatching { context.contentResolver.delete(destination, null, null) }
                    error = true
                    message = failure.message ?: "백업 파일을 저장하지 못했습니다."
                } finally {
                    secret.fill('\u0000')
                    busy = false
                }
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        selectedRestoreFile = uri
        restorePassword = ""
        showRestoreConfirmation = false
    }

    fun clearRestore() {
        selectedRestoreFile = null
        restorePassword = ""
        showRestoreConfirmation = false
    }

    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text("데이터 관리", style = MaterialTheme.typography.titleMedium)
        Text(
            "프로필·생활 기록·사진·화면 설정을 암호화하여 보관하거나 복원합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PetPrimaryButton(
            label = if (busy) "처리 중..." else "암호화 백업 생성",
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !restoring,
            onClick = { showPasswordDialog = true },
        )
        PetSecondaryButton(
            label = "백업 파일 복원",
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !restoring,
            onClick = {
                message = null
                restoreLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
            },
        )
        Text(
            "복원하면 현재 프로필·생활 기록·설정을 백업 내용으로 교체합니다. 암호를 잊으면 백업을 열 수 없습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message != null) {
            Text(
                message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    showPasswordDialog = false
                    password = ""
                    confirmation = ""
                }
            },
            title = { Text("백업 암호 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.medium)) {
                    Text("백업 복원에 사용할 암호를 입력하세요. 암호는 기기에 저장되지 않습니다.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("암호") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("암호 확인") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        singleLine = true,
                        isError = confirmation.isNotEmpty() && password != confirmation,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && password.length >= 8 && password == confirmation,
                    onClick = {
                        showPasswordDialog = false
                        saveLauncher.launch("PetWorld-${LocalDate.now()}.petworld")
                    },
                ) { Text("저장 위치 선택") }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        showPasswordDialog = false
                        password = ""
                        confirmation = ""
                    },
                ) { Text("취소") }
            },
        )
    }

    if (selectedRestoreFile != null && !showRestoreConfirmation && !busy && !restoring) {
        AlertDialog(
            onDismissRequest = ::clearRestore,
            title = { Text("백업 암호 입력") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.medium)) {
                    Text("선택한 PetWorld 백업 파일의 암호를 입력해 주세요.")
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("백업 암호") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = restorePassword.isNotEmpty(),
                    onClick = { showRestoreConfirmation = true },
                ) { Text("다음") }
            },
            dismissButton = {
                TextButton(onClick = ::clearRestore) { Text("취소") }
            },
        )
    }

    if (selectedRestoreFile != null && showRestoreConfirmation && !busy && !restoring) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = false },
            title = { Text("기존 데이터를 교체할까요?") },
            text = {
                Text(
                    "현재 반려동물 프로필, 생활 기록, 사진 연결 및 화면 설정이 백업 데이터로 교체됩니다. " +
                        "기존 기록은 앱에서 다시 볼 수 없게 될 수 있으므로 필요한 경우 먼저 백업해 주세요."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val source = selectedRestoreFile ?: return@TextButton
                    val secret = restorePassword.toCharArray()
                    clearRestore()
                    busy = true
                    scope.launch {
                        try {
                            val result = onRestore(source, secret)
                            result.fold(
                                onSuccess = {
                                    error = false
                                    message = "백업 복원이 완료되었습니다. 프로필과 기록을 새로 불러왔습니다."
                                },
                                onFailure = {
                                    error = true
                                    message = it.message ?: "백업 복원에 실패했습니다. 현재 데이터를 확인해 주세요."
                                },
                            )
                        } finally {
                            secret.fill('\u0000')
                            busy = false
                        }
                    }
                }) { Text("데이터 교체 및 복원") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmation = false }) { Text("뒤로") }
            },
        )
    }
}
