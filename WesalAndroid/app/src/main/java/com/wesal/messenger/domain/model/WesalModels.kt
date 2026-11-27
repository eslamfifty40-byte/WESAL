package com.wesal.messenger.domain.model

import kotlinx.serialization.Serializable

/**
 * A text message that has not been confirmed sent yet. Persisted per account in DataStore
 * BEFORE the send is attempted, so a killed/restarted app never loses a pending message:
 * on relaunch it is retried with the SAME messageId (the send path is idempotent for a
 * caller-supplied id), then removed from the outbox once the send succeeds.
 */
@Serializable
data class PendingOutboxMessage(
    val messageId: String = "",
    val chatId: String = "",
    val text: String = "",
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val forwardedFromName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    // Failover design §5.2 rule 5: the store a previous attempt targeted
    // (MessagePlaneRouter wire name), stamped when that attempt's outcome
    // became unknown (the ACK-window timeout / queued case). Replay checks
    // existence on the hinted store before re-sending. Nullable +
    // defaulted: entries persisted by older builds decode unchanged.
    val attemptedStore: String? = null
)

@Serializable
data class SavedWesalAccount(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val publicId: String = "",
    val numericId: String = "",
    val photoUrl: String = "",
    val provider: String = "email",
    val lastUsedAt: Long = System.currentTimeMillis()
)

/**
 * Failover design §3.2: the on-device own-profile snapshot — the fields
 * the entry decisions (profile completeness, phone-gate verdict, ban /
 * deleted state) are made from when Firestore cannot be consulted
 * (quota-dead). Persisted per uid in the DataStore account vault and
 * refreshed on every successful own-doc read/write, so it is at most
 * one session stale.
 *
 * [phoneVerifiedMirror] is NOT the doc's raw isPhoneVerified flag: it
 * is the complete-mirror verdict (flag + number + phoneHash), computed
 * at snapshot-write time by OwnProfileSnapshot.fromUser — the snapshot
 * can only carry a verification that was genuinely earned.
 */
