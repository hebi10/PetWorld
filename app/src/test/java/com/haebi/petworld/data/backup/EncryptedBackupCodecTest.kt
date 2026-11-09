package com.haebi.petworld.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class EncryptedBackupCodecTest {
    @get:Rule val folder = TemporaryFolder()
    private val codec = EncryptedBackupCodec()
    private val password = "사용자 백업 암호 123!".toCharArray()

    private fun encrypt(value: ByteArray, secret: CharArray = password): ByteArray =
        ByteArrayOutputStream().also {
            codec.encrypt(ByteArrayInputStream(value), it, secret)
        }.toByteArray()

    private fun decrypt(encrypted: ByteArray, secret: CharArray = password): ByteArray {
        val file = codec.decryptToVerifiedFile(
            ByteArrayInputStream(encrypted), folder.root, secret,
        )
        return try { file.readBytes() } finally { file.delete() }
    }

    private fun assertDecryptFails(encrypted: ByteArray, secret: CharArray = password) {
        val before = folder.root.listFiles()?.toSet().orEmpty()
        try {
            codec.decryptToVerifiedFile(ByteArrayInputStream(encrypted), folder.root, secret)
            fail("변조 또는 잘못된 백업을 복원했습니다.")
        } catch (_: Exception) {
            assertEquals(before, folder.root.listFiles()?.toSet().orEmpty())
        }
    }

    @Test fun roundTripSupportsUnicodeAndBinaryPayload() {
        val data = ("반려동물 기록 🐈 " .repeat(200)).toByteArray() +
            byteArrayOf(0, 1, 2, 127, -1)
        val encoded = encrypt(data)
        assertFalse(encoded.contentEquals(data))
        assertArrayEquals(data, decrypt(encoded))
    }

    @Test fun repeatedEncryptionUsesUniqueSaltAndNonce() {
        val original = "same backup content".toByteArray()
        val first = encrypt(original)
        val second = encrypt(original)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(original, decrypt(first))
        assertArrayEquals(original, decrypt(second))
    }

    @Test fun incorrectPasswordAndCiphertextTamperingAreRejected() {
        val encoded = encrypt("private photo data".toByteArray())
        assertDecryptFails(encoded, "incorrect password".toCharArray())
        val changed = encoded.copyOf().also { it[it.lastIndex - 4] = (it[it.lastIndex - 4].toInt() xor 1).toByte() }
        assertDecryptFails(changed)
        assertDecryptFails(encoded.copyOf(encoded.size - 1))
    }

    @Test fun alteredHeaderAndUnsupportedVersionAreRejected() {
        val encoded = encrypt("data".toByteArray())
        val magic = encoded.copyOf().also { it[0] = 'X'.code.toByte() }
        val version = encoded.copyOf().also { it[8] = 2 }
        val salt = encoded.copyOf().also { it[13] = (it[13].toInt() xor 1).toByte() }
        assertDecryptFails(magic)
        assertDecryptFails(version)
        assertDecryptFails(salt)
        assertDecryptFails(byteArrayOf(1, 2, 3))
    }

    @Test fun emptyPayloadIsAuthenticatedAndPasswordIsRequired() {
        assertArrayEquals(byteArrayOf(), decrypt(encrypt(byteArrayOf())))
        try {
            encrypt("data".toByteArray(), charArrayOf())
            fail("빈 암호가 허용됐습니다.")
        } catch (_: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
