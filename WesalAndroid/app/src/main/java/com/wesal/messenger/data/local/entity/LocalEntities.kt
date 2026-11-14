package com.wesal.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val isMuted: Boolean
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
    val timestamp: Long
)
