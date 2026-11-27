package com.wesal.messenger.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.wesal.messenger.R
import com.wesal.messenger.core.util.Constants

/**
 * Notification-channel ids for user-chosen tones.
 *
 * A notification channel's sound is immutable once the channel exists, so a custom
 * tone can never be applied to the base channel — every distinct tone URI gets its
 * own channel whose id is derived from the URI. The id formula here is byte-for-byte
 * the one [WesalFirebaseMessagingService] has always used (`<base>_tone_<hashCode>`),
 * so the push path and the listener-service path share the very same channels
 * instead of creating duplicates with different ids.
 *
 * The two `*ChannelId` functions are pure (no Android framework calls beyond reading
 * the compile-time [Constants]) and are covered by unit tests.
 */
object ToneChannels {

    /** Channel id for message notifications when [toneUri] is the chosen tone ("" = default). */
    fun messageChannelId(toneUri: String): String {
        val trimmed = toneUri.trim()
        if (trimmed.isBlank()) return Constants.CHANNEL_MESSAGES
        return "${Constants.CHANNEL_MESSAGES}_tone_${trimmed.hashCode()}"
    }

    /** Channel id for the audible incoming-call fallback when [ringtoneUri] is chosen ("" = default). */
    fun audibleCallChannelId(ringtoneUri: String): String {
        val trimmed = ringtoneUri.trim()
        if (trimmed.isBlank()) return Constants.CHANNEL_CALLS_AUDIBLE
        return "${Constants.CHANNEL_CALLS_AUDIBLE}_tone_${trimmed.hashCode()}"
    }

    /**
     * Returns the channel id to post message notifications on, creating the per-tone
     * channel first if needed. Falls back to the base channel whenever a custom
     * channel cannot be created (pre-O device, unparseable URI, no manager).
     */
    fun ensureMessageChannel(context: Context, toneUri: String): String {
        val channelId = messageChannelId(toneUri)
        if (channelId == Constants.CHANNEL_MESSAGES) return channelId
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return Constants.CHANNEL_MESSAGES
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return Constants.CHANNEL_MESSAGES
        if (manager.getNotificationChannel(channelId) == null) {
            val parsedUri = runCatching { Uri.parse(toneUri.trim()) }.getOrNull()
                ?: return Constants.CHANNEL_MESSAGES
            val channel = NotificationChannel(
                channelId,
                context.getString(R.string.channel_messages_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                lightColor = 0xFF00E5FF.toInt()
                enableVibration(true)
                setSound(
                    parsedUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            runCatching { manager.createNotificationChannel(channel) }
        }
        return channelId
    }

    /**
     * Returns the channel id for the audible incoming-call fallback (used only when
     * the full-screen intent cannot fire), creating the per-ringtone channel first
     * if needed. Mirrors the attributes of the FCM service's audible call channel.
     */
    fun ensureAudibleCallChannel(context: Context, ringtoneUri: String): String {
        val channelId = audibleCallChannelId(ringtoneUri)
        if (channelId == Constants.CHANNEL_CALLS_AUDIBLE) return channelId
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return Constants.CHANNEL_CALLS_AUDIBLE
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return Constants.CHANNEL_CALLS_AUDIBLE
        if (manager.getNotificationChannel(channelId) == null) {
            val parsedUri = runCatching { Uri.parse(ringtoneUri.trim()) }.getOrNull()
                ?: return Constants.CHANNEL_CALLS_AUDIBLE
            val channel = NotificationChannel(
                channelId,
                context.getString(R.string.channel_calls_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                lightColor = 0xFF00C853.toInt()
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 600L, 400L, 600L, 1000L)
                setSound(
                    parsedUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            runCatching { manager.createNotificationChannel(channel) }
        }
        return channelId
    }
}
