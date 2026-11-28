package com.wesal.messenger.calls

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wesal.messenger.R
import com.wesal.messenger.core.util.Constants

class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    // Round 21: the last start parameters, reused when the system
    // restarts the service with a null intent (START_STICKY below) —
    // a live call must never be left without its foreground service
    // (and its microphone type) just because the system killed and
    // restarted the service under background pressure.
    private var lastPeerName: String = "مكالمة وصال"
    private var lastIsVideo: Boolean = false
    private var lastMediaProjection: Boolean = false
    private var lastSharing: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            lastPeerName = intent.getStringExtra("peerName") ?: lastPeerName
            lastIsVideo = intent.getBooleanExtra("isVideo", lastIsVideo)
            lastMediaProjection =
                intent.getBooleanExtra(EXTRA_MEDIA_PROJECTION, lastMediaProjection)
            lastSharing = intent.getBooleanExtra(EXTRA_SHARING, lastSharing)
        }
        val peerName = lastPeerName
        val isVideo = lastIsVideo
        // Round 20 (screen share): when a share is being started, the
        // service re-registers WITH the mediaProjection type — Android
        // 14 requires a foreground service of that type to be running
        // before a MediaProjection may be created.
        val includeMediaProjection = lastMediaProjection
        // Round 21: while a screen share is live, the notification
        // carries an «إيقاف المشاركة» action — the owner must never be
        // trapped in a share he cannot stop just because the call UI
        // is not in front of him (his r20 report: sharing dumped him
        // out of the call screen with no way back in and no way to
        // stop the share). The action broadcasts to CallActionReceiver,
        // which stops the share on the WebRTC manager; the call itself
        // is untouched.
        val isSharing = lastSharing
        // Round 20 (call survives leaving the screen): tapping the
        // ongoing-call notification reopens the call UI, which simply
        // re-attaches to the session the CallSessionController owns.
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            CallActivity.createRejoinIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificationBuilder = NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_wesal_notification)
            .setContentTitle("مكالمة جارية • وصال")
            .setContentText(peerName)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
        if (isSharing) {
            val stopShareIntent = PendingIntent.getBroadcast(
                this,
                STOP_SHARE_REQUEST_CODE,
                Intent(this, CallActionReceiver::class.java).apply {
                    action = CallActionReceiver.ACTION_STOP_SCREEN_SHARE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            notificationBuilder.addAction(
                R.drawable.ic_wesal_notification,
                "إيقاف المشاركة",
                stopShareIntent
            )
        }
        val notification = notificationBuilder.build()

        var fgsType = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fgsType = if (isVideo) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (includeMediaProjection) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        // Round 21: STICKY — if the system kills this service under
        // background pressure during a call, it restarts (with a null
        // intent; the last parameters above are reused) and re-posts
        // the notification with the same types, instead of leaving
        // the live call with no foreground service at all. The
        // controller still stops the service explicitly at terminate.
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 7001
        private const val STOP_SHARE_REQUEST_CODE = 7002
        const val EXTRA_MEDIA_PROJECTION = "mediaProjection"
        const val EXTRA_SHARING = "sharing"
    }
}
