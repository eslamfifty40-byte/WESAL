package com.wesal.messenger.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Loops the incoming-call ringtone starting from the push handler itself.
 *
 * Why here and not in IncomingCallActivity: full-screen intents are NOT guaranteed
 * to launch the activity (battery/OEM limits), and the incoming-call notification
 * channel is intentionally sound-less so the tone never plays twice. Starting the
 * loop from the FCM handler is the only path that rings reliably when the app is
 * in the background or killed. Answer/reject/end paths stop the loop via [stop].
 */
object CallRingingController {

    private const val MAX_RING_MS = 60_000L

    @Volatile private var ringtone: Ringtone? = null
    @Volatile private var activeCallId: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    @Synchronized
    fun start(context: Context, callId: String, savedToneUri: String) {
        stop()
        activeCallId = callId
        val appContext = context.applicationContext
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val ch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.getNotificationChannel("wesal_calls_ring_v3")?.importance ?: -1
            } else -2
            com.wesal.messenger.chat.WesalDiag.ringLog(
                "ring start ${callId.take(6)} ringer=${am.ringerMode} vol=${am.getStreamVolume(android.media.AudioManager.STREAM_RING)}/${am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)} dnd=${nm.currentInterruptionFilter} ch=$ch batt=${pm.isIgnoringBatteryOptimizations(appContext.packageName)}"
            )
        }
        val candidates = buildList {
            savedToneUri.trim().takeIf { it.isNotBlank() }?.let { add(it) }
            add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE).toString())
            add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString())
        }
        for (raw in candidates.distinct()) {
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: continue
            val tone = runCatching { RingtoneManager.getRingtone(appContext, uri) }.getOrNull() ?: continue
            val started = runCatching {
                tone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    tone.isLooping = true
                }
                tone.play()
                true
            }.getOrDefault(false)
            if (started) {
                ringtone = tone
                handler.postDelayed(autoStop, MAX_RING_MS)
                com.wesal.messenger.chat.WesalDiag.ringLog("ring PLAYING ${callId.take(6)}")
                return
            }
        }
        com.wesal.messenger.chat.WesalDiag.ringLog("ring FAILED: no playable tone")
    }

    @Synchronized
    fun stop(expectedCallId: String? = null) {
        if (expectedCallId != null && activeCallId.isNotBlank() && expectedCallId != activeCallId) return
        handler.removeCallbacks(autoStop)
        runCatching { ringtone?.stop() }
        ringtone = null
        activeCallId = ""
    }
}
