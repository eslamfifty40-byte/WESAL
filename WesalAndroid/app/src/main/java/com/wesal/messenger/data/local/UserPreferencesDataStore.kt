package com.wesal.messenger.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wesal.messenger.domain.model.SavedWesalAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    val pendingVerificationId: String = "",
    val pendingPhoneNumber: String = "",
    val pendingOtpTimestamp: Long = 0L,
    val pendingInviteId: String = "",
    val pendingChannelInvite: String = "",
    val recentEmojis: List<String> = emptyList()
)

@Singleton
class UserPreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val LANGUAGE = stringPreferencesKey("language_tag")
        val NOTIF_MESSAGES = booleanPreferencesKey("notif_messages")
        val NOTIF_GROUPS = booleanPreferencesKey("notif_groups")
        val NOTIF_CALLS = booleanPreferencesKey("notif_calls")
        val NOTIF_STORIES = booleanPreferencesKey("notif_stories")
        val PENDING_VERIFICATION_ID = stringPreferencesKey("pending_verification_id")
        val PENDING_PHONE = stringPreferencesKey("pending_phone")
        val PENDING_OTP_TS = longPreferencesKey("pending_otp_ts")
        val DEVICE_SESSION_ID = stringPreferencesKey("device_session_id")
        val SAVED_ACCOUNTS_JSON = stringPreferencesKey("saved_accounts_json")
        val PENDING_INVITE_ID = stringPreferencesKey("pending_invite_id")
        val PENDING_CHANNEL_INVITE = stringPreferencesKey("pending_channel_invite")
        val RECENT_EMOJIS_JSON = stringPreferencesKey("recent_emojis_json")
    }

    val settingsFlow: Flow<AppUserSettings> = context.wesalDataStore.data.map { prefs ->
        AppUserSettings(
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
            languageTag = prefs[Keys.LANGUAGE] ?: "ar",
            notificationsMessages = prefs[Keys.NOTIF_MESSAGES] ?: true,
            notificationsGroups = prefs[Keys.NOTIF_GROUPS] ?: true,
            notificationsCalls = prefs[Keys.NOTIF_CALLS] ?: true,
            notificationsStories = prefs[Keys.NOTIF_STORIES] ?: false,
            pendingVerificationId = prefs[Keys.PENDING_VERIFICATION_ID] ?: "",
            pendingPhoneNumber = prefs[Keys.PENDING_PHONE] ?: "",
            pendingOtpTimestamp = prefs[Keys.PENDING_OTP_TS] ?: 0L,
            pendingInviteId = prefs[Keys.PENDING_INVITE_ID] ?: "",
            pendingChannelInvite = prefs[Keys.PENDING_CHANNEL_INVITE] ?: "",
            recentEmojis = decodeStringList(prefs[Keys.RECENT_EMOJIS_JSON])
        )
    }

    val recentEmojisFlow: Flow<List<String>> = context.wesalDataStore.data.map { prefs ->
        decodeStringList(prefs[Keys.RECENT_EMOJIS_JSON])
    }

    val savedAccountsFlow: Flow<List<SavedWesalAccount>> = context.wesalDataStore.data.map { prefs ->
        decodeSavedAccounts(prefs[Keys.SAVED_ACCOUNTS_JSON])
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

    suspend fun savePendingOtp(verificationId: String, phoneNumber: String) {
        context.wesalDataStore.edit { prefs ->
            prefs[Keys.PENDING_VERIFICATION_ID] = verificationId
            prefs[Keys.PENDING_PHONE] = phoneNumber
            prefs[Keys.PENDING_OTP_TS] = System.currentTimeMillis()
        }
    }

    suspend fun clearPendingOtp() {
        context.wesalDataStore.edit { prefs ->
            prefs.remove(Keys.PENDING_VERIFICATION_ID)
            prefs.remove(Keys.PENDING_PHONE)
            prefs.remove(Keys.PENDING_OTP_TS)
        }
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
            prefs.remove(Keys.PENDING_VERIFICATION_ID)
            prefs.remove(Keys.PENDING_PHONE)
            prefs.remove(Keys.PENDING_OTP_TS)
            prefs.remove(Keys.DEVICE_SESSION_ID)
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
}
