package com.haebi.petworld.data.account

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** An authenticated, no-backup pointer, not a second copy of the status ticket. */
internal class DeviceDeletionAnchorStore(context: Context) : DeletionRecoveryAnchorStore {
    private val app = context.applicationContext
    private val target = File(app.noBackupFilesDir, "deletion-recovery-anchor-v1.bin")
    private val aad = "petworld-device-recovery-anchor-v1".toByteArray(Charsets.UTF_8)

    companion object {
        private val lock = Any()
        private const val ALIAS = "petworld-device-recovery-anchor-v1"
    }

    private fun secureDevice() {
        val keyguard = requireNotNull(app.getSystemService(KeyguardManager::class.java))
        check(keyguard.isDeviceSecure && !keyguard.isDeviceLocked)
    }

    private fun present(): Boolean = target.exists() ||
        File(target.path + ".bak").exists() || File(target.path + ".new").exists()

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Recovery anchor key unavailable" }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(30, KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(30)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(spec.build())
        }.generateKey()
    }

    override fun read(): DeletionRecoveryAnchor? = synchronized(lock) {
        secureDevice()
        if (!present()) return@synchronized null
        // 128 ASCII bytes (account key and job ID), 12-byte IV, 16-byte GCM tag.
        val blob = AtomicFile(target).openRead().use { input ->
            val bytes = ByteArray(157)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                count += n
            }
            require(count == bytes.size && input.read() == -1)
            bytes
        }
        try {
            require(blob[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, blob.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(blob, 13, blob.size - 13)
            try {
                require(plain.size == 128)
                val account = LocalAccountScope.SignedIn(String(plain, 0, 64, Charsets.US_ASCII))
                DeletionRecoveryAnchor(account, String(plain, 64, 64, Charsets.US_ASCII))
            } finally { plain.fill(0) }
        } finally { blob.fill(0) }
    }

    override fun retain(anchor: DeletionRecoveryAnchor): Boolean = synchronized(lock) {
        secureDevice()
        val existing = read()
        if (existing != null) return@synchronized existing == anchor
        check(!present())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad)
        require(cipher.iv.size == 12)
        val plain = (anchor.account.key + anchor.jobId).toByteArray(Charsets.US_ASCII)
        val sealed = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) }
        finally { plain.fill(0) }
        try {
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try {
                stream.write(sealed)
                atomic.finishWrite(stream)
            } catch (failure: Exception) {
                atomic.failWrite(stream)
                throw failure
            }
            read() == anchor
        } finally { sealed.fill(0) }
    }
}
