package com.haebi.petworld.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.components.PetStatus
import com.haebi.petworld.ui.components.PetStatusLabel
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.theme.PetSpacing

/** Signed-in FirebaseDev only. No network, sign-out, sync activation or data deletion.
 * A real request action will be connected only with a verified endpoint and durable
 * status-ticket/local isolation handling. Acknowledgement here is not submission.
 */
@Composable
internal fun FirebaseDevAccountDeletionSection(enabled: Boolean, accountLabel: String?) {
    var open by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    fun close() {
        open = false
        acknowledged = false
    }
    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text("계정 관리", style = MaterialTheme.typography.titleMedium)
        PetStatusLabel("삭제 요청 준비 중", PetStatus.WARNING)
        Text("계정 삭제와 로그아웃은 서로 다릅니다. 삭제 범위와 준비 상태를 확인할 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium)
        PetSecondaryButton("계정 삭제", enabled = enabled, modifier = Modifier.fillMaxWidth(),
            onClick = { acknowledged = false; open = true })
    }
    if (open) {
        AlertDialog(
            onDismissRequest = ::close,
            title = { Text("계정 삭제 안내") },
            text = {
                Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(PetSpacing.medium)) {
                    Text("삭제 대상", style = MaterialTheme.typography.titleMedium)
                    accountLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text("현재 연결된 Google 계정의 PetWorld 인증 정보, 클라우드 반려동물 프로필, 생활 기록, 사진 및 동기화 관련 자료입니다. Google 계정 자체는 삭제하지 않습니다.")
                    Text("기기에 남는 자료", style = MaterialTheme.typography.titleMedium)
                    Text("비회원 기록과 다른 계정의 자료는 삭제 대상이 아닙니다. 외부에 저장한 암호화 백업은 앱이 원격으로 삭제할 수 없습니다. 현재 계정의 기기 캐시 정리는 별도 검증이 필요합니다.")
                    Text("삭제 전 확인", style = MaterialTheme.typography.titleMedium)
                    Text("삭제 후 서버 자료를 복구할 수 없습니다. 미전송 기록을 포함한 필요한 자료를 먼저 보존해 주세요. 계정별 백업은 아직 제공되지 않습니다.")
                    Text("처리 절차", style = MaterialTheme.typography.titleMedium)
                    Text("Google 재인증 → 요청 확인 → 클라우드 접근 차단 → 기록·사진·인증 삭제 → 잔존 데이터 검증")
                    Text("현재 상태", style = MaterialTheme.typography.titleMedium)
                    PetStatusLabel("실제 삭제 요청 비활성", PetStatus.WARNING)
                    Text("삭제 서버, 보존 정책, 상태 추적 및 기기 캐시 정리 검증 전에는 삭제를 요청할 수 없습니다. 이 안내를 확인해도 계정이나 기록은 변경되지 않습니다.")
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = PetSpacing.minTouchTarget)
                        .clickable(role = Role.Checkbox) { acknowledged = !acknowledged }
                        .padding(vertical = PetSpacing.xSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PetSpacing.small)) {
                        Checkbox(checked = acknowledged, onCheckedChange = null)
                        Text("삭제 범위와 현재 제한 사항을 확인했습니다.", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (acknowledged) Text("안내 확인만 완료되었습니다. 삭제 요청은 접수되지 않았습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                PetSecondaryButton("닫기", onClick = ::close)
            },
        )
    }
}
