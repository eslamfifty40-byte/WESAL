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
    // Spec §3.3: cache reads are scoped to the owning account — a row stamped with a
    // different uid (or a legacy unstamped row) is invisible to the active account.
    @Query("SELECT * FROM cached_chats WHERE ownerUid = :ownerUid ORDER BY isPinned DESC, lastMessageTime DESC")
    fun observeChats(ownerUid: String): Flow<List<CachedChatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChats(chats: List<CachedChatEntity>)

    @Query("SELECT * FROM cached_messages WHERE chatId = :chatId AND ownerUid = :ownerUid ORDER BY timestamp ASC")
    fun observeMessages(chatId: String, ownerUid: String): Flow<List<CachedMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<CachedMessageEntity>)

    @Query("DELETE FROM cached_chats WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    /** Spec §8.3: hidden chats keep no cached message rows on disk. */
    @Query("DELETE FROM cached_messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: String)

    @Query("DELETE FROM cached_chats")
    suspend fun clearAllChats()

    @Query("DELETE FROM cached_messages")
    suspend fun clearAllMessages()
}
