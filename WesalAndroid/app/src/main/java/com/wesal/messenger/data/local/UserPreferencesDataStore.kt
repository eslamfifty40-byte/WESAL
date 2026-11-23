package com.wesal.messenger.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wesal.messenger.domain.model.SavedWesalAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import android.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wesalDataStore by preferencesDataStore(name = "wesal_settings_prefs")

data class AppUserSettings(
    val darkTheme: Boolean = true,
    val fontScale: Float = 1.0f,
    val languageTag: String = "ar",
    val notificationsMessages: Boolean = true,
    val notificationsGroups: Boolean = true,
    val notificationsCalls: Boolean = true,
    val notificationsStories: Boolean = false,
    val pendingInviteId: String = "",
    val pendingChannelInvite: String = "",
    val recentEmojis: List<String> = emptyList(),
    val welcomeMessageEnabled: Boolean = false,
    val welcomeMessageText: String = DEFAULT_WELCOME_MESSAGE_AR,
    val quickReplies: List<String> = DEFAULT_QUICK_REPLIES,
    val welcomedChatKeys: Set<String> = emptySet(),
    val hiddenChatIds: Set<String> = emptySet(),
    val personalWallpapers: Map<String, String> = emptyMap(),
    val lastStoryAudienceMode: String = "CONTACTS",
    val cachedUid: String = "",
    val cachedProfileComplete: Boolean = false,
    val locationGranted: Boolean = false,
    val locationGraceStartedAt: Long = 0L,
    val appLockEnabled: Boolean = false,
    val twoFactorPinEnabled: Boolean = false,
    val defaultDisappearingSec: Long = 0L,
    val groupsAddPermission: String = "EVERYONE", // EVERYONE, CONTACTS
    val silenceUnknownCalls: Boolean = false,
    val protectIpInCalls: Boolean = false,
    val cameraEffectsEnabled: Boolean = true,
    val archiveKeepMutedChats: Boolean = true,
    val highContrastAccessibility: Boolean = false,
    val securityNotificationsEnabled: Boolean = true,
    val lockedChatIds: Set<String> = emptySet(),
    val archivedStoryIds: Set<String> = emptySet(),
    val starredMessagesJson: String = "[]",
    val lastBackupAt: Long = 0L,
    val customSubTierMonthlyPrice: String = "29 EGP / $0.99",
    val customSubTierYearlyPrice: String = "290 EGP / $9.99"
) {
    companion object {
        const val DEFAULT_WELCOME_MESSAGE_AR = "أهلاً بك في WESAL! يسعدني تواصلك وسأرد عليك في أقرب وقت."
        val DEFAULT_QUICK_REPLIES = listOf(
            "وعليكم السلام ورحمة الله",
            "أهلاً بك، لحظات وسأتواصل معك",
            "تمام، تم الاستلام",
            "شكراً لتواصلك"
        )
    }
}

