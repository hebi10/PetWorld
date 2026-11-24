package com.haebi.petworld.data.room

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "petworld_outbox_migration_test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            assertEquals("나비", db.petDao().find("pet")?.name)
            val care = requireNotNull(db.careDao().find("record"))
            assertEquals(0L, care.waterMl)
            assertNull(care.foodServedMilli)
            assertEquals("Asia/Seoul", care.timezoneId)
            assertEquals(emptyList<com.haebi.petworld.data.sync.SyncOutboxEntry>(),
                db.syncOutboxDao().list("unused", 50))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
