package com.wesal.messenger.calls

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
        val notification = NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_wesal_notification)
            .setContentTitle("مكالمة جارية • وصال")
            .setContentText(peerName)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isVideo) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 7001
    }
}
