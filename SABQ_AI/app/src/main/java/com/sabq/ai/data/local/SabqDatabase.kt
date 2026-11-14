package com.sabq.ai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class SabqTypeConverters {
    @TypeConverter
    fun fromMemoryCategory(value: MemoryCategory): String = value.name

    @TypeConverter
    fun toMemoryCategory(value: String): MemoryCategory = MemoryCategory.valueOf(value)

    @TypeConverter
    fun fromCertaintyLevel(value: CertaintyLevel): String = value.name

    @TypeConverter
    fun toCertaintyLevel(value: String): CertaintyLevel = CertaintyLevel.valueOf(value)
}

@Database(
    entities = [
        UserProfileEntity::class,
        MemoryItemEntity::class,
        ChatMessageEntity::class,
        HealthScanEntity::class,
        LabReadingEntity::class,
        UploadedImageEntity::class,
        ScanSessionEntity::class,
        ReportRecordEntity::class,
        HealthRiskAssessmentEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(SabqTypeConverters::class)
abstract class SabqDatabase : RoomDatabase() {
    abstract fun sabqDao(): SabqDao

    companion object {
        @Volatile
        private var INSTANCE: SabqDatabase? = null

        fun getInstance(context: Context): SabqDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SabqDatabase::class.java,
                    "sabq_ai_encrypted_store.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
