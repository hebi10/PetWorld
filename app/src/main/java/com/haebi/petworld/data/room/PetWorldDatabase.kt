package com.haebi.petworld.data.room

import android.content.Context
import com.haebi.petworld.data.account.LocalAccountScope
import com.haebi.petworld.data.account.databaseFileName
import com.haebi.petworld.data.sync.SyncOutboxDao
import com.haebi.petworld.data.sync.SyncOutboxEntry
import com.haebi.petworld.data.sync.SyncInboundDao
import com.haebi.petworld.data.sync.SyncInboundEntry
import com.haebi.petworld.data.sync.SyncScanDao
import com.haebi.petworld.data.sync.SyncScanState
import com.haebi.petworld.data.sync.SyncPhotoDao
import com.haebi.petworld.data.sync.SyncPhotoEntry
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Matches the legacy 1-C `pets` table exactly, including defaults and index order. */
@Entity(
    tableName = "pets",
    indices = [Index(
        value = ["is_archived", "updated_at"],
        orders = [Index.Order.ASC, Index.Order.DESC],
        name = "idx_pets_archived_updated",
    )],
)
data class PetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val species: String,
    @ColumnInfo(defaultValue = "''") val breed: String,
    val birthday: String?,
    @ColumnInfo(name = "estimated_age_years") val estimatedAgeYears: Int?,
    val sex: String,
    @ColumnInfo(name = "neuter_status") val neuterStatus: String,
    @ColumnInfo(defaultValue = "''") val notes: String,
    @ColumnInfo(name = "photo_file") val photoFile: String?,
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "care_entries",
    foreignKeys = [ForeignKey(
        entity = PetEntity::class,
        parentColumns = ["id"],
        childColumns = ["pet_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["pet_id", "occurred_at"], name = "idx_care_pet_time"),
        Index(value = ["pet_id", "type", "occurred_at"], name = "idx_care_pet_type_time"),
    ],
)
data class CareEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "pet_id") val petId: String,
    val type: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "timezone_id") val timezoneId: String,
    @ColumnInfo(name = "food_kind") val foodKind: String?,
    @ColumnInfo(name = "food_product_name") val foodProductName: String? = null,
    @ColumnInfo(name = "food_served_milli") val foodServedMilli: Long?,
    @ColumnInfo(name = "food_consumed_milli") val foodConsumedMilli: Long?,
    @ColumnInfo(name = "food_unit") val foodUnit: String?,
    @ColumnInfo(name = "water_ml") val waterMl: Long?,
    @ColumnInfo(name = "elimination_kind") val eliminationKind: String?,
    @ColumnInfo(name = "elimination_count") val eliminationCount: Int?,
    @ColumnInfo(name = "activity_kind") val activityKind: String?,
    @ColumnInfo(name = "activity_minutes") val activityMinutes: Int?,
    @ColumnInfo(name = "weight_grams") val weightGrams: Long?,
    @ColumnInfo(name = "weight_input_unit") val weightInputUnit: String?,
    @ColumnInfo(name = "photo_file") val photoFile: String? = null,
    val note: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface PetDao {
    @Query("SELECT EXISTS(SELECT 1 FROM pets WHERE photo_file = :name)")
    suspend fun referencesPhoto(name: String): Boolean

    @Query("SELECT * FROM pets ORDER BY is_archived ASC, created_at ASC")
    suspend fun list(): List<PetEntity>

    @Query("SELECT * FROM pets WHERE id = :id LIMIT 1")
    suspend fun find(id: String): PetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PetEntity)

    @Update
    suspend fun update(entity: PetEntity): Int

    @Query("DELETE FROM pets")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM pets WHERE id = :id")
    suspend fun delete(id: String): Int
}

data class CarePetCount(
    @ColumnInfo(name = "pet_id") val petId: String,
    @ColumnInfo(name = "record_count") val recordCount: Int,
)

data class CareHistoryIndex(
    val id: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "timezone_id") val timezoneId: String,
)

@Dao
interface CareDao {
    @Query("SELECT EXISTS(SELECT 1 FROM care_entries WHERE photo_file = :name)")
    suspend fun referencesPhoto(name: String): Boolean

    @Query("SELECT * FROM care_entries ORDER BY occurred_at DESC, created_at DESC")
    fun observeAll(): Flow<List<CareEntity>>

    @Query("SELECT * FROM care_entries WHERE pet_id = :petId ORDER BY occurred_at DESC, created_at DESC")
    fun observePet(petId: String): Flow<List<CareEntity>>

    @Query("SELECT pet_id, COUNT(*) AS record_count FROM care_entries GROUP BY pet_id")
    fun observeRecordCounts(): Flow<List<CarePetCount>>

    @Query("SELECT * FROM care_entries WHERE id = :id AND pet_id = :petId LIMIT 1")
    fun observeRecord(petId: String, id: String): Flow<CareEntity?>

    @Query("""
        SELECT COUNT(*) FROM care_entries
        WHERE pet_id = :petId AND (:recordType IS NULL OR type = :recordType)
    """)
    suspend fun countHistory(petId: String, recordType: String?): Int

    @Query("""
        SELECT * FROM care_entries
        WHERE pet_id = :petId AND (:recordType IS NULL OR type = :recordType)
        ORDER BY occurred_at DESC, created_at DESC, id DESC LIMIT :limit
    """)
    suspend fun historyFirst(petId: String, recordType: String?, limit: Int): List<CareEntity>

