package com.wesal.messenger.core.util

object Constants {
    const val COLLECTION_USERS = "users"
    const val COLLECTION_CHATS = "chats"
    const val COLLECTION_MESSAGES = "messages"
    const val COLLECTION_GROUPS = "groups"
    const val COLLECTION_STORIES = "stories"
    const val COLLECTION_CALLS = "calls"
    const val COLLECTION_REPORTS = "reports"
    const val COLLECTION_SESSIONS = "sessions"
    const val COLLECTION_SUPER_ADMINS = "super_admins"
    const val COLLECTION_VERIFICATION_LOGS = "verification_audit_logs"
    const val COLLECTION_CONTACT_REQUESTS = "contact_requests"
    const val COLLECTION_CHANNELS = "channels"
    const val COLLECTION_WESAL_ID_INDEX = "wesal_id_index"

    const val SUBCOLLECTION_CANDIDATES = "candidates"
    const val SUBCOLLECTION_VIEWERS = "viewers"
    const val SUBCOLLECTION_PRIVATE = "private"
    const val SUBCOLLECTION_POSTS = "posts"
    const val SUBCOLLECTION_COMMENTS = "comments"
    const val SUBCOLLECTION_JOIN_REQUESTS = "join_requests"
    const val SUBCOLLECTION_PROFILE_VISITORS = "profile_visitors"

    // Collections matching unified WESAL specification (Sec 7, 13, 14, 15, 26, 27, 41, 42, 44, 50-53)
    const val COLLECTION_BANNED_IDENTIFIERS = "banned_identifiers"
    const val COLLECTION_HIDDEN_LOGS = "hidden_audit_logs"
    const val COLLECTION_ADMIN_LOGS = "admin_audit_logs"
    const val COLLECTION_OFFICIAL_ANNOUNCEMENTS = "official_announcements"
    const val COLLECTION_SYSTEM_CONFIG = "system_config"
    const val COLLECTION_PRESENCE = "presence"
    const val COLLECTION_PHONE_UNIQUENESS = "phone_uniqueness"
    const val COLLECTION_MODERATORS = "moderators"
    const val COLLECTION_FRAME_LIBRARY = "frame_library"

    const val CHANNEL_MESSAGES = "wesal_messages"
    const val CHANNEL_CALLS = "wesal_calls"
    const val CHANNEL_STORIES = "wesal_stories"
    const val CHANNEL_CHANNELS = "wesal_channels"
    const val CHANNEL_SYSTEM = "wesal_system"

    // Approved Super Admin & Support identities
    const val SUPER_ADMIN_EMAIL = "eslamfifty40@gmail.com"
    const val SECONDARY_ADMIN_EMAIL = "tbtbttbbt7@gmail.com"
    const val APK_INVITE_URL = "https://tinyurl.com/wesal-Messenger"
    val SUPER_ADMIN_EMAILS = listOf("eslamfifty40@gmail.com", "tbtbttbbt7@gmail.com")
    val CONFIRMED_SUPER_ADMIN_UIDS = listOf("fxKvKnbnksPqZy07rQjTzxIKzzP2", "D6Z812za9ZNUW0kAt7SaFD3a9UN2")
    const val CONFIRMED_SUPER_ADMIN_UID = "" // Optional hard lock once deployed
    const val OWNER_ADMIN_NUMERIC_ID = "10001"
    const val SECONDARY_ADMIN_NUMERIC_ID = "10002"
    const val OFFICIAL_WESAL_TEAM_CHANNEL_ID = "wesal_official_team_channel"
    val ADMIN_NUMERIC_IDS: Set<String> = setOf("10001", "10002")
    const val OFFICIAL_CHAT_PREFIX = "wesal_official_"
    const val OFFICIAL_WESAL_SENDER_ID = "WESAL_SYSTEM"
    const val OFFICIAL_WESAL_SENDER_NAME = "WESAL Messenger"

    // Official approved invite URL
    const val APK_DOWNLOAD_URL = "https://tinyurl.com/wesal-Messenger"

    const val MESSAGE_EDIT_WINDOW_MS = 15 * 60 * 1000L // 15 minutes edit policy
    const val STORY_EXPIRATION_MS = 24 * 60 * 60 * 1000L // 24 hours
    const val PRESENCE_HEARTBEAT_INTERVAL_MS = 25_000L
    const val PRESENCE_ONLINE_TTL_MS = 75_000L
    const val LOCATION_GRACE_PERIOD_MS = 48L * 60L * 60L * 1000L
    const val MIN_AGE_YEARS = 13

    const val MAX_UPLOAD_SIZE_BYTES = 50L * 1024L * 1024L // 50 MB
    // Safe fallback limit ONLY for small cropped profile avatars when Storage bucket is unavailable on Spark
    const val MAX_INLINE_IMAGE_BYTES = 350_000L

    const val DEFAULT_COUNTRY_ISO = "EG"
    const val DEFAULT_COUNTRY_DIAL = "+20"

    fun isSuperAdminEmail(email: String?): Boolean {
        val clean = email?.trim().orEmpty()
        if (clean.isBlank()) return false
        return SUPER_ADMIN_EMAILS.any { it.equals(clean, ignoreCase = true) }
    }

    fun isSuperAdminUid(uid: String?): Boolean {
        val clean = uid?.trim().orEmpty()
        if (clean.isBlank()) return false
        return CONFIRMED_SUPER_ADMIN_UIDS.contains(clean)
    }

    fun isProtectedAdminNumericId(numericId: String?): Boolean {
        val clean = numericId?.trim().orEmpty()
        return clean == OWNER_ADMIN_NUMERIC_ID || clean == SECONDARY_ADMIN_NUMERIC_ID
    }
}
