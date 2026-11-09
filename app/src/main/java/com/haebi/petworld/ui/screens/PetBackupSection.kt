package com.haebi.petworld.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * Data-management entry point. Restore remains disabled until process-death recovery is ready.
 * This avoids exposing a destructive action that can leave mixed data after app termination.
 */
@Composable
fun PetBackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination: Uri? ->
        if (destination == null) {
            password = ""
            confirmation = ""
            busy = false
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
                    runCatching {
                        context.contentResolver.delete(destination, null, null)
                    }
                    error = true
                    message = failure.message ?: "백업 파일을 저장하지 못했습니다."
                } finally {
                    secret.fill('\u0000')
                    busy = false
                }
            }
        }
    }

    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text("데이터 관리", style = MaterialTheme.typography.titleMedium)
        Text(
            "반려동물 프로필, 생활 기록, 사진, 화면 설정을 하나의 암호화 파일로 보관합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PetPrimaryButton(
            label = if (busy) "백업 생성 중..." else "암호화 백업 생성",
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = { showPasswordDialog = true },
        )
        PetSecondaryButton(
            label = "백업 복원 (안전성 검증 전)",
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
            onClick = {},
        )
        Text(
            "복원 기능은 앱 강제 종료 후 데이터 복구 검증이 끝나면 활성화됩니다. 백업 암호를 잊으면 파일을 복원할 수 없습니다.",
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
                        launcher.launch("PetWorld-${LocalDate.now()}.petworld")
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
}
