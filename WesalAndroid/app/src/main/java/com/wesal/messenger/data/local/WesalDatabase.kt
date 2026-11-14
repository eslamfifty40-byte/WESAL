package com.wesal.messenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.wesal.messenger.data.local.dao.WesalDao
import com.wesal.messenger.data.local.entity.CachedChatEntity
import com.wesal.messenger.data.local.entity.CachedMessageEntity

@Database(
    entities = [CachedChatEntity::class, CachedMessageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class WesalDatabase : RoomDatabase() {
    abstract fun wesalDao(): WesalDao
}
