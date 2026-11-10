package com.haebi.petworld.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned, password-protected file envelope for PetWorld backups.
 *
 * This codec encrypts arbitrary payloads; assembling Room data and photos is handled in 1-E-B.
 * Decryption uses an isolated temporary file and never returns unauthenticated plaintext.
 * The caller is responsible for deleting the returned verified temporary file.
 */
class EncryptedBackupCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    companion object {
        private val MAGIC = "PETWORLD".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val ITERATIONS = 310_000
        private const val SALT_LENGTH = 16
        private const val NONCE_LENGTH = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
        private const val HEADER_LENGTH = 8 + 1 + 4 + SALT_LENGTH + NONCE_LENGTH
    }

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.isNotEmpty()) { "백업 암호를 입력해 주세요." }
        val salt = ByteArray(SALT_LENGTH).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_LENGTH).also(secureRandom::nextBytes)
        val header = ByteArrayOutputStream(HEADER_LENGTH).also { buffer ->
            DataOutputStream(buffer).use { data ->
                data.write(MAGIC)
                data.writeByte(VERSION)
                data.writeInt(ITERATIONS)
                data.write(salt)
                data.write(nonce)
            }
        }.toByteArray()

        val cipher = createCipher(Cipher.ENCRYPT_MODE, password, salt, nonce, ITERATIONS)
        cipher.updateAAD(header)
        output.write(header)
        CipherOutputStream(NonClosingOutputStream(output), cipher).use { encrypted ->
            input.copyTo(encrypted)
        }
        output.flush()
    }

    /**
     * Returns a fully authenticated, private temporary file.
     * No plaintext is handed to callers unless the GCM tag has been verified.
     * On failure the temporary file is deleted, leaving existing user data unchanged.
     */
    fun decryptToVerifiedFile(
        input: InputStream,
        temporaryDirectory: File,
        password: CharArray,
    ): File {
        require(password.isNotEmpty()) { "백업 암호를 입력해 주세요." }
        require(temporaryDirectory.isDirectory) { "임시 저장 폴더가 없습니다." }
        val header = ByteArray(HEADER_LENGTH)
        DataInputStream(input).readFully(header)
        val parsed = DataInputStream(ByteArrayInputStream(header))
        val magic = ByteArray(MAGIC.size).also(parsed::readFully)
        require(magic.contentEquals(MAGIC)) { "PetWorld 백업 파일이 아닙니다." }
        require(parsed.readUnsignedByte() == VERSION) { "지원하지 않는 백업 버전입니다." }
        val iterations = parsed.readInt()
        require(iterations == ITERATIONS) { "지원하지 않는 키 생성 설정입니다." }
        val salt = ByteArray(SALT_LENGTH).also(parsed::readFully)
        val nonce = ByteArray(NONCE_LENGTH).also(parsed::readFully)
        val cipher = createCipher(Cipher.DECRYPT_MODE, password, salt, nonce, iterations)
        cipher.updateAAD(header)

        val temporaryFile = File.createTempFile("petworld-verified-", ".tmp", temporaryDirectory)
        try {
            CipherInputStream(input, cipher).use { decrypted ->
                temporaryFile.outputStream().use { destination ->
                    decrypted.copyTo(destination)
                }
            }
            return temporaryFile
        } catch (error: Exception) {
            temporaryFile.delete()
            throw error
        }
    }

    private fun createCipher(
        mode: Int,
        password: CharArray,
        salt: ByteArray,
        nonce: ByteArray,
        iterations: Int,
    ): Cipher {
        val specification = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(specification).encoded
        } finally {
            specification.clearPassword()
        }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(value: Int) = delegate.write(value)
        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            delegate.write(buffer, offset, length)
        override fun flush() = delegate.flush()
        override fun close() = delegate.flush()
    }
}
