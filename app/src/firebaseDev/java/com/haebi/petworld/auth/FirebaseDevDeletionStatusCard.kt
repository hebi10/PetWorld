package com.haebi.petworld.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.haebi.petworld.data.account.AccountDeletionStatusView
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.components.PetStatus
import com.haebi.petworld.ui.components.PetStatusLabel
import com.haebi.petworld.ui.components.PetSurfaceCard

@Composable
internal fun FirebaseDevDeletionStatusCard(
    status: AccountDeletionStatusView, enabled: Boolean, onReloadLocal: () -> Unit,
) {
    if (status == AccountDeletionStatusView.None) return
    PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Text("계정 삭제 상태", style = MaterialTheme.typography.titleMedium)
        when (status) {
            AccountDeletionStatusView.Checking -> Text("기기에 보관된 요청 정보를 확인하고 있습니다.")
            AccountDeletionStatusView.Unknown -> {
                PetStatusLabel("처리 결과 확인 필요", PetStatus.WARNING)
                Text("요청 결과를 확인하지 못했습니다. 새로운 삭제 요청으로 처리하지 않습니다.")
            }
            is AccountDeletionStatusView.Saved -> {
                Text("기기에 저장된 마지막 확인 상태", style = MaterialTheme.typography.labelMedium)
                PetStatusLabel(status.phase.title, if (status.phase.serverDeletionComplete) PetStatus.SUCCESS else PetStatus.WARNING)
                Text(status.phase.detail)
            }
            AccountDeletionStatusView.None -> Unit
        }
        Text("클라우드 보관과 동기화는 차단 상태입니다. 실제 서버 상태 조회는 검증 후 제공됩니다. 기기 기록은 자동 삭제하지 않습니다.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PetSecondaryButton("저장된 상태 다시 확인", onClick = onReloadLocal,
            enabled = enabled && status != AccountDeletionStatusView.Checking, modifier = Modifier.fillMaxWidth())
    }
}
