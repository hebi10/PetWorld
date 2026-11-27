package com.haebi.petworld.data.sync

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.PetPhotoStore
import com.haebi.petworld.data.PetRepository
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalPhotoJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = LocalAccountScope.fromFirebaseUid("photo-a") as LocalAccountScope.SignedIn
    private val other = LocalAccountScope.fromFirebaseUid("photo-b") as LocalAccountScope.SignedIn
    private lateinit var db: PetWorldDatabase
    private lateinit var store: PetPhotoStore
    private val name = "pet_11111111-1111-4111-8111-111111111111.jpg"

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java).build()
        store = PetPhotoStore(context, account)
        store.fileFor(name)?.delete()
    }
    @After fun teardown() {
        store.fileFor(name)?.delete()
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

    @Test fun verifiedInstallationNeverOverwritesDifferentBytes() = runBlocking {
        val first = jpeg(android.graphics.Color.RED)
        val second = jpeg(android.graphics.Color.BLUE)
        assertTrue(store.installVerifiedJpeg(name, first, photoSha256(first)))
        assertTrue(store.installVerifiedJpeg(name, first, photoSha256(first)))
        assertFalse(store.installVerifiedJpeg(name, second, photoSha256(second)))
        assertArrayEquals(first, store.fileFor(name)!!.readBytes())
        assertTrue(runCatching { store.installVerifiedJpeg(name, second, "wrong") }.isFailure)
        assertTrue(runCatching { store.installVerifiedJpeg("../escape.jpg", first, photoSha256(first)) }.isFailure)
        assertTrue(runCatching {
            PetPhotoStore(context).installVerifiedJpeg(name, first, photoSha256(first))
        }.isFailure)
    }

    @Test fun signedInDeletionRetainsBytesAndJournal() = runBlocking {
        val bytes = jpeg(android.graphics.Color.RED)
        assertTrue(store.installVerifiedJpeg(name, bytes, photoSha256(bytes)))
        db.petDao().insert(PetEntity("pet-a", "나비", "CAT", "", null, null,
            "UNKNOWN", "UNKNOWN", "", name, false, 1, 1))
        PetRepository(context, db, account).deleteForever("pet-a")
        assertNull(db.petDao().find("pet-a"))
        assertArrayEquals(bytes, store.fileFor(name)!!.readBytes())
        val retained = requireNotNull(db.syncPhotoDao().find(account.key, "pet-a", name))
        assertTrue(retained.retired)
        assertEquals("LOCAL_VERIFIED", retained.status)
        assertEquals(photoSha256(bytes), retained.sha256)
        assertEquals(16, retained.width)
        assertEquals(12, retained.height)
        assertTrue(db.syncPhotoDao().list(other.key, 10).isEmpty())
    }

    @Test fun missingAndLegacyImagesAreRetainedForReviewAndGuestDoesNotQueue() = runBlocking {
        val journal = LocalPhotoJournal(db, account, store)
        db.withTransaction {
            journal.changed("pet-a", null, name)
            journal.changed("pet-a", "pet_legacy.jpg", null)
            LocalPhotoJournal(db, LocalAccountScope.Guest, PetPhotoStore(context))
                .changed("pet-a", null, "pet_guest.jpg")
        }
        assertEquals(2, db.syncPhotoDao().list(account.key, 10).size)
        assertEquals("REVIEW_REQUIRED", db.syncPhotoDao().find(account.key, "pet-a", name)?.status)
        assertTrue(db.syncPhotoDao().find(account.key, "pet-a", "pet_legacy.jpg")!!.retired)
        assertTrue(runCatching { journal.changed("pet-a", null, name) }.isFailure)
    }
}
