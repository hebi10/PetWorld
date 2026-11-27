package com.haebi.petworld.data.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.migration.FirstImportPayloads
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncPhotoTransferTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = LocalAccountScope.fromFirebaseUid("transfer-a") as LocalAccountScope.SignedIn
    private val other = LocalAccountScope.fromFirebaseUid("transfer-b") as LocalAccountScope.SignedIn
    private val key = SyncPhotoKey("pet-a", "pet_11111111-1111-4111-8111-111111111111.jpg")
    private val previousName = "pet_22222222-2222-4222-8222-222222222222.jpg"
    private val store = PetPhotoStore(context, account)
    private val remote = MemoryStorage()
    private lateinit var db: PetWorldDatabase
    private lateinit var transfer: SyncPhotoTransfer
    private var allowed = true
    private val permission = SyncPermission { allowed && it == account }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java).build()
        transfer = SyncPhotoTransfer(context, db, account, remote, permission)
        store.fileFor(key.fileName)?.delete()
        store.fileFor(previousName)?.delete()
    }

    @After fun teardown() {
        store.fileFor(key.fileName)?.delete()
        store.fileFor(previousName)?.delete()
        db.close()
    }

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it))
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private fun pet(name: String?) =
        PetEntity(key.petId, "나비", "CAT", "", null, null, "UNKNOWN", "UNKNOWN", "", name, false, 1, 1)

    private suspend fun queueLocalPhoto(bytes: ByteArray) {
        assertTrue(store.installVerifiedJpeg(key.fileName, bytes, photoSha256(bytes)))
        val pet = pet(key.fileName)
        db.withTransaction {
            db.petDao().insert(pet)
            LocalPhotoJournal(db, account, store).changed(pet.id, null, pet.photoFile)
            LocalSyncOutbox(db, account).petChanged(null, pet)
        }
    }

    @Test fun uploadRecoversLostResponseBeforePublishingThePhotoReference() = runBlocking {
        val bytes = jpeg(Color.RED)
        queueLocalPhoto(bytes)
        remote.failAfterCreate = true
        val preparer = SyncBaselinePreparer(db, account, SyncDocumentSource { _, _, _, _ -> null },
            permission, transfer)
        var remoteDoc: Map<String, Any?>? = null
        var writes = 0
        val receipts = mutableMapOf<String, Map<String, Any?>>()
        val sender = SyncSender(db, SyncTransport { command ->
            val proof = requireNotNull(command.photoProof)
            assertEquals(key.fileName, proof.fileName)
            assertEquals(photoSha256(bytes), proof.evidence.sha256)
            assertArrayEquals(bytes, remote.bytes)
            when (val decision = decideSyncCommit(command, receipts[command.opId], remoteDoc)) {
                is SyncRemoteDecision.Write -> {
                    remoteDoc = decision.document
                    receipts[command.opId] = decision.receipt
                    writes++
                    SyncCommitOutcome.Retry // server committed but response was lost
                }
                is SyncRemoteDecision.Stop -> decision.result
            }
        }, permission, transfer)

        assertEquals(SyncDispatch.Retry(30_000), preparer.next(sender))
        assertEquals(SyncDispatch.Acknowledged, preparer.next(sender))
        assertEquals(1, writes)
        assertEquals(key.fileName, remoteDoc?.get("photoFile"))
        assertEquals(1, remote.creates)
        assertEquals(1, remote.downloads)
        assertArrayEquals(bytes, remote.bytes)
        assertEquals("REMOTE_VERIFIED", db.syncPhotoDao().find(account.key, key.petId, key.fileName)?.status)
        assertNull(db.syncOutboxDao().firstOutstanding(account.key))
        assertEquals("ACKNOWLEDGED", db.syncOutboxDao().list(account.key, 10).single().status)
    }

    @Test fun uploadNeverReplacesExistingCloudBytesOrMarksDifferentContentVerified() = runBlocking {
        val local = jpeg(Color.RED)
        val existing = jpeg(Color.BLUE)
        queueLocalPhoto(local)
        remote.seed(existing)
        val pending = requireNotNull(db.syncOutboxDao().firstOutstanding(account.key))
        assertTrue(runCatching { transfer.upload(pending, key.fileName) }.exceptionOrNull() is SyncPhotoRejected)
        assertEquals(0, remote.creates)
        assertArrayEquals(existing, remote.bytes)
        assertArrayEquals(local, store.fileFor(key.fileName)!!.readBytes())
        assertEquals("LOCAL_VERIFIED", db.syncPhotoDao().find(account.key, key.petId, key.fileName)?.status)
    }

    @Test fun downloadRetriesPageAndCachesBytesWhileKeepingPreviousRoomReference() = runBlocking {
        val previous = jpeg(Color.BLUE)
        assertTrue(store.installVerifiedJpeg(previousName, previous, photoSha256(previous)))
        db.petDao().insert(pet(previousName))
        val bytes = jpeg(Color.RED)
        remote.seed(bytes)
        remote.beforeDownload = { throw IOException("offline") }
        val doc = RemoteSyncDocument("PET", key.petId, key.petId, FirstImportPayloads.pet(pet(key.fileName)))
        val page = RemoteSyncPage(account.key, null, listOf(doc), key.petId, false)
        val receiver = SyncReceiver(db, account, permission, transfer)
        var advanced = false

        assertTrue(runCatching { receiver.applyPage(page) { advanced = true } }.exceptionOrNull() is IOException)
        assertFalse(advanced)
        assertFalse(store.fileFor(key.fileName)!!.exists())

        remote.beforeDownload = {}
        assertEquals(listOf(InboundResult.CONFLICT), receiver.applyPage(page) { advanced = true })
        assertTrue(advanced)
        assertEquals(listOf(InboundResult.DUPLICATE), receiver.applyPage(page))
        assertEquals(2, remote.downloads)
        assertArrayEquals(bytes, store.fileFor(key.fileName)!!.readBytes())
        assertArrayEquals(previous, store.fileFor(previousName)!!.readBytes())
        assertEquals(previousName, db.petDao().find(key.petId)?.photoFile)
        assertFalse(PetPhotoStore(context, other).fileFor(key.fileName)!!.exists())
    }

    @Test fun verifiedRemotePhotoAppliesOnlyWithMatchingBaselineAndRetainsOldFile() = runBlocking {
        val initial = RemoteSyncDocument("PET", key.petId, key.petId, FirstImportPayloads.pet(pet(null)))
        val receiver = SyncReceiver(db, account, permission, transfer)
        fun page(doc: RemoteSyncDocument) = RemoteSyncPage(account.key, null, listOf(doc), key.petId, false)
        assertEquals(listOf(InboundResult.APPLIED), receiver.applyPage(page(initial)))
        val bytes = jpeg(Color.RED)
        remote.seed(bytes)
        val updated = initial.copy(fields = FirstImportPayloads.pet(pet(key.fileName)) + ("revision" to 1L))
        assertEquals(listOf(InboundResult.APPLIED), receiver.applyPage(page(updated)))
        assertEquals(key.fileName, db.petDao().find(key.petId)?.photoFile)
        assertArrayEquals(bytes, store.fileFor(key.fileName)!!.readBytes())
        val removed = initial.copy(fields = initial.fields + ("revision" to 2L))
        assertEquals(listOf(InboundResult.APPLIED), receiver.applyPage(page(removed)))
        assertNull(db.petDao().find(key.petId)?.photoFile)
        assertArrayEquals(bytes, store.fileFor(key.fileName)!!.readBytes())
        assertTrue(db.syncPhotoDao().find(account.key,key.petId,key.fileName)!!.retired)
        assertTrue(db.syncOutboxDao().list(account.key,10).isEmpty())
    }

    @Test fun manifestMismatchAndUnverifiedPhotoNeverPublishOrOverwrite() = runBlocking {
        val bytes = jpeg(Color.RED)
        queueLocalPhoto(bytes)
        remote.seed(jpeg(Color.BLUE))
        val preparer = SyncBaselinePreparer(db, account, SyncDocumentSource { _, _, _, _ -> null },
            permission, transfer)
        val sender = SyncSender(db, SyncTransport { error("Unverified photo must never send") }, permission, transfer)
        assertEquals(SyncDispatch.WaitingPhoto, preparer.next(sender))
        assertEquals(SYNC_WAITING_BASELINE, db.syncOutboxDao().firstOutstanding(account.key)?.status)
        remote.seed(bytes)
        remote.info = remote.info!!.copy(manifestEvidence = cloudJpegEvidence(bytes).copy(width=17))
        assertEquals(SyncDispatch.WaitingPhoto, preparer.next(sender))
        assertEquals(SYNC_WAITING_BASELINE, db.syncOutboxDao().firstOutstanding(account.key)?.status)
    }

    @Test fun checksumMismatchAndRevokedLeaseNeverInstallAFile() = runBlocking {
        val bytes = jpeg(Color.RED)
        remote.seed(bytes)
        remote.info = remote.info!!.copy(sha256 = "0".repeat(64))
        assertTrue(runCatching { transfer.download(key) }.exceptionOrNull() is SyncPhotoRejected)
        assertFalse(store.fileFor(key.fileName)!!.exists())
        assertNull(db.syncPhotoDao().find(account.key, key.petId, key.fileName))

        remote.seed(bytes)
        remote.beforeDownload = { allowed = false }
        assertTrue(runCatching { transfer.download(key) }.exceptionOrNull() is SyncReceiveDenied)
        assertFalse(store.fileFor(key.fileName)!!.exists())
        assertNull(db.syncPhotoDao().find(account.key, key.petId, key.fileName))
    }

    @Test fun legacyStorageChecksumIsRequiredBeforeRecordingSha256() = runBlocking {
        val bytes = jpeg(Color.RED)
        remote.seed(bytes, legacy = true)
        val legacy = requireNotNull(remote.info)
        remote.info = legacy.copy(md5 = null)
        assertTrue(runCatching { transfer.download(key) }.exceptionOrNull() is SyncPhotoRejected)
        assertEquals(0, remote.downloads)
        remote.info = legacy

        transfer.download(key)
        assertArrayEquals(bytes, store.fileFor(key.fileName)!!.readBytes())
        assertEquals(photoSha256(bytes), db.syncPhotoDao().find(account.key, key.petId, key.fileName)?.sha256)
    }

    private class MemoryStorage : SyncPhotoStorage {
        var info: RemotePhotoMetadata? = null
        var bytes: ByteArray? = null
        var failAfterCreate = false
        var beforeDownload: () -> Unit = {}
        var creates = 0
        var downloads = 0

        fun seed(data: ByteArray, legacy: Boolean = false) {
            bytes = data.copyOf()
            info = RemotePhotoMetadata(
                data.size.toLong(), CLOUD_PHOTO_MIME, photoSha256(data).takeUnless { legacy },
                Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(data)),
            )
        }

        override suspend fun metadata(key: SyncPhotoKey) = info

        override suspend fun create(key: SyncPhotoKey, photo: VerifiedPhoto) {
            check(bytes == null)
            creates++
            seed(photo.bytes)
            if (failAfterCreate) throw IOException("upload acknowledged late")
        }

        override suspend fun download(key: SyncPhotoKey): ByteArray {
            downloads++
            beforeDownload()
            return requireNotNull(bytes).copyOf()
        }
    }
}
