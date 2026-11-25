package com.haebi.petworld.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.haebi.petworld.data.account.DeletionWire
import com.haebi.petworld.data.account.DeletionWireRequest
import com.haebi.petworld.data.account.DeletionWireResponse
import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Standard platform HTTPS transport. No alternate trust manager, hostname override,
 * redirect, cookies, URL credentials, error-body logging or cleartext fallback.
 * This is not instantiated by the current UI. The reviewed endpoint and real
 * App Check provider must be connected by a separate verified dev bootstrap.
 */
internal class FirebaseDevDeletionHttpsWire(context: Context) : DeletionWire {
    private val app = context.applicationContext

    override suspend fun post(request: DeletionWireRequest): DeletionWireResponse = withContext(Dispatchers.IO) {
        check(app.packageName == "com.haebi.petworld.dev" &&
            FirebaseApp.getInstance().options.projectId == "petworld-dev")
        check(request.url.scheme == "https" && request.url.path == request.route.path &&
            request.url.userInfo == null && request.url.query == null && request.url.fragment == null)
        val body = request.body.toByteArray(Charsets.UTF_8)
        check(body.size in 1..2048)
        val connection = request.url.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", request.authorization)
            connection.setRequestProperty("X-Firebase-AppCheck", request.appCheckToken)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            // Never read, parse or expose a redirect or an error payload.
            if (code != request.route.responseCode) error("Deletion result unknown")
            check(connection.contentLengthLong <= 2048)
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(256)
                val chunk = ByteArray(512)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    check(output.size() + n <= 2048) { "Deletion response too large" }
                    output.write(chunk, 0, n)
                }
                output.toByteArray()
            }
            val result = try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } finally { bytes.fill(0) }
            DeletionWireResponse(code, connection.contentType, connection.getHeaderField("Cache-Control"), result)
        } finally {
            body.fill(0)
            connection.disconnect()
        }
    }
}
