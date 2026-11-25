package com.haebi.petworld.auth

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.FirebaseApp
import com.haebi.petworld.data.account.DeviceDeletionAnchorStore
import com.haebi.petworld.data.account.DeviceDeletionRecoveryAccess
import com.haebi.petworld.data.account.LocalAccountScopeResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Register in Activity.onCreate, before STARTED. Only the actual Android
 * credential Activity result can release the pending request. No Intent extra
 * or previously cached app boolean constitutes authorization.
 * Not constructed by the current account UI.
 */
internal class FirebaseDevDeletionDeviceCredential(private val activity: ComponentActivity) {
    private val keyguard = requireNotNull(activity.getSystemService(KeyguardManager::class.java))
    private var pending: CompletableDeferred<Boolean>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pending
        pending = null
        request?.complete(result.resultCode == Activity.RESULT_OK &&
            keyguard.isDeviceSecure && !keyguard.isDeviceLocked)
    }

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                pending?.complete(false)
                pending = null
            }
        })
    }

    private suspend fun confirm(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (pending != null || !keyguard.isDeviceSecure ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@withContext false
        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "계정 삭제 상태 확인", "이 기기에 등록된 화면 잠금으로 본인을 확인해 주세요.",
        ) ?: return@withContext false
        val request = CompletableDeferred<Boolean>()
        pending = request
        try {
            launcher.launch(intent)
        } catch (_: Exception) {
            pending = null
            return@withContext false
        }
        // A cancelled caller must not recycle a still-pending system result.
        request.await()
    }

    fun createAccess(): DeviceDeletionRecoveryAccess {
        check(activity.packageName == "com.haebi.petworld.dev" &&
            FirebaseApp.getInstance().options.projectId == "petworld-dev")
        return DeviceDeletionRecoveryAccess(
            anchors = DeviceDeletionAnchorStore(activity),
            confirmDeviceCredential = ::confirm,
            currentScope = { runCatching { LocalAccountScopeResolver.resolve(activity) }.getOrNull() },
            foreground = {
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    keyguard.isDeviceSecure && !keyguard.isDeviceLocked
            },
        )
    }
}
