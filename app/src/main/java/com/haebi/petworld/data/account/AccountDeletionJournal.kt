package com.haebi.petworld.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-account encrypted request journal, excluded from Android backup.
 * A corrupt/unreadable journal or missing key blocks cloud use; never recreate its ticket.
 * The marker survives server completion until separately verified local cleanup/retention.
 * Only the not-yet-connected deletion controller writes this journal.
 */
internal class AccountDeletionJournal(context: Context) : DeletionStateStore {
    private val app = context.applicationContext
    private val directory = File(app.noBackupFilesDir, "account-deletion-v1")

    companion object {
        private val lock = Any()
        private const val ALIAS = "petworld-deletion-journal-v1"
    }

    private fun file(account: LocalAccountScope.SignedIn) =
        File(directory, account.key + ".bin")

    private fun present(file: File) = file.exists() ||
        File(file.path + ".bak").exists() || File(file.path + ".new").exists()

    override fun blocksCloud(account: LocalAccountScope.SignedIn): Boolean = synchronized(lock) {
        runCatching { present(file(account)) }.getOrDefault(true)
    }

    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Deletion journal key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }

    private fun aad(account: LocalAccountScope.SignedIn) =
        ("petworld-deletion-journal-v1:" + account.key).toByteArray(Charsets.UTF_8)

    override fun read(account: LocalAccountScope.SignedIn): DeletionRequestState? = synchronized(lock) {
        val target = file(account)
        if (!present(target)) return@synchronized null
        val blob = AtomicFile(target).openRead().use { input ->
            val buffer = ByteArray(4097)
            var count = 0
            while (count < buffer.size) {
                val n = input.read(buffer, count, buffer.size - count)
                if (n < 0) break
                count += n
            }
            require(count in 30..4096 && input.read() == -1)
            buffer.copyOf(count)
        }
        require(blob[0] == 1.toByte() && blob[1] == 12.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, blob.copyOfRange(2,14)))
        cipher.updateAAD(aad(account))
        val plain = cipher.doFinal(blob,14,blob.size-14)
        try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            require(json.getString("account") == account.key)
            DeletionRequestState.restore(account,json.getString("job"),json.getString("ticket"),
                if (json.isNull("phase")) null else json.getString("phase"))
        } finally { plain.fill(0) }
    }

    override fun write(state: DeletionRequestState) = synchronized(lock) {
        val target = file(state.account)
        val old = read(state.account)
        if (old != null) {
            require(old.jobId == state.jobId && old.statusTicket == state.statusTicket)
            require(old.receipt == null || (state.receipt != null && old.receipt.accept(state.receipt) != null))
        }
        check(directory.isDirectory || directory.mkdirs())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // A missing key must not silently replace one for another retained account.
        val retained = directory.listFiles()?.any { it.isFile } ?: error("Deletion journal directory unavailable")
        cipher.init(Cipher.ENCRYPT_MODE, key(!retained))
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad(state.account))
        val plain = JSONObject().put("account",state.account.key).put("job",state.jobId)
            .put("ticket",state.statusTicket).put("phase",state.receipt?.phase?.name ?: JSONObject.NULL)
            .toString().toByteArray(Charsets.UTF_8)
        val sealed = try { byteArrayOf(1,12) + cipher.iv + cipher.doFinal(plain) }
            finally { plain.fill(0) }
        require(sealed.size <= 4096)
        val atomic = AtomicFile(target)
        val out = atomic.startWrite()
        try {
            out.write(sealed)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        } finally { sealed.fill(0) }
        val saved = requireNotNull(read(state.account))
        check(saved.jobId == state.jobId && saved.statusTicket == state.statusTicket && saved.receipt == state.receipt)
    }
}
