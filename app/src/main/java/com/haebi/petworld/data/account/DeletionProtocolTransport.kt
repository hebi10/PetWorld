package com.haebi.petworld.data.account

import java.net.URI
import java.util.Base64
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Origin comes from a separately reviewed build/runtime bootstrap, never a user,
 * intent, remote document or URL argument. No origin is configured in this commit.
 */
internal class DeletionEndpoint private constructor(private val origin: URI) {
    fun url(route: DeletionRoute): URI =
        URI("https", null, origin.host, origin.port, route.path, null, null)

    companion object {
        fun fromVerifiedOrigin(value: String, verified: Boolean): DeletionEndpoint? {
            if (!verified) return null
            return runCatching {
                val uri = URI(value)
                val host = requireNotNull(uri.host)
                require(uri.scheme == "https" && uri.rawUserInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null &&
                    (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                    uri.port in listOf(-1, 443))
                // Require a DNS origin, not localhost, raw IP or a custom port.
                require(host.matches(Regex("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")) &&
                    host.any { it.isLetter() } &&
                    !host.equals("localhost", ignoreCase = true) &&
                    !host.endsWith(".localhost", ignoreCase = true) &&
                    !host.endsWith(".local", ignoreCase = true))
                DeletionEndpoint(uri)
            }.getOrNull()
        }
    }
}

internal enum class DeletionRoute(val path: String, val responseCode: Int) {
    REQUEST("/account-deletion/request", 202),
    STATUS("/account-deletion/status", 200),
}

/** Secret-bearing objects deliberately have redacted diagnostic strings. */
internal class DeletionWireRequest(
    val route: DeletionRoute,
    val url: URI,
    val authorization: String,
    val appCheckToken: String,
    val body: String,
) {
    override fun toString() = "DeletionWireRequest(redacted)"
}
internal class DeletionWireResponse(
    val statusCode: Int,
    val contentType: String?,
    val cacheControl: String?,
    val body: String,
) {
    override fun toString() = "DeletionWireResponse(redacted)"
}
internal fun interface DeletionWire {
    suspend fun post(request: DeletionWireRequest): DeletionWireResponse
}
internal fun interface DeletionAppCheck {
    suspend fun token(): String?
}

/** Only fixed request/status routes and exact receipt responses are accepted. */
internal class DeletionProtocolTransport(
    private val endpoint: DeletionEndpoint,
    private val appCheck: DeletionAppCheck,
    private val wire: DeletionWire,
) : DeletionRequestTransport {
    override suspend fun request(freshIdToken: String, statusTicket: String): AccountDeletionReceipt {
        require(validTicket(statusTicket))
        return exchange(DeletionRoute.REQUEST, freshIdToken, JSONObject()
            .put("confirmation", "DELETE_MY_PETWORLD_ACCOUNT").put("statusTicket", statusTicket).toString(), null)
    }

    override suspend fun status(jobId: String, statusTicket: String): AccountDeletionReceipt {
        require(jobId.matches(Regex("[0-9a-f]{64}")) && validTicket(statusTicket))
        return exchange(DeletionRoute.STATUS, statusTicket, JSONObject().put("jobId", jobId).toString(), jobId)
    }

    private suspend fun exchange(route: DeletionRoute, bearer: String, body: String,
        expectedJob: String?): AccountDeletionReceipt {
        require(validHeaderToken(bearer))
        val appToken = appCheck.token()
        check(appToken != null && validHeaderToken(appToken)) { "Deletion admission unavailable" }
        val response = wire.post(DeletionWireRequest(route, endpoint.url(route), "Bearer $bearer", appToken, body))
        check(response.statusCode == route.responseCode) { "Deletion result unknown" }
        check(response.contentType?.matches(Regex("application/json(?:\\s*;\\s*charset=utf-8)?", RegexOption.IGNORE_CASE)) == true)
        check(response.cacheControl?.split(',')?.any { it.trim().equals("no-store", ignoreCase = true) } == true)
        check(response.body.toByteArray(Charsets.UTF_8).size in 1..2048)
        // Do not propagate raw parse exceptions or server error payloads into UI/logs.
        val receipt = runCatching {
            val tokenizer = JSONTokener(response.body)
            val json = tokenizer.nextValue() as? JSONObject ?: error("Invalid receipt")
            check(tokenizer.nextClean() == '\u0000')
            val keys = json.keys().asSequence().toSet()
            check(keys == setOf("jobId", "phase"))
            val job = json.get("jobId") as? String ?: error("Invalid receipt")
            val phase = json.get("phase") as? String ?: error("Invalid receipt")
            check(expectedJob == null || expectedJob == job)
            requireNotNull(AccountDeletionReceipt.fromServer(job, phase))
        }.getOrNull()
        return checkNotNull(receipt) { "Deletion result unknown" }
    }

    private fun validHeaderToken(value: String) =
        value.length in 1..8185 && value.matches(Regex("[A-Za-z0-9._-]+"))

    private fun validTicket(value: String): Boolean {
        if (!value.matches(Regex("[A-Za-z0-9_-]{43}"))) return false
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(value)
            try { bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value }
            finally { bytes.fill(0) }
        }.getOrDefault(false)
    }
}
