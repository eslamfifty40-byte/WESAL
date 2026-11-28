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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peerName = intent?.getStringExtra("peerName") ?: "مكالمة وصال"
        val isVideo = intent?.getBooleanExtra("isVideo", false) == true
        // Round 20 (screen share): when a share is being started, the
        // service re-registers WITH the mediaProjection type — Android
        // 14 requires a foreground service of that type to be running
        // before a MediaProjection may be created.
        val includeMediaProjection =
            intent?.getBooleanExtra(EXTRA_MEDIA_PROJECTION, false) == true
        // Round 20 (call survives leaving the screen): tapping the
        // ongoing-call notification reopens the call UI, which simply
        // re-attaches to the session the CallSessionController owns.
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            CallActivity.createRejoinIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_wesal_notification)
            .setContentTitle("مكالمة جارية • وصال")
            .setContentText(peerName)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

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
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 7001
        const val EXTRA_MEDIA_PROJECTION = "mediaProjection"
    }
}
