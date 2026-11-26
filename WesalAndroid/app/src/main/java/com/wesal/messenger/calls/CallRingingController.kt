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
                return
            }
        }
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
