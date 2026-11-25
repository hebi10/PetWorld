package com.haebi.petworld.data.account

import android.content.Context
import com.haebi.petworld.data.ThemePreferences
import com.haebi.petworld.data.backup.PetWorldMutationGate
import com.haebi.petworld.data.backup.PetWorldRecoveryJournal
import com.haebi.petworld.data.room.PetWorldDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class AccountCacheCleanupResult {
    NOT_READY, RETRY_REQUIRED, SCOPED_STORAGE_CLEARED
}

/** A future coordinator must prove old ViewModels, Room readers, image work,
 * sync and import jobs are drained. No UI/production caller is installed yet.
 */
internal fun interface AccountStorageQuiescence {
    fun isQuiescent(account: LocalAccountScope.SignedIn): Boolean
}

internal data class AccountCacheCleanupApproval(
    val account: LocalAccountScope.SignedIn,
    val jobId: String,
    val explicitlyConfirmed: Boolean,
)

/** Only app-owned, account-scoped stores. This does not claim forensic erasure
 * or remove shared SDK caches, external backups, or the retained deletion journal.
 */
internal class AccountDeletionLocalCleanup(
    context: Context,
    private val store: DeletionStateStore,
    private val quiescence: AccountStorageQuiescence,
) {
    private val app = context.applicationContext

    suspend fun clear(approval: AccountCacheCleanupApproval): AccountCacheCleanupResult =
        withContext(Dispatchers.IO) {
            PetWorldMutationGate.mutex.withLock {
                try {
                    val account = approval.account
                    fun eligible(): Boolean {
                        val state = store.read(account) ?: return false
                        return approval.explicitlyConfirmed &&
                            state.account == account &&
                            state.jobId == approval.jobId &&
                            state.receipt?.phase == AccountDeletionPhase.COMPLETE &&
                            store.blocksCloud(account) &&
                            LocalAccountScopeResolver.resolve(app) == LocalAccountScope.Guest &&
                            !PetWorldRecoveryJournal(app).hasPending() &&
                            quiescence.isQuiescent(account)
                    }
                    if (!eligible()) return@withLock AccountCacheCleanupResult.NOT_READY
                    // The exact target must already be detached. No active DB is closed here.
                    if (!PetWorldDatabase.reserveClosedAccountForCleanup(account))
                        return@withLock AccountCacheCleanupResult.NOT_READY
                    // Android may alias /data/user/0 and /data/data. Resolve only the
                    // trusted application root first; reject aliases below that root.
                    val dbParent = requireNotNull(app.getDatabasePath(account.databaseFileName).parentFile).canonicalFile
                    val database = File(dbParent, account.databaseFileName)
                    val dbFiles = listOf("", "-wal", "-shm", "-journal").map { File(database.path + it) }
                    val photoRoot = File(app.filesDir.canonicalFile, "pet_photos_accounts")
                    val photos = File(photoRoot, account.key)
                    // Refuse unexpected aliases or symbolic links before making any destructive change.
                    check(photoRoot.canonicalFile == photoRoot.absoluteFile && !Files.isSymbolicLink(photoRoot.toPath()))
                    check(photos.canonicalFile == photos.absoluteFile && !Files.isSymbolicLink(photos.toPath()))
                    dbFiles.forEach { check(it.canonicalFile == it.absoluteFile && !Files.isSymbolicLink(it.toPath())) }
                    validatePhotoTree(photos)
                    check(eligible())
                    if (dbFiles.any { it.exists() }) app.deleteDatabase(account.databaseFileName)
                    check(dbFiles.none { it.exists() || Files.isSymbolicLink(it.toPath()) })
                    check(eligible())
                    deletePhotoTree(photos)
                    check(!photos.exists() && !Files.isSymbolicLink(photos.toPath()))
                    check(eligible())
                    val preferences = ThemePreferences(app, account)
                    preferences.clearAccountSelectionUnderGate()
                    check(preferences.selectedPetId.first() == null)
                    val checkpoint = app.getSharedPreferences("petworld_fb04_import", Context.MODE_PRIVATE)
                    check(checkpoint.edit().remove(account.key).commit())
                    check(!checkpoint.contains(account.key))
                    check(eligible())
                    AccountCacheCleanupResult.SCOPED_STORAGE_CLEARED
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A partial deletion must remain retryable and never report success.
                    AccountCacheCleanupResult.RETRY_REQUIRED
                }
            }
        }

    private fun validatePhotoTree(file: File) {
        check(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile)
        if (!file.exists()) return
        if (file.isDirectory) {
            val children = file.listFiles() ?: error("Photo directory unavailable")
            children.forEach(::validatePhotoTree)
        } else check(file.isFile)
    }

    private fun deletePhotoTree(file: File) {
        check(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile)
        if (!file.exists()) return
        if (file.isDirectory) (file.listFiles() ?: error("Photo directory unavailable")).forEach(::deletePhotoTree)
        check(file.delete())
    }
}
