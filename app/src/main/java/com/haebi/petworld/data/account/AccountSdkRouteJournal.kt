package com.haebi.petworld.data.account

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files

/**
 * Non-secret, account-scoped route reservation outside Android backup.
 * A marker says only that the legacy route is no longer safe to reopen
 * automatically. It is not evidence of completed migration or deletion.
 */
internal class AccountSdkRouteJournal(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir.canonicalFile, "sdk-route-v1")

    companion object {
        private val lock = Any()
        private val marker = "named-memory-v1\n".toByteArray(Charsets.US_ASCII)
    }

    private fun file(account: LocalAccountScope.SignedIn) = File(directory, account.key + ".route")
    private fun present(target: File): Boolean = listOf(target, File(target.path + ".bak"),
        File(target.path + ".new")).any { it.exists() || Files.isSymbolicLink(it.toPath()) }

    fun permitsLegacy(account: LocalAccountScope.SignedIn): Boolean = synchronized(lock) {
        runCatching {
            check(!Files.isSymbolicLink(directory.toPath()) && (!directory.exists() || directory.isDirectory))
            !present(file(account))
        }.getOrDefault(false)
    }

    /** Keep an existing exact marker; never overwrite corrupt/uncertain state. */
    fun retain(account: LocalAccountScope.SignedIn): Boolean = synchronized(lock) {
        runCatching {
            val target = file(account)
            check(directory.isDirectory || directory.mkdirs())
            check(directory.canonicalFile == directory.absoluteFile && !Files.isSymbolicLink(directory.toPath()) &&
                listOf(target, File(target.path + ".bak"), File(target.path + ".new")).all {
                    it.canonicalFile == it.absoluteFile && !Files.isSymbolicLink(it.toPath())
                })
            if (!present(target)) {
                val atomic = AtomicFile(target)
                val output = atomic.startWrite()
                try {
                    output.write(marker)
                    atomic.finishWrite(output)
                } catch (failure: Exception) {
                    atomic.failWrite(output)
                    throw failure
                }
            }
            AtomicFile(target).openRead().use { input ->
                val actual = ByteArray(marker.size)
                var offset = 0
                while (offset < actual.size) {
                    val read = input.read(actual,offset,actual.size-offset)
                    if (read < 0) break
                    offset += read
                }
                offset == actual.size && actual.contentEquals(marker) && input.read() == -1
            }
        }.getOrDefault(false)
    }
}
