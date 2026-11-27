package com.haebi.petworld.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.haebi.petworld.data.account.AccountCacheTransitionDecision
import com.haebi.petworld.data.account.AccountCacheTransitionEvidence
import com.haebi.petworld.data.account.AccountDeletionJournal
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.accountMemoryAppName

/**
 * Configuration only. Does not initialize a second FirebaseApp, copy a login
 * credential, access cloud data, terminate or clear the default SDK instance.
 * A reviewed same-account bootstrap must own the separate named app lifecycle.
 * No current reader/writer is routed here; legacy migration remains blocked.
 */
internal object FirebaseDevAccountMemoryCache {
    internal fun settings(base: FirebaseFirestoreSettings): FirebaseFirestoreSettings =
        FirebaseFirestoreSettings.Builder(base)
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()

    fun prepare(
        context: Context,
        account: LocalAccountScope.SignedIn,
        isolatedApp: FirebaseApp,
        evidence: AccountCacheTransitionEvidence,
    ): FirebaseFirestore? = runCatching {
        if (context.applicationContext.packageName != "com.haebi.petworld.dev" ||
            evidence.decide(account) != AccountCacheTransitionDecision.PREPARE_ISOLATED_MEMORY_CLIENT ||
            isolatedApp.name != accountMemoryAppName(account)) return null
        val primary = FirebaseApp.getInstance()
        if (primary.options.projectId != "petworld-dev" ||
            isolatedApp.options.projectId != "petworld-dev" ||
            isolatedApp.options.applicationId != primary.options.applicationId ||
            isolatedApp.options.storageBucket != primary.options.storageBucket) return null

        fun sameAccount(): Boolean {
            val mainUser = FirebaseAuth.getInstance(primary).currentUser ?: return false
            val isolatedUser = FirebaseAuth.getInstance(isolatedApp).currentUser ?: return false
            return !mainUser.isAnonymous && !isolatedUser.isAnonymous &&
                mainUser.providerData.any { it.providerId == "google.com" } &&
                isolatedUser.providerData.any { it.providerId == "google.com" } &&
                mainUser.uid == isolatedUser.uid && LocalAccountScope.fromFirebaseUid(mainUser.uid) == account &&
                !AccountDeletionJournal(context.applicationContext).blocksCloud(account) &&
                !FirebaseDevCloudWork.tracker.isBlocked(account)
        }

        if (!sameAccount()) return null
        val client = FirebaseFirestore.getInstance(isolatedApp)
        if (client.app.name != accountMemoryAppName(account)) return null
        client.firestoreSettings = settings(client.firestoreSettings)
        client.takeIf { it.firestoreSettings.cacheSettings is MemoryCacheSettings && sameAccount() }
    }.getOrNull()
}
