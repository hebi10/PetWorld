package com.haebi.petworld.data.account

import java.util.UUID

/**
 * Process-local route fence. An attempted isolated transition reserves the
 * account until process exit, even if it fails. Never fall back to the shared
 * client after a named-client session was selected.
 */
internal class AccountSdkRouteGate {
    internal data class Lease(val account: LocalAccountScope.SignedIn, val generation: String)
    private enum class Phase { RESERVED, ACTIVE, RETIRED }
    private data class Entry(val lease: Lease, val phase: Phase)
    private val routes = mutableMapOf<LocalAccountScope.SignedIn, Entry>()

    @Synchronized
    fun reserve(account: LocalAccountScope.SignedIn): Lease? {
        if (account in routes) return null
        val lease = Lease(account, UUID.randomUUID().toString())
        routes[account] = Entry(lease, Phase.RESERVED)
        return lease
    }

    @Synchronized
    fun activate(lease: Lease): Boolean {
        val entry = routes[lease.account] ?: return false
        if (entry.lease != lease || entry.phase != Phase.RESERVED) return false
        routes[lease.account] = entry.copy(phase = Phase.ACTIVE)
        return true
    }

    @Synchronized
    fun retire(lease: Lease) {
        val entry = routes[lease.account] ?: return
        if (entry.lease == lease) routes[lease.account] = entry.copy(phase = Phase.RETIRED)
    }

    @Synchronized
    fun permits(lease: Lease, target: LocalAccountScope.SignedIn): Boolean =
        lease.account == target && routes[target] == Entry(lease, Phase.ACTIVE)

    @Synchronized
    fun permitsLegacy(target: LocalAccountScope.SignedIn): Boolean = target !in routes
}
