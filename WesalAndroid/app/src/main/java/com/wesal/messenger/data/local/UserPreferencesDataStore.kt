package com.wesal.messenger.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    val pendingOtpTimestamp: Long = 0L
)

@Singleton
class UserPreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
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
            pendingOtpTimestamp = prefs[Keys.PENDING_OTP_TS] ?: 0L
        )
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
