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

    const val SUBCOLLECTION_CANDIDATES = "candidates"
    const val SUBCOLLECTION_VIEWERS = "viewers"
    const val SUBCOLLECTION_PRIVATE = "private"
    const val SUBCOLLECTION_POSTS = "posts"
    const val SUBCOLLECTION_COMMENTS = "comments"
    const val SUBCOLLECTION_JOIN_REQUESTS = "join_requests"

    const val STORAGE_AVATARS = "avatars"
    const val STORAGE_CHAT_MEDIA = "chat_media"
    const val STORAGE_VOICE_NOTES = "voice_notes"
    const val STORAGE_FILES = "shared_files"
    const val STORAGE_STORIES = "stories_media"
    const val STORAGE_GROUP_AVATARS = "group_avatars"
    const val STORAGE_CHANNEL_MEDIA = "channel_media"

    const val CHANNEL_MESSAGES = "wesal_messages"
    const val CHANNEL_CALLS = "wesal_calls"
    const val CHANNEL_STORIES = "wesal_stories"
    const val CHANNEL_CHANNELS = "wesal_channels"
    const val CHANNEL_SYSTEM = "wesal_system"

    // Super Admin primary identity; CONFIRMED_SUPER_ADMIN_UID remains empty until verified from live Firebase Auth / inspectFirstAdminCandidate.
    const val SUPER_ADMIN_EMAIL = "eslamfifty40@gmail.com"
    const val CONFIRMED_SUPER_ADMIN_UID = ""

    const val OTP_TIMEOUT_SECONDS = 60L
    const val MESSAGE_EDIT_WINDOW_MS = 15 * 60 * 1000L // 15 minutes edit policy
    const val STORY_EXPIRATION_MS = 24 * 60 * 60 * 1000L // 24 hours
    const val MAX_UPLOAD_SIZE_BYTES = 50L * 1024L * 1024L // 50 MB

    const val DEFAULT_COUNTRY_ISO = "EG"
    const val DEFAULT_COUNTRY_DIAL = "+20"
}
