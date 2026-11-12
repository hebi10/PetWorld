package com.haebi.petworld.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import com.google.firebase.auth.FirebaseAuth
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.theme.PetSpacing
import kotlinx.coroutines.launch

@Composable
fun FirstImportSection(auth: FirebaseAuth) {
    val context = LocalContext.current
    val service = remember(context) { FirebaseDevFirstImportService(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<FirstImportPreview?>(null) }
    var confirmOpen by remember { mutableStateOf(false) }
    var backupConfirmed by remember { mutableStateOf(false) }
    var permitOtherCloudData by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }

    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text("기기 기록 최초 보관", style = MaterialTheme.typography.titleMedium)
        Text(
            "비회원 기록을 현재 Google 계정의 Firebase에 복사합니다. 자동 동기화가 아니며, " +
                "비회원 원본은 삭제되지 않습니다. 백업을 만든 뒤 직접 승인해야 시작합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "이미 계정에 있는 다른 기록은 유지합니다. 같은 ID의 내용이 다르면 덮어쓰지 않고 중단합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        PetPrimaryButton(
            label = "기기 기록·클라우드 상태 확인",
            enabled = !working && auth.currentUser != null,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                working = true
                preview = null
                feedback = null
                progress = "로컬 원본·서버 기록 읽기"
                scope.launch {
                    try {
                        val found = service.preview()
                        preview = found
                        confirmOpen = found.canImport
                        backupConfirmed = false
                        permitOtherCloudData = false
                        if (found.conflicts > 0) {
                            feedback = "서버에 같은 ID의 다른 기록이나 사진이 있습니다. 이전하지 않았습니다."
                        } else if (found.petCount == 0) {
                            feedback = "기기에 이전할 반려동물 기록이 없습니다."
                        } else {
                            feedback = "미리보기가 완료됐습니다. 암호화 백업 상태를 확인해 주세요."
                        }
                    } catch (_: Exception) {
                        feedback = "기록을 확인하지 못했습니다. 로그인·네트워크·사진 파일을 확인해 주세요."
                    } finally {
                        working = false
                        progress = null
                    }
                }
            },
        )

        preview?.let { item ->
            Text(
                "비회원 프로필 ${item.petCount}개 · 기록 ${item.recordCount}개 · 사진 참조 ${item.photoCount}개",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "클라우드 동일 항목 ${item.matchingCloudDocuments}개 · 추가 클라우드 항목 " +
                    "${item.extraCloudDocuments}개 · 충돌 ${item.conflicts}개",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (item.conflicts > 0) {
                Text(
                    "충돌은 수동으로 검토해야 합니다. 어떤 기록도 덮어쓰지 않습니다.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    val candidate = preview
    if (confirmOpen && candidate != null && candidate.canImport) {
        AlertDialog(
            onDismissRequest = { if (!working) confirmOpen = false },
            title = { Text("비회원 기록을 Firebase에 복사") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(PetSpacing.medium)) {
                    Text("대상: 현재 로그인한 개발 Firebase 계정")
                    Text("프로필 ${candidate.petCount}개 · 기록 ${candidate.recordCount}개 · 사진 참조 ${candidate.photoCount}개")
                    Text(
                        "기존 비회원 기록은 그대로 유지합니다. 서버에 이미 있는 항목은 동일한 " +
                            "내용일 때만 건너뛰고, 다른 내용이면 중단합니다."
                    )
                    Text(
                        "로그아웃한 상태에서 설정의 백업·복원 메뉴로 .petworld 암호화 백업을 " +
                            "먼저 만든 뒤 다시 로그인해 주세요."
                    )
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = backupConfirmed,
                            enabled = !working,
                            onCheckedChange = { backupConfirmed = it },
                        )
                        Text("암호화 .petworld 백업을 직접 만들고 안전하게 보관했습니다.")
                    }
                    if (candidate.extraCloudDocuments > 0) {
                        Text(
                            "클라우드에 이 기기에 없는 기록 ${candidate.extraCloudDocuments}개가 있습니다. " +
                                "기존 클라우드 기록은 변경하지 않고 새 기록만 추가합니다."
                        )
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = permitOtherCloudData,
                                enabled = !working,
                                onCheckedChange = { permitOtherCloudData = it },
                            )
                            Text("기존 클라우드 기록 유지·새 항목만 추가에 동의합니다.")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && backupConfirmed &&
                        (candidate.extraCloudDocuments == 0 || permitOtherCloudData),
                    onClick = {
                        working = true
                        progress = "복사 준비 중"
                        feedback = null
                        scope.launch {
                            try {
                                val result = service.commit(
                                    candidate,
                                    encryptedBackupConfirmed = backupConfirmed,
                                    allowCloudExtras = permitOtherCloudData,
                                    onProgress = { progress = it },
                                )
                                feedback = "서버 대조 완료: 프로필 ${result.confirmedPets}개, " +
                                    "기록 ${result.confirmedRecords}개, 사진 참조 ${result.confirmedPhotos}개. " +
                                    "비회원 원본은 보존됐고 자동 동기화는 시작되지 않았습니다."
                                confirmOpen = false
                                preview = null
                            } catch (_: FirstImportConflict) {
                                feedback = "충돌로 중단했습니다. 이미 생성된 동일 항목은 재시도할 때 건너뜁니다. " +
                                    "기존 기기 기록은 삭제되지 않았습니다."
                                confirmOpen = false
                                preview = null
                            } catch (_: Exception) {
                                feedback = "중단됐습니다. 일부 항목은 이미 서버에 생성됐을 수 있습니다. " +
                                    "기기 원본은 유지되며, 다시 미리보기 후 이어서 복사할 수 있습니다."
                                confirmOpen = false
                                preview = null
                            } finally {
                                working = false
                                progress = null
                            }
                        }
                    },
                ) { Text("확인 후 복사 시작") }
            },
            dismissButton = {
                TextButton(
                    enabled = !working,
                    onClick = { confirmOpen = false },
                ) { Text("취소") }
            },
        )
    }
}
