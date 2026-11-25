package com.haebi.petworld.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.room.PetEntity
import com.haebi.petworld.data.room.PetWorldDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncPhotoRetentionTest {
    @Test fun activeAndHistoricalReferencesPreventCleanupAndRemoteAuditRemainsMandatory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PetWorldDatabase::class.java).build()
        val account = LocalAccountScope.fromFirebaseUid("retention-a") as LocalAccountScope.SignedIn
        val other = LocalAccountScope.fromFirebaseUid("retention-b") as LocalAccountScope.SignedIn
        fun name(n: Int) = "pet_" + n.toString().repeat(8) + "-1111-4111-8111-111111111111.jpg"
        fun pet(id: String, file: String?) = PetEntity(id,"나비","CAT","",null,null,"UNKNOWN","UNKNOWN","",
            file,false,1,1)
        fun entry(n: Int, key: String = account.key) = SyncPhotoEntry(key,"pet-a",name(n),
            "a".repeat(64), 100L,16,12,"REMOTE_VERIFIED",true)
        try {
            db.withTransaction {
                (1..4).forEach { db.syncPhotoDao().save(entry(it)) }
                db.syncPhotoDao().save(entry(5,other.key))
                db.syncPhotoDao().save(entry(4).copy(status="REVIEW_REQUIRED"))
                db.petDao().insert(pet("other-pet",name(1)))
                LocalSyncOutbox(db, account).petChanged(pet("retired-pet",name(2)),null)
            }
            val reviewer = SyncPhotoRetention(db,account,SyncPermission { it == account })
            val reasons = reviewer.review().associate { it.fileName to it.reason }
            assertEquals(PhotoRetentionReason.ACTIVE_REFERENCE,reasons[name(1)])
            assertEquals(PhotoRetentionReason.RETAINED_HISTORY,reasons[name(2)])
            assertEquals(PhotoRetentionReason.WAIT_TRUSTED_REMOTE_AUDIT,reasons[name(3)])
            assertEquals(PhotoRetentionReason.UNVERIFIED,reasons[name(4)])
            assertFalse(reasons.containsKey(name(5)))
            assertTrue(runCatching { SyncPhotoRetention(db,account).review() }.isFailure)
            assertEquals(4,db.syncPhotoDao().list(account.key,20).size)
        } finally { db.close() }
    }
}
