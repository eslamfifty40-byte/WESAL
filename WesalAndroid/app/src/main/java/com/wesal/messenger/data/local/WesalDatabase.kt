package com.wesal.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wesal.messenger.data.local.dao.WesalDao
import com.wesal.messenger.data.local.entity.CachedChatEntity
import com.wesal.messenger.data.local.entity.CachedMessageEntity

@Database(
    entities = [CachedChatEntity::class, CachedMessageEntity::class],
    version = 2,
    exportSchema = true
)
abstract class WesalDatabase : RoomDatabase() {
    abstract fun wesalDao(): WesalDao

    companion object {
        /**
         * v1 → v2 (spec §3.3): per-account isolation — adds the ownerUid column to both
         * cache tables. Existing rows default to "" and are never read back (all reads
         * filter by the active uid); they are re-fetched from Firestore under the owner.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_chats ADD COLUMN ownerUid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN ownerUid TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