    @Query("""
        SELECT id, occurred_at, timezone_id FROM care_entries
        WHERE pet_id = :petId AND (:recordType IS NULL OR type = :recordType)
          AND occurred_at >= :fromMillis AND occurred_at < :untilMillis
        ORDER BY occurred_at DESC, created_at DESC, id DESC
    """)
    suspend fun historyIndex(
        petId: String, recordType: String?, fromMillis: Long, untilMillis: Long,
    ): List<CareHistoryIndex>

    @Query("SELECT * FROM care_entries WHERE pet_id = :petId AND id IN (:ids)")
    suspend fun historyByIds(petId: String, ids: List<String>): List<CareEntity>

    @Query("SELECT * FROM care_entries WHERE id = :id LIMIT 1")
    suspend fun find(id: String): CareEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CareEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<CareEntity>)

    @Update
    suspend fun update(entity: CareEntity): Int

    @Query("DELETE FROM care_entries WHERE id = :id AND pet_id = :petId")
    suspend fun delete(id: String, petId: String): Int

    @Query("SELECT * FROM care_entries")
    suspend fun listAll(): List<CareEntity>

    @Query("SELECT * FROM care_entries WHERE pet_id = :petId")
    suspend fun listForPet(petId: String): List<CareEntity>

    @Query("SELECT COUNT(*) FROM care_entries WHERE pet_id = :petId")
    suspend fun countForPet(petId: String): Int
}

/** Existing 1-C SQLiteOpenHelper used this same file with PRAGMA user_version=1. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `care_entries` (
                `id` TEXT NOT NULL,
                `pet_id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `occurred_at` INTEGER NOT NULL,
                `timezone_id` TEXT NOT NULL,
                `food_kind` TEXT,
                `food_product_name` TEXT,
                `food_served_milli` INTEGER,
                `food_consumed_milli` INTEGER,
                `food_unit` TEXT,
                `water_ml` INTEGER,
                `elimination_kind` TEXT,
                `elimination_count` INTEGER,
                `activity_kind` TEXT,
                `activity_minutes` INTEGER,
                `weight_grams` INTEGER,
                `weight_input_unit` TEXT,
                `photo_file` TEXT,
                `note` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`pet_id`) REFERENCES `pets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_care_pet_time` ON `care_entries` (`pet_id`, `occurred_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_care_pet_type_time` ON `care_entries` (`pet_id`, `type`, `occurred_at`)"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_outbox` (
                `sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `op_id` TEXT NOT NULL,
                `account_key` TEXT NOT NULL,
                `entity_kind` TEXT NOT NULL,
                `pet_id` TEXT NOT NULL,
                `entity_id` TEXT NOT NULL,
                `operation` TEXT NOT NULL,
                `before_json` TEXT,
                `after_json` TEXT,
                `expected_revision` INTEGER,
                `created_at` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `attempt` INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_op_id` ON `sync_outbox` (`op_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_account_key_sequence` ON `sync_outbox` (`account_key`, `sequence`)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_inbound` (
                `sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `account_key` TEXT NOT NULL,
                `entity_kind` TEXT NOT NULL,
                `pet_id` TEXT NOT NULL,
                `entity_id` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `payload_digest` TEXT NOT NULL,
                `remote_json` TEXT NOT NULL,
                `local_json` TEXT,
                `tombstone` INTEGER NOT NULL,
                `status` TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_sync_inbound_identity` ON `sync_inbound` (`account_key`, `entity_kind`, `pet_id`, `entity_id`, `revision`, `payload_digest`)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_scan` (
                `account_key` TEXT NOT NULL PRIMARY KEY,
                `round` INTEGER NOT NULL,
                `pet_cursor` TEXT,
                `pending_parents_json` TEXT NOT NULL,
                `record_cursor` TEXT,
                `root_exhausted` INTEGER NOT NULL
            )""".trimIndent()
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_photos` (
                `account_key` TEXT NOT NULL,
                `pet_id` TEXT NOT NULL,
                `file_name` TEXT NOT NULL,
                `sha256` TEXT,
                `size_bytes` INTEGER,
                `width` INTEGER,
                `height` INTEGER,
                `status` TEXT NOT NULL,
                `retired` INTEGER NOT NULL,
                PRIMARY KEY(`account_key`, `pet_id`, `file_name`)
            )""".trimIndent()
        )
    }
}

@Database(
    entities = [PetEntity::class, CareEntity::class, SyncOutboxEntry::class,
        SyncInboundEntry::class, SyncScanState::class, SyncPhotoEntry::class],
    version = 6,
    exportSchema = false,
)
abstract class PetWorldDatabase : RoomDatabase() {
    abstract fun petDao(): PetDao
    abstract fun careDao(): CareDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun syncInboundDao(): SyncInboundDao
    abstract fun syncScanDao(): SyncScanDao
    abstract fun syncPhotoDao(): SyncPhotoDao

    companion object {
        private val instances = mutableMapOf<String, PetWorldDatabase>()

        fun get(context: Context): PetWorldDatabase = get(context, LocalAccountScope.Guest)

        @Synchronized
        fun get(context: Context, scope: LocalAccountScope): PetWorldDatabase {
            val name = scope.databaseFileName
            return instances.getOrPut(name) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PetWorldDatabase::class.java,
                    name,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
            }
        }

        @Synchronized
        fun closeScope(scope: LocalAccountScope) {
            instances.remove(scope.databaseFileName)?.close()
        }
    }
}
