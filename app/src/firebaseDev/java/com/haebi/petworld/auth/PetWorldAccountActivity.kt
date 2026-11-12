package com.haebi.petworld.auth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.haebi.petworld.ui.components.PetPrimaryButton
import com.haebi.petworld.ui.components.PetSecondaryButton
import com.haebi.petworld.ui.components.PetSurfaceCard
import com.haebi.petworld.ui.theme.PetSpacing
import com.haebi.petworld.ui.theme.PetWorldTheme
import kotlinx.coroutines.launch

class PetWorldAccountActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val verifiedDevProject = packageName == "com.haebi.petworld.dev" &&
            FirebaseApp.getApps(this).any {
                it.name == FirebaseApp.DEFAULT_APP_NAME &&
                    it.options.projectId == "petworld-dev"
            }
        val firebaseAuth = if (verifiedDevProject) {
            runCatching { FirebaseAuth.getInstance() }.getOrNull()
        } else {
            null
        }

        setContent {
            PetWorldTheme {
                AccountContent(
                    auth = firebaseAuth,
                    onClose = ::finish,
                )
            }
        }
    }

    @Composable
    private fun AccountContent(
        auth: FirebaseAuth?,
        onClose: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var waiting by remember { mutableStateOf(false) }
        var feedback by rememberSaveable { mutableStateOf<String?>(null) }
        var currentAccount by remember(auth) {
            mutableStateOf(auth?.currentUser?.let {
                GoogleLoginOutcome.SignedIn(it.uid, it.email)
            })
        }
        val gateway = remember(auth) { auth?.let { FirebaseGoogleAuthGateway(it) } }

        DisposableEffect(auth) {
            val listener = auth?.let { firebase ->
                FirebaseAuth.AuthStateListener { changed ->
                    currentAccount = changed.currentUser?.let {
                        GoogleLoginOutcome.SignedIn(it.uid, it.email)
                    }
                }.also(firebase::addAuthStateListener)
            }
            onDispose {
                if (listener != null) auth?.removeAuthStateListener(listener)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PetSpacing.large),
            verticalArrangement = Arrangement.spacedBy(PetSpacing.large),
        ) {
            Text("계정", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Google 계정 연결은 선택 사항입니다. 로그인 없이도 반려동물 기록을 사용할 수 있습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Text("계정 상태", style = MaterialTheme.typography.titleMedium)
                when {
                    auth == null -> {
                        Text("개발 Firebase 설정을 확인할 수 없습니다.")
                        Text("개발 프로젝트와 앱 패키지 설정을 확인해 주세요.")
                    }
                    currentAccount == null -> {
                        Text("로그인하지 않음", style = MaterialTheme.typography.bodyLarge)
                        Text("현재는 로그인 없이 사용 중입니다.")
                    }
                    else -> {
                        Text("Google 계정 연결됨", style = MaterialTheme.typography.bodyLarge)
                        currentAccount?.email?.let { email ->
                            Text(email, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "Firebase 로그인 상태가 감지됐습니다. 클라우드 기록 동기화는 아직 시작되지 않았습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            PetSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Text("이 기기의 기록", style = MaterialTheme.typography.titleMedium)
                Text(
                    "현재 반려동물 프로필과 돌봄 기록은 이 기기에만 저장됩니다. " +
                        "Google 계정에 연결하거나 로그아웃해도 로컬 기록은 삭제·이전되지 않습니다. " +
                        "계정별 기록 분리와 클라우드 동기화는 추후 제공됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (auth != null && currentAccount == null) {
                PetPrimaryButton(
                    label = "Google 계정으로 시작",
                    enabled = !waiting,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val readyGateway = gateway ?: return@PetPrimaryButton
                        waiting = true
                        feedback = null
                        scope.launch {
                            try {
                                feedback = when (readyGateway.signIn(this@PetWorldAccountActivity)) {
                                    is GoogleLoginOutcome.SignedIn -> "Google 계정에 연결했습니다. 기존 기록은 이 기기에 그대로 보관됩니다."
                                    GoogleLoginOutcome.Cancelled -> "Google 계정 선택을 취소했습니다."
                                    GoogleLoginOutcome.Busy -> "이미 로그인 요청을 처리 중입니다."
                                    GoogleLoginOutcome.MissingConfiguration -> "Firebase 로그인 설정을 확인해 주세요."
                                    GoogleLoginOutcome.InvalidCredential -> "Google 인증 정보를 확인하지 못했습니다."
                                    GoogleLoginOutcome.NetworkUnavailable -> "네트워크 연결을 확인한 후 다시 시도해 주세요."
                                    GoogleLoginOutcome.ProviderUnavailable -> "Google 로그인 서비스를 사용할 수 없습니다."
                                    GoogleLoginOutcome.AuthenticationRejected -> "Google 계정 인증에 실패했습니다."
                                }
                            } finally {
                                waiting = false
                            }
                        }
                    },
                )
            }
            if (auth != null && currentAccount != null) {
                PetSecondaryButton(
                    label = "로그아웃",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !waiting,
                    onClick = {
                        val readyGateway = gateway ?: return@PetSecondaryButton
                        waiting = true
                        feedback = null
                        scope.launch {
                            try {
                                readyGateway.signOut(this@PetWorldAccountActivity)
                                feedback = "로그아웃했습니다. 이 기기의 기록은 유지됩니다."
                            } finally {
                                waiting = false
                            }
                        }
                    },
                )
            }
            if (waiting) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            if (feedback != null) {
                Text(
                    text = feedback.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            PetSecondaryButton(
                label = if (currentAccount == null) "로그인 없이 사용" else "기록 화면으로",
                modifier = Modifier.fillMaxWidth(),
                enabled = !waiting,
                onClick = onClose,
            )
            Text(
                "계정 삭제와 서버 기록 삭제는 이 화면에서 제공하지 않습니다. " +
                    "현재 앱은 서버로 반려동물 기록을 업로드하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
