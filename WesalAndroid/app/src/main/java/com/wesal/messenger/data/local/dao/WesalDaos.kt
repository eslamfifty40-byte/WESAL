package com.wesal.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wesal.messenger.data.local.entity.CachedChatEntity
import com.wesal.messenger.data.local.entity.CachedMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WesalDao {
    @Query("SELECT * FROM cached_chats ORDER BY isPinned DESC, lastMessageTime DESC")
    fun observeChats(): Flow<List<CachedChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChats(chats: List<CachedChatEntity>)

    @Query("SELECT * FROM cached_messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun observeMessages(chatId: String): Flow<List<CachedMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<CachedMessageEntity>)

    @Query("DELETE FROM cached_chats WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    @Query("DELETE FROM cached_chats")
    suspend fun clearAllChats()

    @Query("DELETE FROM cached_messages")
    suspend fun clearAllMessages()
}
