package com.haebi.petworld.data.backup

import android.content.Context
import android.util.AtomicFile
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * One process-wide write gate. All profile, care and preferences writes acquire this mutex.
 * Backup snapshot collection and restore also acquire it; do not acquire recursively.
 */
object PetWorldMutationGate {
    val mutex = Mutex()
}

/**
 * Durable pre-restore snapshot. The marker is committed only after a complete, fsynced backup.
 * Keeping the marker until after DB, photos and settings are committed makes restart recovery
 * repeatable if the process is killed midway through restore.
 */
class PetWorldRecoveryJournal(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "petworld_restore_journal")
    private val archive get() = File(directory, "previous.petworld")
    private val marker get() = AtomicFile(File(directory, "pending"))

    suspend fun begin(
        database: PetWorldDatabase,
        preferences: ThemePreferences,
    ) {
        check(!hasPending()) { "이전 복원을 먼저 복구해야 합니다." }
        if (directory.exists()) check(directory.deleteRecursively())
        check(directory.mkdirs()) { "복구 저널 폴더를 만들 수 없습니다." }
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val password = secret.joinToString("") { "%02x".format(it) }.toCharArray()
        try {
            archive.outputStream().use { stream ->
                PetWorldBackupExporter(context, database, preferences)
                    .exportToStream(stream, password)
                stream.fd.sync()
            }
            val output = marker.startWrite()
            try {
                output.write(password.concatToString().toByteArray(Charsets.US_ASCII))
                marker.finishWrite(output)
            } catch (error: Exception) {
                marker.failWrite(output)
                throw error
            }
        } catch (error: Exception) {
            if (!hasPending()) directory.deleteRecursively()
            throw error
        } finally {
            secret.fill(0)
            password.fill('\u0000')
        }
    }

    fun hasPending(): Boolean = File(directory, "pending").exists()

    suspend fun recoverIfPending(database: PetWorldDatabase, preferences: ThemePreferences): Boolean =
        PetWorldMutationGate.mutex.withLock {
            if (!hasPending()) return@withLock false
            check(archive.isFile) { "복구 저널 데이터가 없습니다. 앱 데이터를 수정하지 않습니다." }
            val password = marker.openRead().use {
                String(it.readBytes(), Charsets.US_ASCII).toCharArray()
            }
            try {
                archive.inputStream().use {
                    PetWorldBackupRestorer(context, database, preferences)
                        .restoreFromJournal(it, password)
                }
                complete()
                true
            } finally {
                password.fill('\u0000')
            }
        }

    fun complete() {
        check(directory.deleteRecursively()) { "복구 저널 정리에 실패했습니다." }
    }
}
