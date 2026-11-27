package com.haebi.petworld.data.account

/** Presentation contains no account identifiers, bearer credentials, or status ticket. */
internal sealed interface AccountDeletionStatusView {
    data object Checking : AccountDeletionStatusView
    data object None : AccountDeletionStatusView
    data object Unknown : AccountDeletionStatusView
    data class Saved(val phase: AccountDeletionPhase) : AccountDeletionStatusView
}

/** Local journal only. Saved is the last verified receipt, not a live server response. */
internal fun readDeletionStatusView(
    store: DeletionStateStore, account: LocalAccountScope.SignedIn,
): AccountDeletionStatusView = try {
    val blocked = store.blocksCloud(account)
    val state = store.read(account)
    when {
        !blocked && state == null -> AccountDeletionStatusView.None
        !blocked || state?.account != account -> AccountDeletionStatusView.Unknown
        else -> state.receipt?.let { AccountDeletionStatusView.Saved(it.phase) }
            ?: AccountDeletionStatusView.Unknown
    }
} catch (_: Exception) {
    AccountDeletionStatusView.Unknown
}
