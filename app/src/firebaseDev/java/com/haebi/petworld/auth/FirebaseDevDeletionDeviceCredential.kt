package com.haebi.petworld.auth

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.atomic.AtomicLong
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
 * Used for explicitly requested local-only guest recovery; live requests remain disabled.
 */
internal class FirebaseDevDeletionDeviceCredential(private val activity: ComponentActivity) {
    private val keyguard = requireNotNull(activity.getSystemService(KeyguardManager::class.java))
    private var pending: CompletableDeferred<Boolean>? = null
    private val auth = FirebaseAuth.getInstance()
    private val version = AtomicLong()
    private var lastUid = auth.currentUser?.uid
    private val authListener = FirebaseAuth.AuthStateListener { changed ->
        val uid = changed.currentUser?.uid
        if (uid != lastUid) {
            lastUid = uid
            version.incrementAndGet()
        }
    }
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pending
        pending = null
        request?.complete(result.resultCode == Activity.RESULT_OK &&
            keyguard.isDeviceSecure && !keyguard.isDeviceLocked)
    }

    init {
        auth.addAuthStateListener(authListener)
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            // A normal background/foreground transition must not reuse an old
            // recovery session. The pending system credential Activity itself is exempt.
            if (event == Lifecycle.Event.ON_STOP && pending == null) version.incrementAndGet()
            if (event == Lifecycle.Event.ON_DESTROY) {
                auth.removeAuthStateListener(authListener)
                version.incrementAndGet()
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
            sessionVersion = version::get,
            foreground = {
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    keyguard.isDeviceSecure && !keyguard.isDeviceLocked
            },
        )
    }
}
