package com.wesal.messenger.core.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.wesal.messenger.R

/**
 * Per-account notification tone and ringtone choice, kept in plain SharedPreferences (no secrets).
 * Value "" means silent, missing means the phone default.
 */
object WesalTones {
    private const val PREFS = "wesal_tones"

    private fun uid(): String = FirebaseAuth.getInstance().currentUser?.uid ?: "anon"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasCustom(ctx: Context, call: Boolean): Boolean = p(ctx).contains(key(call))
    private fun key(call: Boolean) = (if (call) "ring_" else "notif_") + uid()

    fun set(ctx: Context, call: Boolean, uri: Uri?) {
        p(ctx).edit().putString(key(call), uri?.toString() ?: "").apply()
    }

    fun reset(ctx: Context, call: Boolean) {
        p(ctx).edit().remove(key(call)).apply()
    }

    /** The URI to play, or null for silent. */
    fun resolve(ctx: Context, call: Boolean): Uri? {
        val raw = p(ctx).getString(key(call), null)
        return when {
            raw == null -> RingtoneManager.getDefaultUri(
                if (call) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION
            )
            raw.isEmpty() -> null
            else -> Uri.parse(raw)
        }
    }

    fun title(ctx: Context, call: Boolean): String {
        val raw = p(ctx).getString(key(call), null) ?: return "الافتراضي"
        if (raw.isEmpty()) return "صامت"
        return runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(raw))?.getTitle(ctx) }.getOrNull() ?: "مخصص"
    }

    fun preview(ctx: Context, uri: Uri?): Ringtone? {
        if (uri == null) return null
        return runCatching { RingtoneManager.getRingtone(ctx, uri)?.also { it.play() } }.getOrNull()
    }

    /** Notification channel sound cannot change after creation, so each tone gets its own channel id. */
    fun channelId(ctx: Context, call: Boolean): String {
        val base = if (call) Constants.CHANNEL_CALLS else Constants.CHANNEL_MESSAGES
        if (!hasCustom(ctx, call)) return base
        val uri = resolve(ctx, call)
        val id = base + "_t" + (uri?.toString() ?: "silent").hashCode().toUInt().toString(16)
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return base
        if (mgr.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(
                id,
                ctx.getString(if (call) R.string.channel_calls_name else R.string.channel_messages_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setSound(
                    uri,
                    AudioAttributes.Builder()
                        .setUsage(
                            if (call) AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                            else AudioAttributes.USAGE_NOTIFICATION
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            mgr.createNotificationChannel(ch)
        }
        return id
    }
}
