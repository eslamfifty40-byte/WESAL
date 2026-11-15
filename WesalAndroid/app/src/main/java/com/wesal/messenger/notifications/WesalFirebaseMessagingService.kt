package com.wesal.messenger.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wesal.messenger.MainActivity
import com.wesal.messenger.R
import com.wesal.messenger.calls.IncomingCallActivity
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.data.local.UserPreferencesDataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WesalFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var firestore: FirebaseFirestore
    @Inject lateinit var prefs: UserPreferencesDataStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = auth.currentUser?.uid ?: return
        firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .update("fcmToken", token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"] ?: "MESSAGE"
        if (type != "SYSTEM" && auth.currentUser == null) return

        val title = data["title"] ?: message.notification?.title ?: getString(R.string.app_name)
        val body = data["body"] ?: message.notification?.body.orEmpty()
        val dedupId = (data["messageId"] ?: "${type}_${data["chatId"].orEmpty()}_${body.hashCode()}").hashCode()

        serviceScope.launch {
            val userSettings = prefs.settingsFlow.first()
            when (type) {
                "INCOMING_CALL" -> {
                    if (!userSettings.notificationsCalls) return@launch
                    showIncomingCallNotification(
                        callId = data["callId"].orEmpty(),
                        callerName = data["callerName"] ?: title,
                        callerAvatar = data["callerAvatar"].orEmpty(),
                        isVideo = data["isVideo"] == "true"
                    )
                }
                "GROUP_MESSAGE" -> {
                    if (!userSettings.notificationsGroups) return@launch
                    showStandardNotification(Constants.CHANNEL_MESSAGES, title, body, data["chatId"], dedupId)
                }
                "STORY" -> {
                    if (!userSettings.notificationsStories) return@launch
                    showStandardNotification(Constants.CHANNEL_STORIES, title, body, null, dedupId)
                }
                "SYSTEM" -> {
                    showStandardNotification(Constants.CHANNEL_SYSTEM, title, body, null, dedupId)
                }
                else -> {
                    if (!userSettings.notificationsMessages) return@launch
                    showStandardNotification(Constants.CHANNEL_MESSAGES, title, body, data["chatId"], dedupId)
                }
            }
        }
    }

    private fun showStandardNotification(
        channelId: String,
        title: String,
        body: String,
        chatId: String?,
        notificationId: Int
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!chatId.isNullOrBlank()) putExtra("chatId", chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            chatId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_wesal_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(notificationId, notification)
    }

    private fun showIncomingCallNotification(
        callId: String,
        callerName: String,
        callerAvatar: String,
        isVideo: Boolean
    ) {
        val callIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR, callerAvatar)
            putExtra(IncomingCallActivity.EXTRA_IS_VIDEO, isVideo)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            callId.hashCode(),
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_wesal_notification)
            .setContentTitle(callerName)
            .setContentText(if (isVideo) "مكالمة فيديو واردة" else "مكالمة صوتية واردة")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(callId.hashCode(), notification)
    }
}
