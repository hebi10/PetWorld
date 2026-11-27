package com.haebi.petworld.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.CloudWorkKind
import com.haebi.petworld.auth.FirebaseDevCloudWork
import com.haebi.petworld.data.room.PetWorldDatabase
import com.haebi.petworld.data.sync.SyncConflictReview
import com.haebi.petworld.data.sync.SyncDocumentSource
import com.haebi.petworld.data.sync.SyncInboundEntry
import com.haebi.petworld.data.sync.SyncPermission
import com.haebi.petworld.data.sync.SyncReviewApproval
import com.haebi.petworld.data.sync.SyncStatusReader
import com.haebi.petworld.data.sync.SyncStatusSnapshot
import com.haebi.petworld.data.sync.canOfferRemoteReview
import com.haebi.petworld.data.sync.reviewComparison
import com.haebi.petworld.data.sync.reviewStatusLabel
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.components.PetStatus
import com.haebi.petworld.ui.components.PetStatusLabel
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.theme.PetSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private sealed interface StatusUiState {
    data object Loading : StatusUiState
    data object Failed : StatusUiState
    data class Ready(val value: SyncStatusSnapshot) : StatusUiState
}

@Composable
internal fun FirebaseDevSyncStatusSection(account: LocalAccountScope.SignedIn) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lease by FirebaseDevSyncWork.gate.activeLease.collectAsStateWithLifecycle()
    val authorized = lease?.let { FirebaseDevSyncWork.allowed(app, it, account) } == true
    if (FirebaseDevSyncWork.account(app) != account) return
    var limit by remember(account) { mutableStateOf(20) }
    var reload by remember(account) { mutableStateOf(0) }
    var selected by remember(account) { mutableStateOf<SyncInboundEntry?>(null) }
    var feedback by remember(account) { mutableStateOf<String?>(null) }
    var applying by remember(account) { mutableStateOf(false) }
    val statusFlow = remember(app, account, limit, reload) {
        flow<StatusUiState> {
            if (FirebaseDevSyncWork.account(app) != account) return@flow
            val db = PetWorldDatabase.get(app, account)
            emitAll(SyncStatusReader(db, account).observe(limit)
                .map<SyncStatusSnapshot, StatusUiState> { StatusUiState.Ready(it) })
        }.catch { error ->
            if (error is CancellationException) throw error
            emit(StatusUiState.Failed)
        }
    }
    val ui by statusFlow.collectAsStateWithLifecycle(initialValue = StatusUiState.Loading)
    val ready = (ui as? StatusUiState.Ready)?.value
    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text("클라우드 동기화", style = MaterialTheme.typography.titleMedium)
        if (!authorized) {
            Text("실서버 검증과 동의가 완료되기 전에는 자동 전송하지 않습니다.",
                style = MaterialTheme.typography.bodyMedium)
        }
        when (val state = ui) {
            StatusUiState.Loading -> CircularProgressIndicator()
            StatusUiState.Failed -> Text("동기화 상태를 불러오지 못했습니다. 기록은 그대로 보존됩니다.",
                color = MaterialTheme.colorScheme.error)
            is StatusUiState.Ready -> {
                val value = state.value
                PetStatusLabel(value.summary(authorized),
                    if (value.reviewCount > 0 || value.blocked > 0) PetStatus.WARNING else PetStatus.NEUTRAL)
                SyncCountRow("전송 대기 및 미확정", "${value.pending}건")
                SyncCountRow("전송 확인 완료", "${value.acknowledged}건")
                SyncCountRow("재시도 또는 결과 확인", "${value.retry}건")
                SyncCountRow("서버 기준 확인 대기", "${value.waitingBaseline}건")
                SyncCountRow("전송 차단·충돌", "${value.blocked}건")
                SyncCountRow("원격 검토 항목", "${value.reviewCount}건")
                Text(when {
                    value.scan == null -> "서버 조회: 시작 전"
                    value.scan.complete() -> "서버 조회: 마지막 저장 라운드 완료"
                    else -> "서버 조회: 저장된 위치에서 재개 가능"
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
        PetSecondaryButton("상태 새로고침", onClick = { reload++ }, modifier = Modifier.fillMaxWidth())
        PetPrimaryButton("동기화 다시 시도", enabled = authorized && !applying,
            onClick = {
                val current = lease
                feedback = try {
                    if (current != null && FirebaseDevSyncWork.enqueue(app, current))
                        "동기화 작업을 요청했습니다. 전송 결과는 이후 상태에서 확인해 주세요."
                    else "검증된 동기화 세션이 필요합니다."
                } catch (_: Exception) {
                    "동기화 작업을 요청하지 못했습니다. 상태를 확인한 후 다시 시도해 주세요."
                }
            }, modifier = Modifier.fillMaxWidth())
        feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    if (ready != null && ready.reviews.isNotEmpty()) {
        PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
            Text("충돌 및 변경 검토", style = MaterialTheme.typography.titleMedium)
            Text("서버와 기기의 기록을 비교할 수 있습니다. 승인 전에는 기록을 변경하지 않습니다.",
                style = MaterialTheme.typography.bodyMedium)
            ready.reviews.forEach { entry ->
                HorizontalDivider()
                Text(if (entry.entityKind == "PET") "반려동물 프로필" else "생활 기록")
                PetStatusLabel(reviewStatusLabel(entry.status), PetStatus.WARNING)
                Text("서버 리비전 ${entry.revision}", style = MaterialTheme.typography.bodySmall)
                PetSecondaryButton("기록 비교", modifier = Modifier.fillMaxWidth(), enabled = !applying,
                    onClick = { selected = entry; feedback = null })
            }
            if (ready.reviewCount > ready.reviews.size && limit < 500) {
                PetSecondaryButton("검토 항목 더 보기", onClick = { limit = minOf(500, limit + 20) },
                    modifier = Modifier.fillMaxWidth())
            } else if (ready.reviewCount > ready.reviews.size) {
                Text("최근 500건까지 표시합니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val entry = selected
    if (entry != null) {
        val comparisons = remember(entry) { runCatching { reviewComparison(entry) }.getOrNull() }
        val eligible = canOfferRemoteReview(entry, authorized) && comparisons != null
        AlertDialog(
            onDismissRequest = { if (!applying) selected = null },
            title = { Text(reviewStatusLabel(entry.status)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(PetSpacing.medium)) {
                    Text("서버 리비전 ${entry.revision} · 검토용으로 저장된 값")
                    if (comparisons == null) Text("비교 데이터를 읽을 수 없습니다.")
                    else comparisons.forEach { field ->
                        PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                            Text(field.label, style = MaterialTheme.typography.titleSmall)
                            Text("이 기기", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(field.local, style = MaterialTheme.typography.bodyMedium)
                            HorizontalDivider()
                            Text("서버", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(field.remote, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(if (eligible)
                        "서버 기록 선택 시 미전송 변경만 대체 처리하며 원본 이력은 보존합니다. 현재 값이 바뀌었거나 전송 결과가 불명확하면 적용되지 않습니다."
                    else "현재는 비교만 가능합니다. 사진·삭제·미검증 세션은 자동 적용하지 않습니다.")
                    if (applying) CircularProgressIndicator()
                }
            },
            confirmButton = {
                PetPrimaryButton("서버 기록 적용", enabled = eligible && !applying, onClick = {
                    val current = lease ?: return@PetPrimaryButton
                    if (!FirebaseDevSyncWork.allowed(app, current, account)) return@PetPrimaryButton
                    applying = true
                    scope.launch {
                        val permit = FirebaseDevCloudWork.tracker.tryBegin(account, CloudWorkKind.SYNC_REVIEW)
                        if (permit == null) {
                            applying = false
                            return@launch
                        }
                        try {
                            if (!FirebaseDevSyncWork.allowed(app, current, account)) return@launch
                            val permission = SyncPermission { candidate ->
                                candidate == account && FirebaseDevSyncWork.allowed(app, current, account)
                            }
                            val reader = FirebaseDevRemoteReader(app, permission)
                            val approval = SyncReviewApproval(account.key, entry.entityKind, entry.petId,
                                entry.entityId, entry.revision, entry.payloadDigest, entry.localJson, true)
                            val accepted = SyncConflictReview(PetWorldDatabase.get(app, account), account,
                                SyncDocumentSource { a, kind, pet, id -> reader.readDocument(a, kind, pet, id) },
                                permission).accept(approval)
                            feedback = if (accepted) "서버 기록을 반영했습니다. 이전 기록은 변경 이력에 보존했습니다."
                                else "기록이 변경되었거나 안전 조건을 충족하지 않아 적용하지 않았습니다."
                            selected = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            feedback = "서버 기록 적용 결과를 확인하지 못했습니다. 상태를 새로고침해 주세요."
                            selected = null
                        } finally {
                            permit.close()
                            applying = false
                        }
                    }
                })
            },
            dismissButton = {
                PetSecondaryButton("현재 기록 유지", enabled = !applying, onClick = { selected = null })
            },
        )
    }
}

@Composable
private fun SyncCountRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = PetSpacing.xSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PetSpacing.small),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
    HorizontalDivider()
}
