package com.haebi.petworld.data.account

import android.content.Context

object LocalAccountScopeResolver {
    @Volatile
    private var firebaseScopeResolver: ((Context) -> LocalAccountScope)? = null

    fun installFirebaseDev(resolver: (Context) -> LocalAccountScope) {
        firebaseScopeResolver = resolver
    }

    fun resolve(context: Context): LocalAccountScope {
        if (context.packageName != "com.haebi.petworld.dev") {
            return LocalAccountScope.Guest
        }
        val resolver = firebaseScopeResolver
            ?: error("The development account scope provider is unavailable.")
        return resolver(context)
    }
}
