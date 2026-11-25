package com.haebi.petworld.auth

import com.haebi.petworld.data.account.SharedSdkCacheDisposition

/**
 * All current FirebaseDev import/read/sync/photo adapters use the default
 * FirebaseApp's Firestore instance. Source.SERVER avoids serving cached
 * snapshots but does not prove that persisted data and pending SDK work are
 * gone. clearPersistence would also affect unrelated accounts.
 *
 * Keep the request path disabled until a reviewed account-isolated SDK/cache
 * lifecycle and actual device QA replace this unverified disposition.
 */
internal object FirebaseDevDeletionCachePolicy {
    fun disposition(): SharedSdkCacheDisposition = SharedSdkCacheDisposition.UNVERIFIED_SHARED
    fun permitsRequest(): Boolean = disposition() != SharedSdkCacheDisposition.UNVERIFIED_SHARED
}
