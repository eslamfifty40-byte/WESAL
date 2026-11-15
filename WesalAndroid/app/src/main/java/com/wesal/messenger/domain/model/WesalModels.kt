package com.wesal.messenger.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedWesalAccount(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val publicId: String = "",
    val photoUrl: String = "",
    val provider: String = "email",
    val lastUsedAt: Long = System.currentTimeMillis()
)

enum class PrivacyVisibility(val key: String) {
    EVERYONE("everyone"),
    CONTACTS("contacts"),
    NOBODY("nobody");

    companion object {
        fun fromKey(key: String?): PrivacyVisibility =
            entries.firstOrNull { it.key == key } ?: EVERYONE
    }
}

data class PrivacySettings(
    val lastSeenVisibility: PrivacyVisibility = PrivacyVisibility.EVERYONE,
    val onlineVisibility: PrivacyVisibility = PrivacyVisibility.EVERYONE,
    val profilePhotoVisibility: PrivacyVisibility = PrivacyVisibility.EVERYONE,
    val storiesVisibility: PrivacyVisibility = PrivacyVisibility.EVERYONE,
    val readReceiptsEnabled: Boolean = true,
    val hidePhoneNumber: Boolean = false
)

data class WesalUser(
    val uid: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val isPhoneVerified: Boolean = false,
    val isVerified: Boolean = false,
    val verifiedByUid: String = "",
    val verifiedAt: Long = 0L,
    val verificationNote: String = "",
    val displayName: String = "",
    val publicId: String = "",
    val bio: String = "أستخدم تطبيق WESAL",
    val photoUrl: String = "",
    val createdAt: Long = 0L,
    val lastSeen: Long = 0L,
    val isOnline: Boolean = false,
    val fcmToken: String = "",
    val publicKey: String = "",
    val privacySettings: PrivacySettings = PrivacySettings(),
    val blockedUsers: List<String> = emptyList()
)

enum class ContactRequestStatus(val raw: String) {
    PENDING("PENDING"),
    ACCEPTED("ACCEPTED"),
    REJECTED("REJECTED");

    companion object {
        fun fromRaw(raw: String?): ContactRequestStatus =
            entries.firstOrNull { it.raw == raw } ?: PENDING
    }
}

