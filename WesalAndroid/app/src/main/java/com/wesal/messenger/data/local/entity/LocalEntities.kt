package com.wesal.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-account local cache (spec §3.3): every row is stamped with the owning account's
 * [ownerUid] and every read is filtered by it, so switching accounts can never surface
 * another account's cached chats/messages even if a clear-on-switch is missed.
 * Rows written before the owner column existed carry ownerUid = "" and are simply
 * never read back (they are re-fetched from Firestore under the active account).
 */
@Entity(tableName = "cached_chats")
data class CachedChatEntity(
    @PrimaryKey val chatId: String,
    val isGroup: Boolean,
    val title: String,
    val avatarUrl: String,
    val peerUid: String,
    val lastMessagePreview: String,
    val lastMessageTime: Long,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isMuted: Boolean,
    val ownerUid: String = ""
)

@Entity(tableName = "cached_messages")
data class CachedMessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val type: String,
    val text: String,
    val mediaUrl: String,
    val mediaLocalPath: String,
    val mediaMimeType: String,
    val mediaFileName: String,
    val mediaSizeBytes: Long,
    val mediaDurationMs: Long,
    val status: String,
    val timestamp: Long,
    val ownerUid: String = ""
)