@Serializable
data class CachedOwnProfile(
    val uid: String = "",
    val profileCompleted: Boolean = false,
    val phoneHash: String = "",
    val phoneVerifiedMirror: Boolean = false,
    val isBanned: Boolean = false,
    val banType: String = "",
    val banExpiresAt: Long = 0L,
    val deletedAt: Long = 0L,
    val deletedPermanent: Boolean = false,
    val savedAtMs: Long = 0L
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

enum class GroupAddPrivacy(val raw: String) {
    EVERYONE("EVERYONE"),
    CONTACTS("CONTACTS"),
    NOBODY("NOBODY");

    companion object {
        fun fromRaw(raw: String?): GroupAddPrivacy =
            entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: EVERYONE
    }
}

enum class StoryAudienceMode(val raw: String) {
    CONTACTS("CONTACTS"),
    CONTACTS_EXCEPT("CONTACTS_EXCEPT"),
    ONLY_WITH("ONLY_WITH"),
    ALL_CONTACTS("ALL_CONTACTS");

    companion object {
        fun fromRaw(raw: String?): StoryAudienceMode =
            entries.firstOrNull { it.raw == raw } ?: CONTACTS
    }
}

enum class GroupJoinMode(val raw: String) {
    OPEN("OPEN"),
    APPROVAL_REQUIRED("APPROVAL_REQUIRED"),
    CLOSED("CLOSED"),
    INVITE_ONLY("INVITE_ONLY");

    companion object {
        fun fromRaw(raw: String?): GroupJoinMode =
            entries.firstOrNull { it.raw == raw } ?: OPEN
    }
}

@Serializable
data class BusinessDaySlot(
    val openTime: String = "09:00",
    val closeTime: String = "17:00"
)

@Serializable
data class BusinessDayHours(
    val dayKey: String = "SAT", // SAT, SUN, MON, TUE, WED, THU, FRI
    val isClosed: Boolean = false,
    val isOpen24Hours: Boolean = false,
    val slots: List<BusinessDaySlot> = listOf(BusinessDaySlot())
)

@Serializable
data class BusinessHoursSchedule(
    val enabled: Boolean = false,
    val businessName: String = "",
    val timeZoneId: String = "Africa/Cairo",
    val days: List<BusinessDayHours> = listOf(
        BusinessDayHours("SAT"),
        BusinessDayHours("SUN"),
        BusinessDayHours("MON"),
        BusinessDayHours("TUE"),
        BusinessDayHours("WED"),
        BusinessDayHours("THU"),
        BusinessDayHours("FRI", isClosed = true)
    )
)

data class PrivacySettings(
    val lastSeenVisibility: PrivacyVisibility = PrivacyVisibility.EVERYONE,
    val onlineVisibility: PrivacyVisibility = PrivacyVisibility.EVERYONE,
    val profilePhotoVisibility: PrivacyVisibility = PrivacyVisibility.EVERYONE,
    val storiesVisibility: PrivacyVisibility = PrivacyVisibility.EVERYONE,
    val readReceiptsEnabled: Boolean = true,
    val hidePhoneNumber: Boolean = false,
    val hideBirthDate: Boolean = false,
    val hideAvatarPhoto: Boolean = false,
    val hideCoverPhoto: Boolean = false,
    val recordProfileVisits: Boolean = true,
    // FIX 3 (round 5): «حماية المحادثات من تصوير الشاشة» — when ON (default),
    // chat destinations apply FLAG_SECURE. Hidden chats and view-once media
    // stay protected regardless (see ScreenshotProtectionPolicy).
    val screenshotProtectionEnabled: Boolean = true
)

@Serializable
data class AdminPermissions(
    val canManageVerification: Boolean = false,
    val canManageFramesAndNeon: Boolean = false,
    val canManageLibrary: Boolean = false,
    val canManageUsersModeration: Boolean = false,
    val canInspectPrivateData: Boolean = false,
    val canManageReports: Boolean = false,
    val canBroadcastAnnouncements: Boolean = false,
    val canCustomizeGlobalTheme: Boolean = false,
    val canManageAdmins: Boolean = false,
    val isFullSuperAdmin: Boolean = false,
    val active: Boolean = true,
    val grantedByUid: String = "",
    val updatedAt: Long = 0L
) {
    fun hasAnyAccess(): Boolean = active && (
        isFullSuperAdmin ||
            canManageVerification ||
            canManageFramesAndNeon ||
            canManageLibrary ||
            canManageUsersModeration ||
            canInspectPrivateData ||
            canManageReports ||
            canBroadcastAnnouncements ||
            canCustomizeGlobalTheme ||
            canManageAdmins
        )

    companion object {
        fun fullSuperAdmin(grantedByUid: String = ""): AdminPermissions = AdminPermissions(
            canManageVerification = true,
            canManageFramesAndNeon = true,
            canManageLibrary = true,
            canManageUsersModeration = true,
            canInspectPrivateData = true,
            canManageReports = true,
            canBroadcastAnnouncements = true,
            canCustomizeGlobalTheme = true,
            canManageAdmins = true,
            isFullSuperAdmin = true,
            active = true,
            grantedByUid = grantedByUid,
            updatedAt = System.currentTimeMillis()
        )
    }
}

data class GlobalAppearanceConfig(
    val enabled: Boolean = false,
    val emeraldHex: Long = 0xFF10B981L,
    val turquoiseHex: Long = 0xFF00D9E8L,
    val backgroundHex: Long = 0L,
    val surfaceHex: Long = 0L,
    val textPrimaryHex: Long = 0L,
    val iconTintHex: Long = 0L,
    val iconPack: String = "DEFAULT",
    val globalCustomerFrameEnabled: Boolean = true,
    val defaultCustomerFrameId: String = "cust_01",
    val defaultCustomerIconId: String = "",
    val defaultCustomerIconUrl: String = "",
    val updatedAt: Long = 0L,
    val updatedByUid: String = ""
)

/**
 * Forced/soft app-update policy, read live from `system_config/app_update`
 * (written only from the admin panel). Defaults are fail-open: a missing doc,
 * a listener error or `enabled = false` means no update UI is ever shown.
 */
data class AppUpdateConfig(
    val enabled: Boolean = false,
    val latestVersionCode: Int = 0,
    val minSupportedVersionCode: Int = 0,
    val apkUrl: String = "",
    val messageAr: String = "",
    val messageEn: String = "",
    val mandatory: Boolean = false,
    val updatedAt: Long = 0L,
    val updatedByUid: String = ""
)

data class SubscriptionRequest(
    val requestId: String = "",
    val userUid: String = "",
    val userName: String = "",
    val userNumericId: String = "",
    val userPublicId: String = "",
    val requestType: String = "VERIFICATION_BLUE",
    val desiredSeal: String = "BLUE",
    val desiredFrameId: String = "",
    val desiredGlowColor: String = "",
    val idDocumentUrl: String = "",
    // Round 12: when the Storage upload failed, the ID image rides INSIDE
    // the doc as a data:image/jpeg;base64 string (idDocumentSource tells
    // the surfaces which payload exists: STORAGE / INLINE / NONE; blank
    // on legacy docs, which always carry a Storage URL).
    val idDocumentInline: String = "",
    val idDocumentSource: String = "",
    val note: String = "",
    val status: String = "PENDING", // PENDING, APPROVED, REJECTED
    val adminResponse: String = "",
    val reviewedByUid: String = "",
    val reviewedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

data class DynamicLibraryItem(
    val id: String = "",
    val kind: String = "FRAME", // "FRAME" or "ICON"
    val titleAr: String = "",
    val titleEn: String = "",
    val category: String = "ADMIN", // "CUSTOMER" or "ADMIN"
    val imageUrl: String = "",
    val accentColorHex: Long = 0xFF00D9E8L,
    val isHidden: Boolean = false,
    val sortOrder: Int = 0,
    val createdByUid: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class WesalUser(
    val uid: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val isPhoneVerified: Boolean = false,
    // Round 6: the doc's canonical phoneHash (sha256 of the E.164 number) and
    // the legacy digits-only phoneDigits field, parsed for the OWNER's own
    // doc only (see toWesalUser). The gate needs the hash to judge whether a
    // verification mirror is complete; the verify flow needs the digits to
    // pre-fill a number whose phoneNumber was left blank by older writers.
    val phoneHash: String = "",
    val phoneDigits: String = "",
    val isVerified: Boolean = false,
    val verificationType: String = "BLUE", // 30 seal presets in VerificationSealType
    val verificationGlowColor: String = "", // Optional independent halo from 30 NeonColorPreset colors
    val verificationGlowEnabled: Boolean = true,
    val verificationGlowIntensity: Float = 0.7f,
    val verificationExpiresAt: Long = 0L, // 0 = permanent
    val verifiedByUid: String = "",
    val verifiedAt: Long = 0L,
    val verificationNote: String = "",
    val displayName: String = "",
    val publicId: String = "",
    val numericId: String = "",
    val bio: String = "أستخدم تطبيق WESAL",
    val photoUrl: String = "",
    val coverUrl: String = "",
    val birthDate: String = "", // ISO YYYY-MM-DD (timezone-independent)
    val frameId: String = "",
    val frameUrl: String = "",
    val frameExpiresAt: Long = 0L, // 0 = permanent
    // Frames an admin has granted this account over time. The owner may re-apply any of
    // these (or no frame) from the profile picker; firestore.rules enforces the same list.
    val grantedFrameIds: List<String> = emptyList(),
    // Round 12 (addendum 6): gift-grant snapshots. While a TIMED admin gift
    // is in force, the marker is true and the preGift* fields hold the state
    // the user had BEFORE the gift; at expiry the effective state reverts
    // to the snapshot (see GiftGrantLogic + toWesalUser). Defaults keep
    // every pre-r12 doc and constructor call-site unchanged.
    val giftBadgeActive: Boolean = false,
    val preGiftIsVerified: Boolean = false,
    val preGiftVerificationType: String = "BLUE",
    val preGiftVerificationGlowColor: String = "",
    val preGiftVerificationGlowEnabled: Boolean = true,
    val preGiftVerificationGlowIntensity: Float = 0.7f,
    val preGiftVerificationExpiresAt: Long = 0L,
    val giftFrameActive: Boolean = false,
    val preGiftFrameId: String = "",
    val preGiftFrameExpiresAt: Long = 0L,
    // Round 12 (addendum 11): once-ever stamp of the automatic welcome
    // gift (0 = never granted). See WelcomeGiftLogic.
    val welcomeGiftGrantedAt: Long = 0L,
    val iconId: String = "",
    val iconUrl: String = "",
    val photoRemovedBg: Boolean = false,
    // Round 9 (item 7): the owner manually framed this avatar in the crop editor;
    // the saved square already carries his composition, so WesalAvatar renders it
    // as-is (no alpha-bounds re-fit). Stamped by the crop/save flow only.
    val photoUserFramed: Boolean = false,
    val neonEnabled: Boolean = false,
    val neonColor: String = "", // "", RED, GREEN, BLACK, BLUE
    val neonGlowIntensity: Float = 0.7f,
    val nameplateColor: String = "CRIMSON",
    val groupAddPrivacy: GroupAddPrivacy = GroupAddPrivacy.EVERYONE,
    val adminPermissions: AdminPermissions? = null,
    val facebookUrl: String = "",
    val instagramUrl: String = "",
    val businessHours: BusinessHoursSchedule = BusinessHoursSchedule(),
    val locationGranted: Boolean = false,
    val locationFallbackStartedAt: Long = 0L,
    val profileCompleted: Boolean = false,
    val welcomeSent: Boolean = false,
    val hiddenFromSearch: Boolean = false,
    val hideFromSearchReason: String = "",
    val hiddenAt: Long = 0L,
    val hiddenByUid: String = "",
    val isBanned: Boolean = false,
    val banType: String = "", // TEMPORARY, PERMANENT
    val banReason: String = "",
    val banExpiresAt: Long = 0L,
    val warningsCount: Int = 0,
    val deletedAt: Long = 0L,
    val deletedPermanent: Boolean = false,
    val createdAt: Long = 0L,
    val lastSeen: Long = 0L,
    val lastHeartbeatAt: Long = 0L,
    val isOnline: Boolean = false,
    val activeSessionsCount: Int = 0,
    val lastDeviceName: String = "",
    val lastAppVersion: String = "",
    val lastActivityType: String = "", // CHAT, GROUP, CHANNEL, NONE
    val lastActivityChatId: String = "",
    val lastActivityParticipantsCount: Int = 0,
    val lastActivityAt: Long = 0L,
    val fcmToken: String = "",
    val publicKey: String = "",
    val privacySettings: PrivacySettings = PrivacySettings(),
    val blockedUsers: List<String> = emptyList(),
    // Referral attribution (round 3): stamped once, at account creation only, when the
    // account was created after this device opened another user's /join?id= invite link.
    // referredByNumericId mirrors the inviter's numericId (stored on the doc as a number).
    val referredByUid: String = "",
    val referredByNumericId: Long = 0L
)

enum class ContactRequestStatus(val raw: String) {
    PENDING("PENDING"),
    ACCEPTED("ACCEPTED"),
    REJECTED("REJECTED"),
    HELD("HELD");

    companion object {
        fun fromRaw(raw: String?): ContactRequestStatus =
            entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: PENDING
    }
}

data class ContactRequest(
    val requestId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val senderPublicId: String = "",
    val senderAvatarUrl: String = "",
    val senderVerified: Boolean = false,
    // FIX 1 (round 5): appearance snapshot for both sides of the request so
    // request rows render the nameplate icon/neon like every other surface.
    val senderVerificationType: String = "BLUE",
    val senderIconId: String = "",
    val senderIconUrl: String = "",
    val senderNeonColor: String = "",
    val senderNeonEnabled: Boolean = false,
    val senderNeonGlowIntensity: Float = 0.7f,
    val receiverUid: String = "",
    val receiverName: String = "",
    val receiverPublicId: String = "",
    val receiverAvatarUrl: String = "",
    val receiverVerified: Boolean = false,
    val receiverVerificationType: String = "BLUE",
    val receiverIconId: String = "",
    val receiverIconUrl: String = "",
    val receiverNeonColor: String = "",
    val receiverNeonEnabled: Boolean = false,
    val receiverNeonGlowIntensity: Float = 0.7f,
    val status: ContactRequestStatus = ContactRequestStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ProfileVisitor(
    val visitorUid: String = "",
    val visitorName: String = "",
    val visitorPublicId: String = "",
    val visitorNumericId: String = "",
    val visitorAvatarUrl: String = "",
    val visitorVerified: Boolean = false,
    val visitorVerificationType: String = "BLUE",
    // FIX 1 (round 5): visitor appearance snapshot for the visitors list.
    val visitorIconId: String = "",
    val visitorIconUrl: String = "",
    val visitorNeonColor: String = "",
    val visitorNeonEnabled: Boolean = false,
    val visitorNeonGlowIntensity: Float = 0.7f,
    val visitedAt: Long = System.currentTimeMillis()
)

data class VerificationAuditLog(
    val logId: String = "",
    val operationId: String = "",
    val targetUid: String = "",
    val targetNumericId: String = "",
    val targetPublicId: String = "",
    val targetName: String = "",
    val action: String = "GRANTED", // GRANTED, REVOKED, EXPIRED, FRAME_GRANTED, NEON_UPDATED, HIDDEN, UNHIDDEN, etc.
    val verificationType: String = "BLUE",
    val durationLabel: String = "",
    val expiresAt: Long = 0L,
    val adminUid: String = "",
    val adminEmail: String = "",
    val note: String = "",
    val details: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class AdminReportRecord(
    val reportId: String = "",
    val reporterUid: String = "",
    val reporterName: String = "",
    val reportedUid: String = "",
    val reportedName: String = "",
    val targetType: String = "USER", // USER, MESSAGE, CHAT, CHANNEL, POST, STORY
    val targetChatId: String = "",
    val targetMessageId: String = "",
    val targetContentSnapshot: String = "",
    val reason: String = "",
    val status: String = "NEW", // NEW, IN_REVIEW, RESOLVED, REJECTED, CLOSED
    val adminNote: String = "",
    val resolutionAction: String = "",
    val reviewedByUid: String = "",
    val reviewedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

data class OfficialAnnouncement(
    val announcementId: String = "",
    val title: String = "WESAL Messenger",
    val body: String = "",
    val adminUid: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class SystemWelcomeConfig(
    val enabled: Boolean = true,
    val messageTemplate: String = DEFAULT_OFFICIAL_WELCOME_TEMPLATE,
    val previousVersions: List<String> = emptyList(),
    val updatedAt: Long = 0L,
    val updatedByUid: String = ""
) {
    companion object {
        const val DEFAULT_OFFICIAL_WELCOME_TEMPLATE =
            "مرحبًا بك يا [اسم المستخدم] 👋\n\n" +
                "أهلًا وسهلًا بك في WESAL Messenger ❤️\n\n" +
                "سعداء جدًا بانضمامك إلى عائلة وصال!\n\n" +
                "من هنا تبدأ رحلتك للتواصل مع أصدقائك ومشاركة لحظاتك بكل سهولة.\n\n" +
                "يمكنك إرسال الرسائل والصور والفيديوهات والتسجيلات الصوتية، وإجراء المكالمات الصوتية والمرئية، وإنشاء المجموعات والقنوات والتواصل مع الأشخاص الذين تعرفهم.\n\n" +
                "نحرص دائمًا على توفير تجربة سهلة وآمنة تحترم خصوصيتك.\n\n" +
                "نتمنى لك وقتًا ممتعًا وتجربة رائعة معنا ❤️\n\n" +
                "أهلًا بك بيننا.\n\n" +
                "فريق وصال | WESAL Team"
    }
}

data class AdminDashboardStats(
    val totalAccounts: Int = 0,
    val onlineNowCount: Int = 0,
    val offlineCount: Int = 0,
    val newTodayCount: Int = 0,
    val newTwoDaysCount: Int = 0,
    val newWeekCount: Int = 0,
    val newMonthCount: Int = 0,
    val activeTodayCount: Int = 0,
    val activeWeekCount: Int = 0,
    val activeMonthCount: Int = 0,
    val bannedCount: Int = 0,
    val deletedCount: Int = 0,
    val hiddenCount: Int = 0,
    val verifiedCount: Int = 0
)

enum class MessageType(val raw: String) {
    TEXT("TEXT"),
    IMAGE("IMAGE"),
    VIDEO("VIDEO"),
    VOICE("VOICE"),
    AUDIO("AUDIO"),
    DOCUMENT("DOCUMENT"),
    LOCATION("LOCATION"),
    LIVE_LOCATION("LIVE_LOCATION"),
    CALL_LOG("CALL_LOG"),
    SYSTEM("SYSTEM");

    companion object {
        fun fromRaw(raw: String?): MessageType =
            entries.firstOrNull { it.raw == raw } ?: TEXT
    }
}

enum class DisappearingTimer(val seconds: Long, val labelAr: String, val labelEn: String) {
    OFF(0L, "إيقاف", "Off"),
    HOURS_24(86_400L, "24 ساعة", "24 Hours"),
    DAYS_7(604_800L, "7 أيام", "7 Days"),
    DAYS_90(7_776_000L, "90 يوماً", "90 Days");

    companion object {
        fun fromSeconds(sec: Long): DisappearingTimer =
            entries.firstOrNull { it.seconds == sec } ?: OFF
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
    // FIX 1 (round 5): sender appearance snapshot, written at send time from
    // the sender's user doc so group-chat sender names render with the same
    // nameplate icon / neon / verified badge as everywhere else. Empty for
    // messages sent before this snapshot existed (name renders plainly).
    val senderIconId: String = "",
    val senderIconUrl: String = "",
    val senderVerified: Boolean = false,
    val senderVerificationType: String = "BLUE",
    val senderNeonColor: String = "",
    val senderNeonEnabled: Boolean = false,
    val senderNeonGlowIntensity: Float = 0.7f,
    val type: MessageType = MessageType.TEXT,
    val ciphertext: String = "",
    val text: String = "",
    val isEncrypted: Boolean = false,
    val isViewOnce: Boolean = false,
    val viewedBy: List<String> = emptyList(),
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
    val liveLocationExpiresAt: Long = 0L,
    val disappearingExpiresAt: Long = 0L,
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

/** Sync state of the chats list (spec §13.3): loading must always stop, errors must surface. */
enum class ChatListSyncStatus { LOADING, READY, ERROR }

data class ChatListSyncState(
    val status: ChatListSyncStatus = ChatListSyncStatus.LOADING,
    val errorMessage: String? = null,
    /** True when the latest emission came from the local cache/offline snapshot (spec §13.3 cached-vs-live). */
    val fromCache: Boolean = false,
    /**
     * Owning chat for per-chat states (the messages listener). The repository's
     * message sync flow is shared across chats, so consumers compare this
     * against their own chat and ignore stale states stamped for another one
     * (review MINOR-6). Blank for list-level states (chats / sessions / contact lists).
     */
    val chatId: String = ""
)

/**
 * Round 12 (addendum 10b): the RAW message-request state of a chat doc,
 * exactly as stored — unlike [ChatPreview], whose request fields are
 * viewer-relative (the requester sees ACCEPTED so his thread stays in the
 * main list). The chat screen uses this to show the SENDER his pending
 * state («طلب مراسلة — بانتظار قبول الطرف الآخر»).
 */
data class ChatRequestState(
    val requestStatus: String = "",
    val requestFromUid: String = "",
    val isMessageRequest: Boolean = false
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
    val peerVerificationType: String = "BLUE",
    val peerVerificationGlowColor: String = "",
    val peerVerificationGlowEnabled: Boolean = true,
    val peerVerificationGlowIntensity: Float = 0.7f,
    val peerFrameId: String = "",
    val peerFrameUrl: String = "",
    val peerFrameExpiresAt: Long = 0L,
    val peerIconUrl: String = "",
    val peerPhotoRemovedBg: Boolean = false,
    val peerNeonColor: String = "",
    val peerNeonGlowIntensity: Float = 0.7f,
    val peerNeonEnabled: Boolean = false,
    val peerNameplateColor: String = "",
    val peerPhotoUserFramed: Boolean = false,
    val officialWallpaperUrl: String = "",
    val lastMessagePreview: String = "",
    val lastMessageTime: Long = 0L,
    val lastMessageSenderId: String = "",
    val lastMessageStatus: MessageStatus = MessageStatus.SENT,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val isSupportChat: Boolean = false,
    val isOfficialWesalChat: Boolean = false,
    val isMessageRequest: Boolean = false,
    val requestStatus: String = "ACCEPTED",
    val disappearingDurationSec: Long = 0L,
    val deletedForUsers: List<String> = emptyList(),
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
    val joinMode: GroupJoinMode = GroupJoinMode.OPEN,
    val inviteCode: String = "",
    val inviteRevokedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

data class GroupJoinRequest(
    val requestId: String = "",
    val groupId: String = "",
    val requesterUid: String = "",
    val requesterName: String = "",
    val requesterNumericId: String = "",
    val requesterAvatarUrl: String = "",
    val status: String = "PENDING",
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
    val officialWallpaperUrl: String = "",
    val isPublic: Boolean = true,
    val isOfficialTeamChannel: Boolean = false,
    val isSuspended: Boolean = false,
    val inviteCode: String = "",
    val ownerUid: String = "",
    val moderatorIds: List<String> = emptyList(),
    val subscriberIds: List<String> = emptyList(),
    val subscribersCount: Int = 0,
    val isVerified: Boolean = false,
    val verificationType: String = "BLUE",
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
    // FIX 1 (round 5): author appearance snapshot for post headers.
    val authorIconId: String = "",
    val authorIconUrl: String = "",
    val authorVerificationType: String = "BLUE",
    val authorNeonColor: String = "",
    val authorNeonEnabled: Boolean = false,
    val authorNeonGlowIntensity: Float = 0.7f,
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
    // FIX 1 (round 5): author appearance snapshot for comment rows.
    val authorIconId: String = "",
    val authorIconUrl: String = "",
    val authorVerificationType: String = "BLUE",
    val authorNeonColor: String = "",
    val authorNeonEnabled: Boolean = false,
    val authorNeonGlowIntensity: Float = 0.7f,
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
    val ownerVerified: Boolean = false,
    // FIX 1 (round 5): owner appearance snapshot (icon/neon) for story rows
    // and the story viewer header.
    val ownerVerificationType: String = "BLUE",
    val ownerIconId: String = "",
    val ownerIconUrl: String = "",
    val ownerNeonColor: String = "",
    val ownerNeonEnabled: Boolean = false,
    val ownerNeonGlowIntensity: Float = 0.7f,
    // Round 12 (addendum 2): official announcements — published by an
    // admin account, visible to EVERY signed-in user regardless of
    // contacts, pinned above contact stories in the Status tab.
    val isOfficial: Boolean = false,
    val type: StoryType = StoryType.TEXT,
    val textContent: String = "",
    val backgroundColorHex: Long = 0xFF064E3B,
    val mediaUrl: String = "",
    val caption: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1000L,
    val visibility: PrivacyVisibility = PrivacyVisibility.CONTACTS,
    val audienceMode: StoryAudienceMode = StoryAudienceMode.CONTACTS,
    val allowedViewerIds: List<String> = emptyList(),
    val excludedViewerIds: List<String> = emptyList(),
    val viewerIds: List<String> = emptyList()
)

data class StoryViewer(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val viewedAt: Long = 0L,
    // FIX 1 (round 5): viewer appearance snapshot for the viewers list.
    val isVerified: Boolean = false,
    val verificationType: String = "BLUE",
    val iconId: String = "",
    val iconUrl: String = "",
    val neonColor: String = "",
    val neonEnabled: Boolean = false,
    val neonGlowIntensity: Float = 0.7f
)

enum class CallStatus(val raw: String) {
    CONNECTING("CONNECTING"),
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
    val status: CallStatus = CallStatus.CONNECTING,
    val calleeRingingAck: Boolean = false,
    val ringingAt: Long = 0L,
    val offerSdp: String = "",
    val answerSdp: String = "",
    /** Incremented every time the caller signals an ICE-restart offer; the callee re-answers when it changes. */
    val iceRestartGeneration: Long = 0L,
    /** Set by the callee when its ICE dies; the caller watches it and produces the restart offer. */
    val iceRestartRequestedAt: Long = 0L,
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

data class ContactLabelList(
    val listId: String = "",
    val ownerUid: String = "",
    val name: String = "",
    val memberUids: List<String> = emptyList(),
    val isDefaultPreset: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class AppEventItem(
    val eventId: String = "",
    val creatorUid: String = "",
    val creatorName: String = "",
    val title: String = "",
    val description: String = "",
    val locationName: String = "",
    val eventTime: Long = System.currentTimeMillis(),
    val participantUids: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class ScheduledCallRecord(
    val scheduleId: String = "",
    val creatorUid: String = "",
    val peerUid: String = "",
    val peerName: String = "",
    val peerAvatarUrl: String = "",
    val isVideo: Boolean = false,
    val scheduledAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class SupportTicket(
    val ticketId: String = "",
    val referenceNumber: String = "",
    val userUid: String = "",
    val userName: String = "",
    val userNumericId: String = "",
    val userPublicId: String = "",
    val subject: String = "",
    val message: String = "",
    val attachmentUrl: String = "",
    val status: String = "OPEN", // OPEN, IN_PROGRESS, RESOLVED, CLOSED
    val lastReplyText: String = "",
    val lastReplyByUid: String = "",
    val lastReplyAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class SupportTicketMessage(
    val messageId: String = "",
    val ticketId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val isFromAdmin: Boolean = false,
    val text: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class BroadcastList(
    val listId: String = "",
    val ownerUid: String = "",
    val name: String = "",
    val recipientUids: List<String> = emptyList(),
    val lastSentText: String = "",
    val lastSentAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
