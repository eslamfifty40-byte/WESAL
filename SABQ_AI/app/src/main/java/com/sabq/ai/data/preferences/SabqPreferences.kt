package com.sabq.ai.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sabqDataStore: DataStore<Preferences> by preferencesDataStore(name = "sabq_settings")

data class SabqSettingsState(
    val activeUserId: String = "user_primary",
    val voiceEnabled: Boolean = true,
    val voiceVolume: Float = 0.85f,
    val darkMode: Boolean = true,
    val fontScale: Float = 1.0f,
    val notificationsEnabled: Boolean = true,
    val quietHoursStartHour: Int = 22,
    val quietHoursEndHour: Int = 7,
    val cameraAllowed: Boolean = true,
    val micAllowed: Boolean = true,
    val biometricLockEnabled: Boolean = false
)

class SabqPreferences(private val context: Context) {
    private object Keys {
        val ACTIVE_USER_ID = stringPreferencesKey("active_user_id")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val VOICE_VOLUME = floatPreferencesKey("voice_volume")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val QUIET_START = intPreferencesKey("quiet_hours_start")
        val QUIET_END = intPreferencesKey("quiet_hours_end")
        val CAMERA_ALLOWED = booleanPreferencesKey("camera_allowed")
        val MIC_ALLOWED = booleanPreferencesKey("mic_allowed")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
    }

    val settingsFlow: Flow<SabqSettingsState> = context.sabqDataStore.data.map { prefs ->
        SabqSettingsState(
            activeUserId = prefs[Keys.ACTIVE_USER_ID] ?: "user_primary",
            voiceEnabled = prefs[Keys.VOICE_ENABLED] ?: true,
            voiceVolume = prefs[Keys.VOICE_VOLUME] ?: 0.85f,
            darkMode = prefs[Keys.DARK_MODE] ?: true,
            fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            quietHoursStartHour = prefs[Keys.QUIET_START] ?: 22,
            quietHoursEndHour = prefs[Keys.QUIET_END] ?: 7,
            cameraAllowed = prefs[Keys.CAMERA_ALLOWED] ?: true,
            micAllowed = prefs[Keys.MIC_ALLOWED] ?: true,
            biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK] ?: false
        )
    }

    suspend fun updateSettings(transform: (MutablePreferences) -> Unit) {
        context.sabqDataStore.edit(transform)
    }

    suspend fun setActiveUserId(userId: String) = updateSettings { it[Keys.ACTIVE_USER_ID] = userId }
    suspend fun setVoiceEnabled(enabled: Boolean) = updateSettings { it[Keys.VOICE_ENABLED] = enabled }
    suspend fun setVoiceVolume(volume: Float) = updateSettings { it[Keys.VOICE_VOLUME] = volume.coerceIn(0f, 1f) }
    suspend fun setFontScale(scale: Float) = updateSettings { it[Keys.FONT_SCALE] = scale.coerceIn(0.85f, 1.35f) }
    suspend fun setNotificationsEnabled(enabled: Boolean) = updateSettings { it[Keys.NOTIFICATIONS] = enabled }
    suspend fun setCameraAllowed(allowed: Boolean) = updateSettings { it[Keys.CAMERA_ALLOWED] = allowed }
    suspend fun setMicAllowed(allowed: Boolean) = updateSettings { it[Keys.MIC_ALLOWED] = allowed }
    suspend fun setBiometricLock(enabled: Boolean) = updateSettings { it[Keys.BIOMETRIC_LOCK] = enabled }
}
