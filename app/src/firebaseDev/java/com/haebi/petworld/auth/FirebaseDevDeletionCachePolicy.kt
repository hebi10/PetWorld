package com.haebi.petworld.auth

import com.haebi.petworld.data.account.SharedSdkCacheDisposition

/**
 * Default FirebaseDev import/read/sync/photo adapters still use shared persistence
 * unless a reviewed named SDK session is explicitly opened. Source.SERVER avoids serving cached
 * snapshots but does not prove that persisted data and pending SDK work are
 * gone. clearPersistence would also affect unrelated accounts.
 *
 * An isolated memory session alone does not prove legacy cache absence or safe
 * removal. Keep the deletion path disabled until the installation audit and
 * complete account lifecycle have been verified on real devices.
 */
internal object FirebaseDevDeletionCachePolicy {
    fun disposition(): SharedSdkCacheDisposition = SharedSdkCacheDisposition.UNVERIFIED_SHARED
    fun permitsRequest(): Boolean = disposition() != SharedSdkCacheDisposition.UNVERIFIED_SHARED
}