data class ContactRequest(
    val requestId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val senderPublicId: String = "",
    val senderAvatarUrl: String = "",
    val senderVerified: Boolean = false,
    val receiverUid: String = "",
    val receiverName: String = "",
    val receiverPublicId: String = "",
    val receiverAvatarUrl: String = "",
    val receiverVerified: Boolean = false,
    val status: ContactRequestStatus = ContactRequestStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class VerificationAuditLog(
    val logId: String = "",
    val targetUid: String = "",
    val targetPublicId: String = "",
    val targetName: String = "",
    val action: String = "GRANTED",
    val adminUid: String = "",
    val adminEmail: String = "",
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageType(val raw: String) {
    TEXT("TEXT"),
    IMAGE("IMAGE"),
    VIDEO("VIDEO"),
    VOICE("VOICE"),
    DOCUMENT("DOCUMENT"),
    LOCATION("LOCATION"),
    SYSTEM("SYSTEM");

    companion object {
        fun fromRaw(raw: String?): MessageType =
            entries.firstOrNull { it.raw == raw } ?: TEXT
    }
}

enum class MessageStatus(val raw: String) {
    SENDING("SENDING"),
    SENT("SENT"),
    DELIVERED("DELIVERED"),
    READ("READ"),
    FAILED("FAILED");

    companion object {
        fun fromRaw(raw: String?): MessageStatus =
            entries.firstOrNull { it.raw == raw } ?: SENT
    }
}

data class WesalMessage(
    val messageId: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val type: MessageType = MessageType.TEXT,
    val ciphertext: String = "",
    val text: String = "",
    val isEncrypted: Boolean = false,
    val mediaUrl: String = "",
    val mediaLocalPath: String = "",
    val mediaMimeType: String = "",
    val mediaFileName: String = "",
    val mediaSizeBytes: Long = 0L,
    val mediaDurationMs: Long = 0L,
    val waveformAmplitudes: List<Int> = emptyList(),
    val wrappedKeys: Map<String, String> = emptyMap(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAddress: String = "",
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val forwardedFromName: String? = null,
    val isEdited: Boolean = false,
    val isDeletedForEveryone: Boolean = false,
    val deletedForUsers: List<String> = emptyList(),
    val status: MessageStatus = MessageStatus.SENT,
    val uploadProgress: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatPreview(
    val chatId: String = "",
    val isGroup: Boolean = false,
    val groupId: String? = null,
    val participantIds: List<String> = emptyList(),
    val peerUid: String = "",
    val title: String = "",
    val avatarUrl: String = "",
    val peerPublicKey: String = "",
    val peerOnline: Boolean = false,
    val peerLastSeen: Long = 0L,
    val peerVerified: Boolean = false,
    val lastMessagePreview: String = "",
    val lastMessageTime: Long = 0L,
    val lastMessageSenderId: String = "",
    val lastMessageStatus: MessageStatus = MessageStatus.SENT,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isMuted: Boolean = false,
    val typingUsers: List<String> = emptyList()
)

data class GroupInfo(
    val groupId: String = "",
    val chatId: String = "",
    val name: String = "",
    val description: String = "",
    val avatarUrl: String = "",
    val creatorId: String = "",
    val adminIds: List<String> = emptyList(),
    val memberIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

enum class ChannelRole {
    OWNER,
    MODERATOR,
    SUBSCRIBER,
    NONE
}

data class WesalChannel(
    val channelId: String = "",
    val title: String = "",
    val description: String = "",
    val avatarUrl: String = "",
    val isPublic: Boolean = true,
    val inviteCode: String = "",
    val ownerUid: String = "",
    val moderatorIds: List<String> = emptyList(),
    val subscriberIds: List<String> = emptyList(),
    val subscribersCount: Int = 0,
    val isVerified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun roleFor(uid: String): ChannelRole = when {
        uid.isBlank() -> ChannelRole.NONE
        ownerUid == uid -> ChannelRole.OWNER
        moderatorIds.contains(uid) -> ChannelRole.MODERATOR
        subscriberIds.contains(uid) -> ChannelRole.SUBSCRIBER
        else -> ChannelRole.NONE
    }
}

enum class ChannelPostType(val raw: String) {
    TEXT("TEXT"),
    IMAGE("IMAGE"),
    VIDEO("VIDEO"),
    AUDIO("AUDIO"),
    DOCUMENT("DOCUMENT");

    companion object {
        fun fromRaw(raw: String?): ChannelPostType =
            entries.firstOrNull { it.raw == raw } ?: TEXT
    }
}

data class ChannelPost(
    val postId: String = "",
    val channelId: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val authorAvatarUrl: String = "",
    val authorVerified: Boolean = false,
    val type: ChannelPostType = ChannelPostType.TEXT,
    val text: String = "",
    val mediaUrl: String = "",
    val mediaMimeType: String = "",
    val mediaFileName: String = "",
    val mediaSizeBytes: Long = 0L,
    val mediaDurationMs: Long = 0L,
    val reactionCounts: Map<String, Int> = emptyMap(),
    val userReactions: Map<String, String> = emptyMap(),
    val isPinned: Boolean = false,
    val commentsCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

data class ChannelComment(
    val commentId: String = "",
    val postId: String = "",
    val channelId: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val authorAvatarUrl: String = "",
    val authorVerified: Boolean = false,
    val text: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class ChannelJoinRequest(
    val requestId: String = "",
    val channelId: String = "",
    val requesterUid: String = "",
    val requesterName: String = "",
    val requesterPublicId: String = "",
    val requesterAvatarUrl: String = "",
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)

enum class StoryType(val raw: String) {
    TEXT("TEXT"),
    IMAGE("IMAGE"),
    VIDEO("VIDEO"),
    AUDIO("AUDIO");

    companion object {
        fun fromRaw(raw: String?): StoryType =
            entries.firstOrNull { it.raw == raw } ?: TEXT
    }
}

data class StoryItem(
    val storyId: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val ownerAvatarUrl: String = "",
    val type: StoryType = StoryType.TEXT,
    val textContent: String = "",
    val backgroundColorHex: Long = 0xFF064E3B,
    val mediaUrl: String = "",
    val caption: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1000L,
    val visibility: PrivacyVisibility = PrivacyVisibility.EVERYONE,
    val viewerIds: List<String> = emptyList()
)

data class StoryViewer(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val viewedAt: Long = 0L
)

enum class CallStatus(val raw: String) {
    RINGING("RINGING"),
    ACTIVE("ACTIVE"),
    ENDED("ENDED"),
    MISSED("MISSED"),
    REJECTED("REJECTED");

    companion object {
        fun fromRaw(raw: String?): CallStatus =
            entries.firstOrNull { it.raw == raw } ?: ENDED
    }
}

data class CallRecord(
    val callId: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val callerAvatarUrl: String = "",
    val callerVerified: Boolean = false,
    val calleeId: String = "",
    val calleeName: String = "",
    val calleeAvatarUrl: String = "",
    val calleeVerified: Boolean = false,
    val isVideo: Boolean = false,
    val status: CallStatus = CallStatus.RINGING,
    val offerSdp: String = "",
    val answerSdp: String = "",
    val deletedForUsers: List<String> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0L,
    val durationSeconds: Long = 0L
)

data class DeviceSession(
    val sessionId: String = "",
    val deviceName: String = "",
    val osVersion: String = "",
    val appVersion: String = "",
    val lastActiveAt: Long = System.currentTimeMillis(),
    val isCurrent: Boolean = false
)

data class CountryCode(
    val nameAr: String,
    val nameEn: String,
    val iso: String,
    val dialCode: String,
    val flagEmoji: String
)
