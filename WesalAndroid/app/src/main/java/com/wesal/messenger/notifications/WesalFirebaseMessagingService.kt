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
import com.wesal.messenger.calls.CallActionReceiver
import com.wesal.messenger.calls.CallActivity
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

        val rawTitle = data["title"] ?: message.notification?.title ?: getString(R.string.app_name)
        val rawBody = data["body"] ?: message.notification?.body.orEmpty()
        val chatId = data["chatId"]
        val dedupId = (data["messageId"] ?: "${type}_${chatId.orEmpty()}_${rawBody.hashCode()}").hashCode()

        serviceScope.launch {
            val userSettings = prefs.settingsFlow.first()
            val isHiddenChat = !chatId.isNullOrBlank() && userSettings.hiddenChatIds.contains(chatId)
            val title = if (isHiddenChat) getString(R.string.app_name) else rawTitle
            val body = if (isHiddenChat) "لديك رسالة جديدة" else rawBody

            when (type) {
                "INCOMING_CALL" -> {
                    if (!userSettings.notificationsCalls) return@launch
                    showIncomingCallNotification(
                        callId = data["callId"].orEmpty(),
                        callerUid = data["callerId"].orEmpty(),
                        callerName = data["callerName"] ?: title,
                        callerAvatar = data["callerAvatar"].orEmpty(),
                        callerVerified = data["callerVerified"] == "true",
                        isVideo = data["isVideo"] == "true"
                    )
                }
                "GROUP_MESSAGE" -> {
                    if (!userSettings.notificationsGroups) return@launch
                    showStandardNotification(
                        channelId = com.wesal.messenger.core.util.WesalTones.channelId(this@WesalFirebaseMessagingService, false),
                        title = title,
                        body = body,
                        chatId = if (isHiddenChat) null else chatId,
                        wesalChannelId = null,
                        notificationId = dedupId
                    )
                }
                "STORY" -> {
                    if (!userSettings.notificationsStories) return@launch
                    showStandardNotification(Constants.CHANNEL_STORIES, title, body, null, null, dedupId)
                }
                "CHANNEL_POST" -> {
                    if (!userSettings.notificationsMessages) return@launch
                    showStandardNotification(Constants.CHANNEL_CHANNELS, title, body, null, data["channelId"], dedupId)
                }
                "SYSTEM" -> {
                    showStandardNotification(Constants.CHANNEL_SYSTEM, rawTitle, rawBody, null, null, dedupId)
                }
                else -> {
                    if (!userSettings.notificationsMessages) return@launch
                    showStandardNotification(
                        channelId = com.wesal.messenger.core.util.WesalTones.channelId(this@WesalFirebaseMessagingService, false),
                        title = title,
                        body = body,
                        chatId = if (isHiddenChat) null else chatId,
                        wesalChannelId = null,
                        notificationId = dedupId
                    )
                }
            }
        }
    }

    private fun showStandardNotification(
        channelId: String,
        title: String,
        body: String,
        chatId: String?,
        wesalChannelId: String?,
        notificationId: Int
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!chatId.isNullOrBlank()) putExtra("chatId", chatId)
            if (!wesalChannelId.isNullOrBlank()) putExtra("channelId", wesalChannelId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            (chatId ?: wesalChannelId)?.hashCode() ?: 0,
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
        callerUid: String,
        callerName: String,
        callerAvatar: String,
        callerVerified: Boolean,
        isVideo: Boolean
    ) {
        val notifId = callId.hashCode()
        val callIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_UID, callerUid)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR, callerAvatar)
            putExtra(IncomingCallActivity.EXTRA_CALLER_VERIFIED, callerVerified)
            putExtra(IncomingCallActivity.EXTRA_IS_VIDEO, isVideo)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = CallActivity.createIncomingAnswerIntent(
            context = this,
            callId = callId,
            peerName = callerName,
            peerAvatar = callerAvatar,
            isVideo = isVideo,
            peerVerified = callerVerified,
            peerUid = callerUid
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val answerPendingIntent = PendingIntent.getActivity(
            this,
            notifId + 1,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_REJECT_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            this,
            notifId + 2,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, com.wesal.messenger.core.util.WesalTones.channelId(this, true))
            .setSmallIcon(R.drawable.ic_wesal_notification)
            .setContentTitle(callerName)
            .setContentText(if (isVideo) "مكالمة فيديو واردة" else "مكالمة صوتية واردة")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "رفض", rejectPendingIntent)
            .addAction(android.R.drawable.ic_menu_call, "رد", answerPendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(notifId, notification)
    }
}