@Singleton
class UserPreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val encryptedPrefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "wesal_secure_vault",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences("wesal_secure_vault_fallback", Context.MODE_PRIVATE)
        }
    }

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val LANGUAGE = stringPreferencesKey("language_tag")
        val NOTIF_MESSAGES = booleanPreferencesKey("notif_messages")
        val NOTIF_GROUPS = booleanPreferencesKey("notif_groups")
        val NOTIF_CALLS = booleanPreferencesKey("notif_calls")
        val NOTIF_STORIES = booleanPreferencesKey("notif_stories")
        val DEVICE_SESSION_ID = stringPreferencesKey("device_session_id")
        val SAVED_ACCOUNTS_JSON = stringPreferencesKey("saved_accounts_json")
        val PENDING_INVITE_ID = stringPreferencesKey("pending_invite_id")
        val PENDING_CHANNEL_INVITE = stringPreferencesKey("pending_channel_invite")
        val RECENT_EMOJIS_JSON = stringPreferencesKey("recent_emojis_json")
        val WELCOME_ENABLED = booleanPreferencesKey("welcome_message_enabled")
        val WELCOME_TEXT = stringPreferencesKey("welcome_message_text")
        val QUICK_REPLIES_JSON = stringPreferencesKey("quick_replies_json")
        val WELCOMED_CHAT_KEYS_JSON = stringPreferencesKey("welcomed_chat_keys_json")
        val HIDDEN_CHAT_IDS_JSON = stringPreferencesKey("hidden_chat_ids_json")
        val PERSONAL_WALLPAPERS_JSON = stringPreferencesKey("personal_wallpapers_json")
        val LAST_STORY_AUDIENCE = stringPreferencesKey("last_story_audience")
        val CACHED_UID = stringPreferencesKey("cached_uid")
        val CACHED_PROFILE_COMPLETE = booleanPreferencesKey("cached_profile_complete")
        val LOCATION_GRANTED = booleanPreferencesKey("location_granted")
        val LOCATION_GRACE_STARTED_AT = longPreferencesKey("location_grace_started_at")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val TWO_FACTOR_PIN_ENABLED = booleanPreferencesKey("two_factor_pin_enabled")
        val DEFAULT_DISAPPEARING_SEC = longPreferencesKey("default_disappearing_sec")
        val GROUPS_ADD_PERMISSION = stringPreferencesKey("groups_add_permission")
        val SILENCE_UNKNOWN_CALLS = booleanPreferencesKey("silence_unknown_calls")
        val PROTECT_IP_CALLS = booleanPreferencesKey("protect_ip_calls")
        val CAMERA_EFFECTS_ENABLED = booleanPreferencesKey("camera_effects_enabled")
        val ARCHIVE_KEEP_MUTED = booleanPreferencesKey("archive_keep_muted")
        val HIGH_CONTRAST_ACCESSIBILITY = booleanPreferencesKey("high_contrast_accessibility")
        val SECURITY_NOTIFS = booleanPreferencesKey("security_notifs")
        val LOCKED_CHAT_IDS_JSON = stringPreferencesKey("locked_chat_ids_json")
        val ARCHIVED_STORY_IDS_JSON = stringPreferencesKey("archived_story_ids_json")
        val STARRED_MESSAGES_JSON = stringPreferencesKey("starred_messages_json")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val SUB_MONTHLY_PRICE = stringPreferencesKey("sub_monthly_price")
        val SUB_YEARLY_PRICE = stringPreferencesKey("sub_yearly_price")
    }

    val settingsFlow: Flow<AppUserSettings> = context.wesalDataStore.data.map { prefs ->
        val savedReplies = prefs[Keys.QUICK_REPLIES_JSON]
        val replies = if (savedReplies == null) {
            AppUserSettings.DEFAULT_QUICK_REPLIES
        } else {
            decodeStringList(savedReplies)
        }
        AppUserSettings(
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
            languageTag = prefs[Keys.LANGUAGE] ?: "ar",
            notificationsMessages = prefs[Keys.NOTIF_MESSAGES] ?: true,
            notificationsGroups = prefs[Keys.NOTIF_GROUPS] ?: true,
            notificationsCalls = prefs[Keys.NOTIF_CALLS] ?: true,
            notificationsStories = prefs[Keys.NOTIF_STORIES] ?: false,
            pendingInviteId = prefs[Keys.PENDING_INVITE_ID] ?: "",
            pendingChannelInvite = prefs[Keys.PENDING_CHANNEL_INVITE] ?: "",
            recentEmojis = decodeStringList(prefs[Keys.RECENT_EMOJIS_JSON]),
            welcomeMessageEnabled = prefs[Keys.WELCOME_ENABLED] ?: false,
            welcomeMessageText = prefs[Keys.WELCOME_TEXT] ?: AppUserSettings.DEFAULT_WELCOME_MESSAGE_AR,
            quickReplies = replies,
            welcomedChatKeys = decodeStringList(prefs[Keys.WELCOMED_CHAT_KEYS_JSON]).toSet(),
            hiddenChatIds = decodeStringList(prefs[Keys.HIDDEN_CHAT_IDS_JSON]).toSet(),
            personalWallpapers = decodeStringMap(prefs[Keys.PERSONAL_WALLPAPERS_JSON]),
            lastStoryAudienceMode = prefs[Keys.LAST_STORY_AUDIENCE] ?: "CONTACTS",
            cachedUid = prefs[Keys.CACHED_UID] ?: "",
            cachedProfileComplete = prefs[Keys.CACHED_PROFILE_COMPLETE] ?: false,
            locationGranted = prefs[Keys.LOCATION_GRANTED] ?: false,
            locationGraceStartedAt = prefs[Keys.LOCATION_GRACE_STARTED_AT] ?: 0L,
            appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
            twoFactorPinEnabled = prefs[Keys.TWO_FACTOR_PIN_ENABLED] ?: false,
            defaultDisappearingSec = prefs[Keys.DEFAULT_DISAPPEARING_SEC] ?: 0L,
            groupsAddPermission = prefs[Keys.GROUPS_ADD_PERMISSION] ?: "EVERYONE",
            silenceUnknownCalls = prefs[Keys.SILENCE_UNKNOWN_CALLS] ?: false,
            protectIpInCalls = prefs[Keys.PROTECT_IP_CALLS] ?: false,
            cameraEffectsEnabled = prefs[Keys.CAMERA_EFFECTS_ENABLED] ?: true,
            archiveKeepMutedChats = prefs[Keys.ARCHIVE_KEEP_MUTED] ?: true,
            highContrastAccessibility = prefs[Keys.HIGH_CONTRAST_ACCESSIBILITY] ?: false,
            securityNotificationsEnabled = prefs[Keys.SECURITY_NOTIFS] ?: true,
            lockedChatIds = decodeStringList(prefs[Keys.LOCKED_CHAT_IDS_JSON]).toSet(),
            archivedStoryIds = decodeStringList(prefs[Keys.ARCHIVED_STORY_IDS_JSON]).toSet(),
            starredMessagesJson = prefs[Keys.STARRED_MESSAGES_JSON] ?: "[]",
            lastBackupAt = prefs[Keys.LAST_BACKUP_AT] ?: 0L,
            customSubTierMonthlyPrice = prefs[Keys.SUB_MONTHLY_PRICE] ?: "29 EGP / $0.99",
            customSubTierYearlyPrice = prefs[Keys.SUB_YEARLY_PRICE] ?: "290 EGP / $9.99"
        )
    }

    val recentEmojisFlow: Flow<List<String>> = context.wesalDataStore.data.map { prefs ->
        decodeStringList(prefs[Keys.RECENT_EMOJIS_JSON])
    }

    val savedAccountsFlow: Flow<List<SavedWesalAccount>> = context.wesalDataStore.data.map { prefs ->
        decodeSavedAccounts(prefs[Keys.SAVED_ACCOUNTS_JSON])
    }

    private val _hiddenChatsUnlockedSession = MutableStateFlow(false)
    val hiddenChatsUnlockedSession: StateFlow<Boolean> = _hiddenChatsUnlockedSession.asStateFlow()

    private val _openHiddenChatsTrigger = MutableStateFlow(false)
    val openHiddenChatsTrigger: StateFlow<Boolean> = _openHiddenChatsTrigger.asStateFlow()

    fun setHiddenChatsUnlockedSession(unlocked: Boolean) {
        _hiddenChatsUnlockedSession.value = unlocked
    }

    fun requestOpenHiddenChats() {
        _openHiddenChatsTrigger.value = true
    }

    fun consumeOpenHiddenChats() {
        _openHiddenChatsTrigger.value = false
    }

    fun normalizeHiddenPinInput(raw: String): String {
        val trimmed = raw.trim()
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
                in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // Hidden Chats Secure Password Lock (PBKDF2WithHmacSHA256 + random salt in Keystore EncryptedSharedPreferences)
    // -----------------------------------------------------------------------

    fun hasHiddenChatsPassword(uid: String): Boolean {
        if (uid.isBlank()) return false
        return !encryptedPrefs.getString("hc_hash_$uid", null).isNullOrBlank()
    }

    fun getHiddenChatsRecoveryQuestion(uid: String): String {
        return encryptedPrefs.getString("hc_q_$uid", null).orEmpty()
    }

    fun setHiddenChatsPassword(uid: String, password: String, recoveryQuestion: String = "", recoveryAnswer: String = "") {
        val cleanPass = normalizeHiddenPinInput(password)
        if (uid.isBlank() || cleanPass.length < 4) return
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2Hash(cleanPass, salt)
        val editor = encryptedPrefs.edit()
            .putString("hc_salt_$uid", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hc_hash_$uid", hash)
        if (recoveryQuestion.isNotBlank() && recoveryAnswer.isNotBlank()) {
            val qSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val aHash = pbkdf2Hash(normalizeHiddenPinInput(recoveryAnswer).lowercase(), qSalt)
            editor
                .putString("hc_q_$uid", recoveryQuestion.trim())
                .putString("hc_qsalt_$uid", Base64.encodeToString(qSalt, Base64.NO_WRAP))
                .putString("hc_ahash_$uid", aHash)
        }
        editor.commit()
    }

    fun verifyHiddenChatsPassword(uid: String, candidate: String): Boolean {
        val cleanCandidate = normalizeHiddenPinInput(candidate)
        if (uid.isBlank() || cleanCandidate.isBlank()) return false
        val saltB64 = encryptedPrefs.getString("hc_salt_$uid", null) ?: return false
        val expected = encryptedPrefs.getString("hc_hash_$uid", null) ?: return false
        val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val actual = pbkdf2Hash(cleanCandidate, salt)
        return constantTimeEquals(expected, actual)
    }

    fun verifyHiddenChatsRecoveryAnswer(uid: String, answer: String): Boolean {
        val cleanAnswer = normalizeHiddenPinInput(answer).lowercase()
        if (uid.isBlank() || cleanAnswer.isBlank()) return false
        val saltB64 = encryptedPrefs.getString("hc_qsalt_$uid", null) ?: return false
        val expected = encryptedPrefs.getString("hc_ahash_$uid", null) ?: return false
        val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val actual = pbkdf2Hash(cleanAnswer, salt)
        return constantTimeEquals(expected, actual)
    }

    suspend fun clearHiddenChatsProtectionAndUnhideAll(uid: String) {
        if (uid.isNotBlank()) {
            encryptedPrefs.edit()
                .remove("hc_salt_$uid")
                .remove("hc_hash_$uid")
                .remove("hc_q_$uid")
                .remove("hc_qsalt_$uid")
                .remove("hc_ahash_$uid")
                .commit()
        }
        _hiddenChatsUnlockedSession.value = false
        context.wesalDataStore.edit { prefs ->
            prefs[Keys.HIDDEN_CHAT_IDS_JSON] = "[]"
        }
    }

    private fun pbkdf2Hash(input: String, salt: ByteArray): String {
        val spec = PBEKeySpec(input.toCharArray(), salt, 32_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    suspend fun toggleHiddenChat(chatId: String, hidden: Boolean) {
        if (chatId.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val current = decodeStringList(prefs[Keys.HIDDEN_CHAT_IDS_JSON]).toMutableSet()
            if (hidden) current.add(chatId) else current.remove(chatId)
            prefs[Keys.HIDDEN_CHAT_IDS_JSON] = json.encodeToString(current.toList())
        }
    }

    suspend fun setPersonalChatWallpaper(chatId: String, localPathOrUri: String?) {
        if (chatId.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val current = decodeStringMap(prefs[Keys.PERSONAL_WALLPAPERS_JSON]).toMutableMap()
            if (localPathOrUri.isNullOrBlank()) current.remove(chatId) else current[chatId] = localPathOrUri
            prefs[Keys.PERSONAL_WALLPAPERS_JSON] = json.encodeToString(current)
        }
    }

    suspend fun setCachedStartupProfileState(uid: String, complete: Boolean) {
        context.wesalDataStore.edit { prefs ->
            prefs[Keys.CACHED_UID] = uid
            prefs[Keys.CACHED_PROFILE_COMPLETE] = complete
        }
    }

    suspend fun setLocationState(granted: Boolean, graceStartedAt: Long) {
        context.wesalDataStore.edit { prefs ->
            prefs[Keys.LOCATION_GRANTED] = granted
            if (graceStartedAt > 0L) prefs[Keys.LOCATION_GRACE_STARTED_AT] = graceStartedAt
        }
    }

    suspend fun setLastStoryAudienceMode(mode: String) {
        context.wesalDataStore.edit { it[Keys.LAST_STORY_AUDIENCE] = mode }
    }

    suspend fun upsertSavedAccount(account: SavedWesalAccount) {
        if (account.uid.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val current = decodeSavedAccounts(prefs[Keys.SAVED_ACCOUNTS_JSON]).toMutableList()
            val idx = current.indexOfFirst { it.uid == account.uid }
            if (idx >= 0) {
                val existing = current[idx]
                current[idx] = account.copy(
                    displayName = account.displayName.ifBlank { existing.displayName },
                    email = account.email.ifBlank { existing.email },
                    phoneNumber = account.phoneNumber.ifBlank { existing.phoneNumber },
                    publicId = account.publicId.ifBlank { existing.publicId },
                    numericId = account.numericId.ifBlank { existing.numericId },
                    photoUrl = account.photoUrl.ifBlank { existing.photoUrl },
                    provider = account.provider.ifBlank { existing.provider }
                )
            } else {
                current.add(account)
            }
            val sorted = current.sortedByDescending { it.lastUsedAt }
            prefs[Keys.SAVED_ACCOUNTS_JSON] = json.encodeToString(sorted)
        }
    }

    suspend fun removeSavedAccount(uid: String) {
        context.wesalDataStore.edit { prefs ->
            val updated = decodeSavedAccounts(prefs[Keys.SAVED_ACCOUNTS_JSON])
                .filterNot { it.uid == uid }
            prefs[Keys.SAVED_ACCOUNTS_JSON] = json.encodeToString(updated)
        }
    }

    private fun decodeSavedAccounts(raw: String?): List<SavedWesalAccount> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SavedWesalAccount>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<String>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun decodeStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw)
        }.getOrDefault(emptyMap())
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.wesalDataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    suspend fun setFontScale(scale: Float) {
        context.wesalDataStore.edit { it[Keys.FONT_SCALE] = scale.coerceIn(0.85f, 1.35f) }
    }

    suspend fun setLanguage(tag: String) {
        context.wesalDataStore.edit { it[Keys.LANGUAGE] = tag }
    }

    suspend fun setNotificationPreference(
        messages: Boolean? = null,
        groups: Boolean? = null,
        calls: Boolean? = null,
        stories: Boolean? = null
    ) {
        context.wesalDataStore.edit { prefs ->
            messages?.let { prefs[Keys.NOTIF_MESSAGES] = it }
            groups?.let { prefs[Keys.NOTIF_GROUPS] = it }
            calls?.let { prefs[Keys.NOTIF_CALLS] = it }
            stories?.let { prefs[Keys.NOTIF_STORIES] = it }
        }
    }

    suspend fun setWelcomeConfig(enabled: Boolean, text: String) {
        context.wesalDataStore.edit { prefs ->
            prefs[Keys.WELCOME_ENABLED] = enabled
            prefs[Keys.WELCOME_TEXT] = text.trim()
        }
    }

    suspend fun addQuickReply(reply: String) {
        val clean = reply.trim()
        if (clean.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val current = if (prefs[Keys.QUICK_REPLIES_JSON] == null) {
                AppUserSettings.DEFAULT_QUICK_REPLIES.toMutableList()
            } else {
                decodeStringList(prefs[Keys.QUICK_REPLIES_JSON]).toMutableList()
            }
            if (clean !in current) {
                current.add(0, clean)
            }
            prefs[Keys.QUICK_REPLIES_JSON] = json.encodeToString(current.take(25))
        }
    }

    suspend fun removeQuickReply(reply: String) {
        context.wesalDataStore.edit { prefs ->
            val current = if (prefs[Keys.QUICK_REPLIES_JSON] == null) {
                AppUserSettings.DEFAULT_QUICK_REPLIES
            } else {
                decodeStringList(prefs[Keys.QUICK_REPLIES_JSON])
            }
            val updated = current.filterNot { it == reply }
            prefs[Keys.QUICK_REPLIES_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun markChatWelcomed(myUid: String, chatId: String) {
        if (myUid.isBlank() || chatId.isBlank()) return
        val key = "${myUid}_$chatId"
        context.wesalDataStore.edit { prefs ->
            val updated = (decodeStringList(prefs[Keys.WELCOMED_CHAT_KEYS_JSON]) + key)
                .distinct()
                .takeLast(500)
            prefs[Keys.WELCOMED_CHAT_KEYS_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun clearPendingOtp() {
        // Legacy cleanup only (OTP flow disabled per Sec 8)
    }

    suspend fun setPendingInviteId(id: String) {
        val clean = id.trim().removePrefix("@")
        if (clean.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            prefs[Keys.PENDING_INVITE_ID] = clean
        }
    }

    suspend fun clearPendingInviteId() {
        context.wesalDataStore.edit { prefs ->
            prefs.remove(Keys.PENDING_INVITE_ID)
        }
    }

    suspend fun setPendingChannelInvite(code: String) {
        val clean = code.trim()
        if (clean.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            prefs[Keys.PENDING_CHANNEL_INVITE] = clean
        }
    }

    suspend fun clearPendingChannelInvite() {
        context.wesalDataStore.edit { prefs ->
            prefs.remove(Keys.PENDING_CHANNEL_INVITE)
        }
    }

    suspend fun recordRecentEmoji(emoji: String) {
        if (emoji.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val current = decodeStringList(prefs[Keys.RECENT_EMOJIS_JSON])
                .filterNot { it == emoji }
                .toMutableList()
            current.add(0, emoji)
            val trimmed = current.take(36)
            prefs[Keys.RECENT_EMOJIS_JSON] = json.encodeToString(trimmed)
        }
    }

    suspend fun clearUserSessionState() {
        context.wesalDataStore.edit { prefs ->
            prefs.remove(Keys.DEVICE_SESSION_ID)
            prefs.remove(Keys.CACHED_UID)
            prefs.remove(Keys.CACHED_PROFILE_COMPLETE)
        }
    }

    suspend fun getOrCreateDeviceSessionId(): String {
        var id = ""
        context.wesalDataStore.edit { prefs ->
            val existing = prefs[Keys.DEVICE_SESSION_ID]
            if (existing.isNullOrBlank()) {
                id = java.util.UUID.randomUUID().toString()
                prefs[Keys.DEVICE_SESSION_ID] = id
            } else {
                id = existing
            }
        }
        return id
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.wesalDataStore.edit { it[Keys.APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setTwoFactorPin(uid: String, pin: String?) {
        if (uid.isBlank()) return
        if (pin.isNullOrBlank()) {
            encryptedPrefs.edit().remove("2fa_salt_$uid").remove("2fa_hash_$uid").commit()
            context.wesalDataStore.edit { it[Keys.TWO_FACTOR_PIN_ENABLED] = false }
        } else {
            val clean = normalizeHiddenPinInput(pin)
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = pbkdf2Hash(clean, salt)
            encryptedPrefs.edit()
                .putString("2fa_salt_$uid", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("2fa_hash_$uid", hash)
                .commit()
            context.wesalDataStore.edit { it[Keys.TWO_FACTOR_PIN_ENABLED] = true }
        }
    }

    fun verifyTwoFactorPin(uid: String, candidate: String): Boolean {
        val clean = normalizeHiddenPinInput(candidate)
        if (uid.isBlank() || clean.isBlank()) return false
        val saltB64 = encryptedPrefs.getString("2fa_salt_$uid", null) ?: return false
        val expected = encryptedPrefs.getString("2fa_hash_$uid", null) ?: return false
        val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        return constantTimeEquals(expected, pbkdf2Hash(clean, salt))
    }

    suspend fun setDefaultDisappearingSec(seconds: Long) {
        context.wesalDataStore.edit { it[Keys.DEFAULT_DISAPPEARING_SEC] = seconds }
    }

    suspend fun setGroupsAddPermission(mode: String) {
        context.wesalDataStore.edit { it[Keys.GROUPS_ADD_PERMISSION] = mode }
    }

    suspend fun setSilenceUnknownCalls(enabled: Boolean) {
        context.wesalDataStore.edit { it[Keys.SILENCE_UNKNOWN_CALLS] = enabled }
    }

    suspend fun setProtectIpInCalls(enabled: Boolean) {
        context.wesalDataStore.edit { it[Keys.PROTECT_IP_CALLS] = enabled }
    }

    suspend fun setCameraEffectsEnabled(enabled: Boolean) {
        context.wesalDataStore.edit { it[Keys.CAMERA_EFFECTS_ENABLED] = enabled }
    }

    suspend fun setArchiveKeepMutedChats(keep: Boolean) {
        context.wesalDataStore.edit { it[Keys.ARCHIVE_KEEP_MUTED] = keep }
    }

    suspend fun setHighContrastAccessibility(enabled: Boolean) {
        context.wesalDataStore.edit { it[Keys.HIGH_CONTRAST_ACCESSIBILITY] = enabled }
    }

    suspend fun setSecurityNotificationsEnabled(enabled: Boolean) {
        context.wesalDataStore.edit { it[Keys.SECURITY_NOTIFS] = enabled }
    }

    suspend fun toggleLockedChat(chatId: String, locked: Boolean) {
        if (chatId.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val current = decodeStringList(prefs[Keys.LOCKED_CHAT_IDS_JSON]).toMutableSet()
            if (locked) current.add(chatId) else current.remove(chatId)
            prefs[Keys.LOCKED_CHAT_IDS_JSON] = json.encodeToString(current.toList())
        }
    }

    suspend fun toggleArchivedStory(storyId: String, archived: Boolean) {
        if (storyId.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val current = decodeStringList(prefs[Keys.ARCHIVED_STORY_IDS_JSON]).toMutableSet()
            if (archived) current.add(storyId) else current.remove(storyId)
            prefs[Keys.ARCHIVED_STORY_IDS_JSON] = json.encodeToString(current.toList())
        }
    }

    suspend fun toggleStarredMessage(entryKey: String) {
        if (entryKey.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val current = decodeStringList(prefs[Keys.STARRED_MESSAGES_JSON]).toMutableList()
            if (entryKey in current) current.remove(entryKey) else current.add(0, entryKey)
            prefs[Keys.STARRED_MESSAGES_JSON] = json.encodeToString(current.take(200))
        }
    }

    suspend fun recordBackupTimestamp(ts: Long = System.currentTimeMillis()) {
        context.wesalDataStore.edit { it[Keys.LAST_BACKUP_AT] = ts }
    }

    suspend fun setSubscriptionPrices(monthly: String, yearly: String) {
        context.wesalDataStore.edit { prefs ->
            if (monthly.isNotBlank()) prefs[Keys.SUB_MONTHLY_PRICE] = monthly.trim()
            if (yearly.isNotBlank()) prefs[Keys.SUB_YEARLY_PRICE] = yearly.trim()
        }
    }
}
