package com.haebi.petworld.data.room

import android.content.Context
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

@Dao
interface CareDao {
    @Query("SELECT * FROM care_entries ORDER BY occurred_at DESC, created_at DESC")
    fun observeAll(): Flow<List<CareEntity>>

    @Query("SELECT * FROM care_entries WHERE pet_id = :petId ORDER BY occurred_at DESC, created_at DESC")
    fun observePet(petId: String): Flow<List<CareEntity>>

    @Query("SELECT * FROM care_entries WHERE id = :id LIMIT 1")
    suspend fun find(id: String): CareEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CareEntity)

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

@Database(
    entities = [PetEntity::class, CareEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class PetWorldDatabase : RoomDatabase() {
    abstract fun petDao(): PetDao
    abstract fun careDao(): CareDao

    companion object {
        private const val DATABASE_NAME = "petworld_profiles.db"
        @Volatile private var instance: PetWorldDatabase? = null

        fun get(context: Context): PetWorldDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PetWorldDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
