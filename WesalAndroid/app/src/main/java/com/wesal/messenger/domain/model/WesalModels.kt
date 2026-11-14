package com.wesal.messenger.domain.model

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
    val readReceiptsEnabled: Boolean = true
)

data class WesalUser(
    val uid: String = "",
    val phoneNumber: String = "",
    val displayName: String = "",
    val publicId: String = "",
    val bio: String = "أستخدم تطبيق وصال",
    val photoUrl: String = "",
    val createdAt: Long = 0L,
    val lastSeen: Long = 0L,
    val isOnline: Boolean = false,
    val fcmToken: String = "",
    val publicKey: String = "",
    val privacySettings: PrivacySettings = PrivacySettings(),
    val blockedUsers: List<String> = emptyList()
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

enum class StoryType(val raw: String) {
    TEXT("TEXT"),
    IMAGE("IMAGE"),
    VIDEO("VIDEO");

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
    val calleeId: String = "",
    val calleeName: String = "",
    val calleeAvatarUrl: String = "",
    val isVideo: Boolean = false,
    val status: CallStatus = CallStatus.RINGING,
    val offerSdp: String = "",
    val answerSdp: String = "",
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
