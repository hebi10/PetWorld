package com.haebi.petworld.data.room

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncOutboxMigrationTest {
    @Test fun versionTwoRowsSurviveAdditiveMigration() = runBlocking {
        verifyMigration(2)
    }

    @Test fun versionThreeOutboxSurvivesAdditiveMigration() = runBlocking {
        verifyMigration(3)
    }

    @Test fun versionFourInboundSurvivesAdditiveMigration() = runBlocking {
        verifyMigration(4)
    }

    @Test fun versionFiveScanSurvivesAdditivePhotoMigration() = runBlocking {
        verifyMigration(5)
    }

    private suspend fun verifyMigration(version: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "petworld_outbox_migration_test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE pets (
                            id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, species TEXT NOT NULL,
                            breed TEXT NOT NULL DEFAULT '', birthday TEXT, estimated_age_years INTEGER,
                            sex TEXT NOT NULL, neuter_status TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '',
                            photo_file TEXT, is_archived INTEGER NOT NULL DEFAULT 0,
                            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
                        db.execSQL("CREATE INDEX idx_pets_archived_updated ON pets(is_archived, updated_at DESC)")
                        MIGRATION_1_2.migrate(db)
                        db.execSQL("INSERT INTO pets (id,name,species,sex,neuter_status,created_at,updated_at) VALUES ('pet','나비','CAT','UNKNOWN','UNKNOWN',1,1)")
                        db.execSQL("INSERT INTO care_entries (id,pet_id,type,occurred_at,timezone_id,water_ml,note,created_at,updated_at) VALUES ('record','pet','WATER',1700000000000,'Asia/Seoul',0,'',1,1)")
                        if (version >= 3) {
                            MIGRATION_2_3.migrate(db)
                            db.execSQL("INSERT INTO sync_outbox (op_id,account_key,entity_kind,pet_id,entity_id,operation,before_json,after_json,created_at,status,attempt) VALUES ('saved-op','qa','CARE','pet','record','CREATE',NULL,'{}',1,'WAITING_BASELINE',0)")
                        }
                        if (version >= 4) {
                            MIGRATION_3_4.migrate(db)
                            db.execSQL("INSERT INTO sync_inbound (account_key,entity_kind,pet_id,entity_id,revision,payload_digest,remote_json,local_json,tombstone,status) VALUES ('qa','CARE','pet','record',1,'digest','{}',NULL,0,'CONFLICT')")
                        }
                        if (version == 5) {
                            MIGRATION_4_5.migrate(db)
                            db.execSQL("INSERT INTO sync_scan (account_key,round,pet_cursor,pending_parents_json,record_cursor,root_exhausted) VALUES ('qa',2,'pet','[]',NULL,1)")
                        }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("Unexpected fixture upgrade")
                    }
                }).build()
        )
        try {
            helper.writableDatabase
        } finally {
            helper.close()
        }
        val db = Room.databaseBuilder(context, PetWorldDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
        try {
            assertEquals("나비", db.petDao().find("pet")?.name)
            val care = requireNotNull(db.careDao().find("record"))
            assertEquals(0L, care.waterMl)
            assertNull(care.foodServedMilli)
            assertEquals("Asia/Seoul", care.timezoneId)
            assertEquals(emptyList<com.haebi.petworld.data.sync.SyncOutboxEntry>(),
                db.syncOutboxDao().list("unused", 50))
            assertEquals(if (version >= 3) listOf("saved-op") else emptyList<String>(),
                db.syncOutboxDao().list("qa", 50).map { it.opId })
            assertEquals(if (version >= 4) 1 else 0, db.syncInboundDao().observeReviewCount("qa").first())
            if (version == 5) assertEquals(2L, db.syncScanDao().find("qa")?.round)
            else assertNull(db.syncScanDao().find("qa"))
            assertEquals(emptyList<com.haebi.petworld.data.sync.SyncPhotoEntry>(), db.syncPhotoDao().list("qa", 100))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
