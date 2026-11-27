package com.wesal.messenger.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.auth.FirebaseAuth
import com.wesal.messenger.domain.model.SavedWesalAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wesalDataStore by preferencesDataStore(name = "wesal_settings_prefs")

/**
 * Process-wide lock serializing outbox sends (app-start flush + in-chat retry + fresh sends),
 * so the same pending message can never be in flight twice at once.
 */
object OutboxSendGuard {
    val mutex = kotlinx.coroutines.sync.Mutex()
}

data class AppUserSettings(
    val darkTheme: Boolean = true,
    val fontScale: Float = 1.0f,
    val languageTag: String = "ar",
    val notificationsMessages: Boolean = true,
    val notificationsGroups: Boolean = true,
    val notificationsCalls: Boolean = true,
    val notificationsStories: Boolean = false,
    val notificationToneUri: String = "",
    val callRingtoneUri: String = "",
    val pendingInviteId: String = "",
    val manualInviteId: String = "",
    val pendingChannelInvite: String = "",
    val recentEmojis: List<String> = emptyList(),
    val welcomeMessageEnabled: Boolean = false,
    val welcomeMessageText: String = DEFAULT_WELCOME_MESSAGE_AR,
    val quickReplies: List<String> = DEFAULT_QUICK_REPLIES,
    val welcomedChatKeys: Set<String> = emptySet(),
    val hiddenChatIds: Set<String> = emptySet(),
    val archivedChatIds: Set<String> = emptySet(),
    val personalWallpapers: Map<String, String> = emptyMap(),
    val lastStoryAudienceMode: String = "CONTACTS",
    val cachedUid: String = "",
    val cachedProfileComplete: Boolean = false,
    val locationGranted: Boolean = false,
    val locationGraceStartedAt: Long = 0L,
    val appLockEnabled: Boolean = false,
    val twoFactorPinEnabled: Boolean = false,
    val defaultDisappearingSec: Long = 0L,
    val groupsAddPermission: String = "EVERYONE", // EVERYONE, CONTACTS, NOBODY
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
    val customSubTierYearlyPrice: String = "290 EGP / $9.99",
    val personalAccentPreset: String = "DEFAULT",
    val personalSurfacePreset: String = "DEFAULT"
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

    @Volatile
    private var secureVaultFailed = false

    /**
     * True only while the Keystore-backed EncryptedSharedPreferences is the live
     * store. When vault creation fails, [secureVaultAvailable] is false and the
     * store degrades to process memory only (see below) — callers that persist
     * secrets (hidden-chats password setup) check this and fail closed with a
     * visible error instead of storing anything unencrypted.
     */
    val secureVaultAvailable: Boolean
        get() {
            // Force the lazy store to initialise so a creation failure is reflected.
            runCatching { encryptedPrefs }
            return !secureVaultFailed
        }

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
            // FAIL CLOSED (spec §8.4): the previous fallback persisted the PBKDF2
            // hash + salt to a plain MODE_PRIVATE file ("wesal_secure_vault_fallback").
            // That file is no longer read or written. Instead the vault degrades to
            // a process-memory-only store: nothing sensitive ever touches disk
            // unencrypted, hasHiddenChatsPassword() is false again after any process
            // restart (hidden chats simply stay locked), and the Settings UI
            // surfaces secureVaultAvailable == false when saving a password.
            secureVaultFailed = true
            InMemorySharedPreferences()
        }
    }

    private val _activeUidFlow = MutableStateFlow(
        runCatching { FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }.getOrDefault("")
    )

    init {
        runCatching {
            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                val nextUid = auth.currentUser?.uid.orEmpty()
                if (_activeUidFlow.value != nextUid) {
                    _activeUidFlow.value = nextUid
                    _hiddenChatsUnlockedSession.value = false
                }
            }
        }
    }

    private object GlobalKeys {
        val DEVICE_SESSION_ID = stringPreferencesKey("device_session_id")
        val SAVED_ACCOUNTS_JSON = stringPreferencesKey("saved_accounts_json")
        val PENDING_INVITE_ID = stringPreferencesKey("pending_invite_id")
        val MANUAL_INVITE_ID = stringPreferencesKey("manual_invite_id")
        val PENDING_CHANNEL_INVITE = stringPreferencesKey("pending_channel_invite")
        val CACHED_UID = stringPreferencesKey("cached_uid")
        val CACHED_PROFILE_COMPLETE = booleanPreferencesKey("cached_profile_complete")
        val LOCATION_GRANTED = booleanPreferencesKey("location_granted")
        val LOCATION_GRACE_STARTED_AT = longPreferencesKey("location_grace_started_at")
        val SUB_MONTHLY_PRICE = stringPreferencesKey("sub_monthly_price")
        val SUB_YEARLY_PRICE = stringPreferencesKey("sub_yearly_price")
        val DEVICE_INSTALL_ID = stringPreferencesKey("device_install_id")

        // Legacy un-scoped fallback keys (used only if active UID matches legacy owner and scoped key is unset)
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val LANGUAGE = stringPreferencesKey("language_tag")
    }

    private fun resolveActiveUid(prefs: Preferences? = null): String {
        val authUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
        if (authUid != null) return authUid
        val flowUid = _activeUidFlow.value.takeIf { it.isNotBlank() }
        if (flowUid != null) return flowUid
        val cached = prefs?.get(GlobalKeys.CACHED_UID)?.takeIf { it.isNotBlank() }
        return cached ?: "default"
    }

    private fun boolKey(uid: String, name: String) = booleanPreferencesKey("u_${uid}_$name")
    private fun floatKey(uid: String, name: String) = floatPreferencesKey("u_${uid}_$name")
    private fun longKey(uid: String, name: String) = longPreferencesKey("u_${uid}_$name")
    private fun strKey(uid: String, name: String) = stringPreferencesKey("u_${uid}_$name")

    val settingsFlow: Flow<AppUserSettings> = combine(
        context.wesalDataStore.data,
        _activeUidFlow
    ) { prefs, _ ->
        val uid = resolveActiveUid(prefs)
        val savedReplies = prefs[strKey(uid, "quick_replies_json")]
        val replies = if (savedReplies == null) {
            AppUserSettings.DEFAULT_QUICK_REPLIES
        } else {
            decodeStringList(savedReplies)
        }
        AppUserSettings(
            darkTheme = prefs[boolKey(uid, "dark_theme")] ?: prefs[GlobalKeys.DARK_THEME] ?: true,
            fontScale = prefs[floatKey(uid, "font_scale")] ?: prefs[GlobalKeys.FONT_SCALE] ?: 1.0f,
            languageTag = prefs[strKey(uid, "language_tag")] ?: prefs[GlobalKeys.LANGUAGE] ?: "ar",
            notificationsMessages = prefs[boolKey(uid, "notif_messages")] ?: true,
            notificationsGroups = prefs[boolKey(uid, "notif_groups")] ?: true,
            notificationsCalls = prefs[boolKey(uid, "notif_calls")] ?: true,
            notificationsStories = prefs[boolKey(uid, "notif_stories")] ?: false,
            notificationToneUri = prefs[strKey(uid, "notification_tone_uri")] ?: "",
            callRingtoneUri = prefs[strKey(uid, "call_ringtone_uri")] ?: "",
            pendingInviteId = prefs[GlobalKeys.PENDING_INVITE_ID] ?: "",
            manualInviteId = prefs[GlobalKeys.MANUAL_INVITE_ID] ?: "",
            pendingChannelInvite = prefs[GlobalKeys.PENDING_CHANNEL_INVITE] ?: "",
            recentEmojis = decodeStringList(prefs[strKey(uid, "recent_emojis_json")]),
            welcomeMessageEnabled = prefs[boolKey(uid, "welcome_message_enabled")] ?: false,
            welcomeMessageText = prefs[strKey(uid, "welcome_message_text")] ?: AppUserSettings.DEFAULT_WELCOME_MESSAGE_AR,
            quickReplies = replies,
            welcomedChatKeys = decodeStringList(prefs[strKey(uid, "welcomed_chat_keys_json")]).toSet(),
            hiddenChatIds = decodeStringList(prefs[strKey(uid, "hidden_chat_ids_json")]).toSet(),
            archivedChatIds = decodeStringList(prefs[strKey(uid, "archived_chat_ids_json")]).toSet(),
            personalWallpapers = decodeStringMap(prefs[strKey(uid, "personal_wallpapers_json")]),
            lastStoryAudienceMode = prefs[strKey(uid, "last_story_audience")] ?: "CONTACTS",
            cachedUid = prefs[GlobalKeys.CACHED_UID] ?: "",
            cachedProfileComplete = prefs[GlobalKeys.CACHED_PROFILE_COMPLETE] ?: false,
            locationGranted = prefs[GlobalKeys.LOCATION_GRANTED] ?: false,
            locationGraceStartedAt = prefs[GlobalKeys.LOCATION_GRACE_STARTED_AT] ?: 0L,
            appLockEnabled = prefs[boolKey(uid, "app_lock_enabled")] ?: false,
            twoFactorPinEnabled = prefs[boolKey(uid, "two_factor_pin_enabled")] ?: false,
            defaultDisappearingSec = prefs[longKey(uid, "default_disappearing_sec")] ?: 0L,
            groupsAddPermission = prefs[strKey(uid, "groups_add_permission")] ?: "EVERYONE",
            silenceUnknownCalls = prefs[boolKey(uid, "silence_unknown_calls")] ?: false,
            protectIpInCalls = prefs[boolKey(uid, "protect_ip_calls")] ?: false,
            cameraEffectsEnabled = prefs[boolKey(uid, "camera_effects_enabled")] ?: true,
            archiveKeepMutedChats = prefs[boolKey(uid, "archive_keep_muted")] ?: true,
            highContrastAccessibility = prefs[boolKey(uid, "high_contrast_accessibility")] ?: false,
            securityNotificationsEnabled = prefs[boolKey(uid, "security_notifs")] ?: true,
            lockedChatIds = decodeStringList(prefs[strKey(uid, "locked_chat_ids_json")]).toSet(),
            archivedStoryIds = decodeStringList(prefs[strKey(uid, "archived_story_ids_json")]).toSet(),
            starredMessagesJson = prefs[strKey(uid, "starred_messages_json")] ?: "[]",
            lastBackupAt = prefs[longKey(uid, "last_backup_at")] ?: 0L,
            customSubTierMonthlyPrice = prefs[GlobalKeys.SUB_MONTHLY_PRICE] ?: "29 EGP / $0.99",
            customSubTierYearlyPrice = prefs[GlobalKeys.SUB_YEARLY_PRICE] ?: "290 EGP / $9.99",
            personalAccentPreset = prefs[strKey(uid, "personal_accent_preset")] ?: "DEFAULT",
            personalSurfacePreset = prefs[strKey(uid, "personal_surface_preset")] ?: "DEFAULT"
        )
    }

    val recentEmojisFlow: Flow<List<String>> = combine(
        context.wesalDataStore.data,
        _activeUidFlow
    ) { prefs, _ ->
        val uid = resolveActiveUid(prefs)
        decodeStringList(prefs[strKey(uid, "recent_emojis_json")])
    }

    val savedAccountsFlow: Flow<List<SavedWesalAccount>> = context.wesalDataStore.data.map { prefs ->
        decodeSavedAccounts(prefs[GlobalKeys.SAVED_ACCOUNTS_JSON])
    }

    private val _hiddenChatsUnlockedSession = MutableStateFlow(false)
    val hiddenChatsUnlockedSession: StateFlow<Boolean> = _hiddenChatsUnlockedSession.asStateFlow()

    fun setHiddenChatsUnlockedSession(unlocked: Boolean) {
        _hiddenChatsUnlockedSession.value = unlocked
    }

    // Spec §8.2: there is intentionally NO "open hidden chats" trigger anymore.
    // The only unlock path is typing the password into the chats search box
    // (ChatsListViewModel.updateSearchQuery); the old Settings -> trigger ->
    // unlock-dialog chain was removed with all of its consumers.

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
        val targetUid = uid.ifBlank { resolveActiveUid() }
        context.wesalDataStore.edit { prefs ->
            prefs[strKey(targetUid, "hidden_chat_ids_json")] = "[]"
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
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "hidden_chat_ids_json")
            val current = decodeStringList(prefs[key]).toMutableSet()
            if (hidden) current.add(chatId) else current.remove(chatId)
            prefs[key] = json.encodeToString(current.toList())
        }
    }

    suspend fun toggleArchivedChat(chatId: String, archived: Boolean) {
        if (chatId.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "archived_chat_ids_json")
            val current = decodeStringList(prefs[key]).toMutableSet()
            if (archived) current.add(chatId) else current.remove(chatId)
            prefs[key] = json.encodeToString(current.toList())
        }
    }

    suspend fun setPersonalChatWallpaper(chatId: String, localPathOrUri: String?) {
        if (chatId.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "personal_wallpapers_json")
            val current = decodeStringMap(prefs[key]).toMutableMap()
            if (localPathOrUri.isNullOrBlank()) current.remove(chatId) else current[chatId] = localPathOrUri
            prefs[key] = json.encodeToString(current)
        }
    }

    suspend fun setCachedStartupProfileState(uid: String, complete: Boolean) {
        _activeUidFlow.value = uid
        context.wesalDataStore.edit { prefs ->
            prefs[GlobalKeys.CACHED_UID] = uid
            prefs[GlobalKeys.CACHED_PROFILE_COMPLETE] = complete
        }
    }

    suspend fun setLocationState(granted: Boolean, graceStartedAt: Long) {
        context.wesalDataStore.edit { prefs ->
            prefs[GlobalKeys.LOCATION_GRANTED] = granted
            if (graceStartedAt > 0L) prefs[GlobalKeys.LOCATION_GRACE_STARTED_AT] = graceStartedAt
        }
    }

    suspend fun setLastStoryAudienceMode(mode: String) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[strKey(uid, "last_story_audience")] = mode
        }
    }

    suspend fun upsertSavedAccount(account: SavedWesalAccount) {
        if (account.uid.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val current = decodeSavedAccounts(prefs[GlobalKeys.SAVED_ACCOUNTS_JSON]).toMutableList()
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
            prefs[GlobalKeys.SAVED_ACCOUNTS_JSON] = json.encodeToString(sorted)
        }
    }

    suspend fun removeSavedAccount(uid: String) {
        context.wesalDataStore.edit { prefs ->
            val updated = decodeSavedAccounts(prefs[GlobalKeys.SAVED_ACCOUNTS_JSON])
                .filterNot { it.uid == uid }
            prefs[GlobalKeys.SAVED_ACCOUNTS_JSON] = json.encodeToString(updated)
        }
    }

    private fun decodeSavedAccounts(raw: String?): List<SavedWesalAccount> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SavedWesalAccount>>(raw)
        }.getOrDefault(emptyList())
    }

    // -----------------------------------------------------------------------------------------
    // Pending-message outbox (per account). Written BEFORE a send is attempted and cleared only
    // after the send succeeds, so pending text messages survive process death / app restart.
    // -----------------------------------------------------------------------------------------

    private fun decodeOutbox(raw: String?): List<com.wesal.messenger.domain.model.PendingOutboxMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<com.wesal.messenger.domain.model.PendingOutboxMessage>>(raw)
        }.getOrDefault(emptyList())
    }

    suspend fun enqueueOutboxMessage(entry: com.wesal.messenger.domain.model.PendingOutboxMessage) {
        if (entry.messageId.isBlank() || entry.chatId.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "pending_outbox_json")
            val current = decodeOutbox(prefs[key]).filterNot { it.messageId == entry.messageId }
            prefs[key] = json.encodeToString(current + entry)
        }
    }

    suspend fun removeOutboxMessage(messageId: String) {
        if (messageId.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "pending_outbox_json")
            val current = decodeOutbox(prefs[key])
            val updated = current.filterNot { it.messageId == messageId }
            if (updated.size != current.size) {
                prefs[key] = json.encodeToString(updated)
            }
        }
    }

    suspend fun pendingOutboxMessages(): List<com.wesal.messenger.domain.model.PendingOutboxMessage> {
        val prefs = context.wesalDataStore.data.first()
        val uid = resolveActiveUid(prefs)
        return decodeOutbox(prefs[strKey(uid, "pending_outbox_json")])
    }

    fun outboxFlow(): Flow<List<com.wesal.messenger.domain.model.PendingOutboxMessage>> =
        context.wesalDataStore.data.map { prefs ->
            val uid = resolveActiveUid(prefs)
            decodeOutbox(prefs[strKey(uid, "pending_outbox_json")])
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
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[boolKey(uid, "dark_theme")] = enabled
            prefs[GlobalKeys.DARK_THEME] = enabled
        }
    }

    suspend fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(0.85f, 1.35f)
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[floatKey(uid, "font_scale")] = clamped
            prefs[GlobalKeys.FONT_SCALE] = clamped
        }
    }

    suspend fun setLanguage(tag: String) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[strKey(uid, "language_tag")] = tag
            prefs[GlobalKeys.LANGUAGE] = tag
        }
    }

    suspend fun setNotificationPreference(
        messages: Boolean? = null,
        groups: Boolean? = null,
        calls: Boolean? = null,
        stories: Boolean? = null
    ) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            messages?.let { prefs[boolKey(uid, "notif_messages")] = it }
            groups?.let { prefs[boolKey(uid, "notif_groups")] = it }
            calls?.let { prefs[boolKey(uid, "notif_calls")] = it }
            stories?.let { prefs[boolKey(uid, "notif_stories")] = it }
        }
    }

    suspend fun setNotificationToneUri(uriString: String) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[strKey(uid, "notification_tone_uri")] = uriString.trim()
        }
    }

    suspend fun setCallRingtoneUri(uriString: String) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[strKey(uid, "call_ringtone_uri")] = uriString.trim()
        }
    }

    suspend fun getNotificationToneUriForCurrentAccount(): String {
        val prefs = context.wesalDataStore.data.first()
        val uid = resolveActiveUid(prefs)
        return prefs[strKey(uid, "notification_tone_uri")].orEmpty()
    }

    suspend fun getCallRingtoneUriForCurrentAccount(): String {
        val prefs = context.wesalDataStore.data.first()
        val uid = resolveActiveUid(prefs)
        return prefs[strKey(uid, "call_ringtone_uri")].orEmpty()
    }

    suspend fun setWelcomeConfig(enabled: Boolean, text: String) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[boolKey(uid, "welcome_message_enabled")] = enabled
            prefs[strKey(uid, "welcome_message_text")] = text.trim()
        }
    }

    suspend fun addQuickReply(reply: String) {
        val clean = reply.trim()
        if (clean.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "quick_replies_json")
            val current = if (prefs[key] == null) {
                AppUserSettings.DEFAULT_QUICK_REPLIES.toMutableList()
            } else {
                decodeStringList(prefs[key]).toMutableList()
            }
            if (clean !in current) {
                current.add(0, clean)
            }
            prefs[key] = json.encodeToString(current.take(25))
        }
    }

    suspend fun removeQuickReply(reply: String) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "quick_replies_json")
            val current = if (prefs[key] == null) {
                AppUserSettings.DEFAULT_QUICK_REPLIES
            } else {
                decodeStringList(prefs[key])
            }
            val updated = current.filterNot { it == reply }
            prefs[key] = json.encodeToString(updated)
        }
    }

    suspend fun markChatWelcomed(myUid: String, chatId: String) {
        if (myUid.isBlank() || chatId.isBlank()) return
        val entry = "${myUid}_$chatId"
        context.wesalDataStore.edit { prefs ->
            val uid = myUid.ifBlank { resolveActiveUid(prefs) }
            val key = strKey(uid, "welcomed_chat_keys_json")
            val updated = (decodeStringList(prefs[key]) + entry)
                .distinct()
                .takeLast(500)
            prefs[key] = json.encodeToString(updated)
        }
    }

    suspend fun clearPendingOtp() {
        // Legacy cleanup only (OTP flow disabled per Sec 8)
    }

    suspend fun setPendingInviteId(id: String) {
        val clean = id.trim().removePrefix("@")
        if (clean.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            prefs[GlobalKeys.PENDING_INVITE_ID] = clean
        }
    }

    suspend fun clearPendingInviteId() {
        context.wesalDataStore.edit { prefs ->
            prefs.remove(GlobalKeys.PENDING_INVITE_ID)
        }
    }

    /**
     * The inviter id/number the user typed by hand on the profile-setup screen
     * ("who invited you"). This is the reliable attribution path for sideloaded
     * installs, where a shared link's /join parameter never reaches the app.
     * Persisted before completeUserProfile runs; consumed (and cleared) by
     * AuthRepositoryImpl once the referral stamp lands.
     */
    suspend fun setManualInviteId(id: String) {
        val clean = id.trim().removePrefix("@")
        if (clean.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            prefs[GlobalKeys.MANUAL_INVITE_ID] = clean
        }
    }

    suspend fun clearManualInviteId() {
        context.wesalDataStore.edit { prefs ->
            prefs.remove(GlobalKeys.MANUAL_INVITE_ID)
        }
    }

    suspend fun setPendingChannelInvite(code: String) {
        val clean = code.trim()
        if (clean.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            prefs[GlobalKeys.PENDING_CHANNEL_INVITE] = clean
        }
    }

    suspend fun clearPendingChannelInvite() {
        context.wesalDataStore.edit { prefs ->
            prefs.remove(GlobalKeys.PENDING_CHANNEL_INVITE)
        }
    }

    suspend fun recordRecentEmoji(emoji: String) {
        if (emoji.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "recent_emojis_json")
            val current = decodeStringList(prefs[key])
                .filterNot { it == emoji }
                .toMutableList()
            current.add(0, emoji)
            val trimmed = current.take(36)
            prefs[key] = json.encodeToString(trimmed)
        }
    }

    suspend fun clearUserSessionState() {
        _hiddenChatsUnlockedSession.value = false
        _activeUidFlow.value = ""
        context.wesalDataStore.edit { prefs ->
            // Keep DEVICE_INSTALL_ID & SAVED_ACCOUNTS_JSON intact so re-login/switching reuses the device session
            prefs.remove(GlobalKeys.DEVICE_SESSION_ID)
            prefs.remove(GlobalKeys.CACHED_UID)
            prefs.remove(GlobalKeys.CACHED_PROFILE_COMPLETE)
        }
    }

    suspend fun getOrCreateDeviceSessionId(): String {
        var id = ""
        context.wesalDataStore.edit { prefs ->
            val existingInstallId = prefs[GlobalKeys.DEVICE_INSTALL_ID]
                ?: prefs[GlobalKeys.DEVICE_SESSION_ID]?.takeIf { it.isNotBlank() }
            if (existingInstallId.isNullOrBlank()) {
                val androidIdRaw = runCatching {
                    android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    )
                }.getOrNull().orEmpty()
                id = if (androidIdRaw.isNotBlank()) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest("wesal_dev_$androidIdRaw".toByteArray(Charsets.UTF_8))
                    "dev_" + digest.joinToString("") { "%02x".format(it) }.take(24)
                } else {
                    "dev_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)
                }
            } else {
                id = existingInstallId
            }
            prefs[GlobalKeys.DEVICE_INSTALL_ID] = id
            prefs[GlobalKeys.DEVICE_SESSION_ID] = id
        }
        return id
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[boolKey(uid, "app_lock_enabled")] = enabled
        }
    }

    suspend fun setTwoFactorPin(uid: String, pin: String?) {
        if (uid.isBlank()) return
        if (pin.isNullOrBlank()) {
            encryptedPrefs.edit().remove("2fa_salt_$uid").remove("2fa_hash_$uid").commit()
            context.wesalDataStore.edit { it[boolKey(uid, "two_factor_pin_enabled")] = false }
        } else {
            val clean = normalizeHiddenPinInput(pin)
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = pbkdf2Hash(clean, salt)
            encryptedPrefs.edit()
                .putString("2fa_salt_$uid", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("2fa_hash_$uid", hash)
                .commit()
            context.wesalDataStore.edit { it[boolKey(uid, "two_factor_pin_enabled")] = true }
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
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[longKey(uid, "default_disappearing_sec")] = seconds
        }
    }

    suspend fun setGroupsAddPermission(mode: String) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[strKey(uid, "groups_add_permission")] = mode
        }
    }

    suspend fun setSilenceUnknownCalls(enabled: Boolean) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[boolKey(uid, "silence_unknown_calls")] = enabled
        }
    }

    suspend fun setProtectIpInCalls(enabled: Boolean) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[boolKey(uid, "protect_ip_calls")] = enabled
        }
    }

    suspend fun setCameraEffectsEnabled(enabled: Boolean) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[boolKey(uid, "camera_effects_enabled")] = enabled
        }
    }

    suspend fun setArchiveKeepMutedChats(keep: Boolean) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[boolKey(uid, "archive_keep_muted")] = keep
        }
    }

    suspend fun setHighContrastAccessibility(enabled: Boolean) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[boolKey(uid, "high_contrast_accessibility")] = enabled
        }
    }

    suspend fun setSecurityNotificationsEnabled(enabled: Boolean) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[boolKey(uid, "security_notifs")] = enabled
        }
    }

    suspend fun toggleLockedChat(chatId: String, locked: Boolean) {
        if (chatId.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "locked_chat_ids_json")
            val current = decodeStringList(prefs[key]).toMutableSet()
            if (locked) current.add(chatId) else current.remove(chatId)
            prefs[key] = json.encodeToString(current.toList())
        }
    }

    suspend fun toggleArchivedStory(storyId: String, archived: Boolean) {
        if (storyId.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "archived_story_ids_json")
            val current = decodeStringList(prefs[key]).toMutableSet()
            if (archived) current.add(storyId) else current.remove(storyId)
            prefs[key] = json.encodeToString(current.toList())
        }
    }

    suspend fun toggleStarredMessage(entryKey: String) {
        if (entryKey.isBlank()) return
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            val key = strKey(uid, "starred_messages_json")
            val current = decodeStringList(prefs[key]).toMutableList()
            if (entryKey in current) current.remove(entryKey) else current.add(0, entryKey)
            prefs[key] = json.encodeToString(current.take(200))
        }
    }

    suspend fun recordBackupTimestamp(ts: Long = System.currentTimeMillis()) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[longKey(uid, "last_backup_at")] = ts
        }
    }

    suspend fun setSubscriptionPrices(monthly: String, yearly: String) {
        context.wesalDataStore.edit { prefs ->
            if (monthly.isNotBlank()) prefs[GlobalKeys.SUB_MONTHLY_PRICE] = monthly.trim()
            if (yearly.isNotBlank()) prefs[GlobalKeys.SUB_YEARLY_PRICE] = yearly.trim()
        }
    }

    suspend fun setPersonalAccentPreset(presetKey: String) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[strKey(uid, "personal_accent_preset")] = presetKey.ifBlank { "DEFAULT" }
        }
    }

    suspend fun setPersonalSurfacePreset(presetKey: String) {
        context.wesalDataStore.edit { prefs ->
            val uid = resolveActiveUid(prefs)
            prefs[strKey(uid, "personal_surface_preset")] = presetKey.ifBlank { "DEFAULT" }
        }
    }
}

/**
 * Process-memory-only [SharedPreferences] used ONLY as the fail-closed degrade
 * path when the Keystore-backed encrypted vault cannot be created (spec §8.4).
 * Values live in a plain in-process map and vanish with the process — nothing is
 * ever written to disk unencrypted. Change listeners are not supported (no
 * caller of the vault relies on them).
 */
private class InMemorySharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = map.toMap()
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (map[key] as? Set<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { pending[key] = value; removals.remove(key) }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor =
            apply { pending[key] = values?.toSet(); removals.remove(key) }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { pending[key] = value; removals.remove(key) }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { pending[key] = value; removals.remove(key) }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { pending[key] = value; removals.remove(key) }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { pending[key] = value; removals.remove(key) }

        override fun remove(key: String): SharedPreferences.Editor =
            apply { removals.add(key); pending.remove(key) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) map.clear()
            removals.forEach { map.remove(it) }
            pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }
}
