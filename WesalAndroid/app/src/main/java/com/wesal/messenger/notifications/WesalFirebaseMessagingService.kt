package com.wesal.messenger.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
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
            val isArchivedChat = !chatId.isNullOrBlank() && userSettings.archivedChatIds.contains(chatId)
            // Never expose hidden chat content, sender identity, or alerts in system notifications
            if (isHiddenChat) return@launch
            if (isArchivedChat && userSettings.archiveKeepMutedChats) return@launch

            val customToneUri = userSettings.notificationToneUri.trim()
            val messageChannelId = resolveMessageChannelId(customToneUri)

            when (type) {
                "INCOMING_CALL" -> {
                    if (!userSettings.notificationsCalls) return@launch
                    showIncomingCallNotification(
                        callId = data["callId"].orEmpty(),
                        callerUid = data["callerId"].orEmpty(),
                        callerName = data["callerName"] ?: rawTitle,
                        callerAvatar = data["callerAvatar"].orEmpty(),
                        callerVerified = data["callerVerified"] == "true",
                        isVideo = data["isVideo"] == "true",
                        customRingtoneUri = userSettings.callRingtoneUri.trim()
                    )
                }
                "GROUP_MESSAGE" -> {
                    if (!userSettings.notificationsGroups) return@launch
                    showStandardNotification(
                        channelId = messageChannelId,
                        soundUriString = customToneUri,
                        title = rawTitle,
                        body = rawBody,
                        chatId = chatId,
                        wesalChannelId = null,
                        notificationId = dedupId
                    )
                }
                "STORY" -> {
                    if (!userSettings.notificationsStories) return@launch
                    showStandardNotification(Constants.CHANNEL_STORIES, "", rawTitle, rawBody, null, null, dedupId)
                }
                "CHANNEL_POST" -> {
                    if (!userSettings.notificationsMessages) return@launch
                    showStandardNotification(Constants.CHANNEL_CHANNELS, "", rawTitle, rawBody, null, data["channelId"], dedupId)
                }
                "SYSTEM", "ADMIN_ALERT", "ADMIN_SUBSCRIPTION", "ADMIN_REPORT", "ADMIN_SUPPORT" -> {
                    showStandardNotification(messageChannelId, customToneUri, rawTitle, rawBody, chatId, null, dedupId)
                }
                else -> {
                    if (!userSettings.notificationsMessages) return@launch
                    showStandardNotification(
                        channelId = messageChannelId,
                        soundUriString = customToneUri,
                        title = rawTitle,
                        body = rawBody,
                        chatId = chatId,
                        wesalChannelId = null,
                        notificationId = dedupId
                    )
                }
            }
        }
    }

    private fun resolveMessageChannelId(customToneUri: String): String {
        if (customToneUri.isBlank()) return Constants.CHANNEL_MESSAGES
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return Constants.CHANNEL_MESSAGES
        val manager = getSystemService(NotificationManager::class.java) ?: return Constants.CHANNEL_MESSAGES
        val channelId = "${Constants.CHANNEL_MESSAGES}_tone_${customToneUri.hashCode()}"
        if (manager.getNotificationChannel(channelId) == null) {
            val parsedUri = runCatching { Uri.parse(customToneUri) }.getOrNull()
            if (parsedUri != null) {
                val channel = NotificationChannel(
                    channelId,
                    getString(R.string.channel_messages_name),
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
        }
        return channelId
    }

    private fun showStandardNotification(
        channelId: String,
        soundUriString: String,
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

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_wesal_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        if (soundUriString.isNotBlank()) {
            runCatching { Uri.parse(soundUriString) }.getOrNull()?.let { builder.setSound(it) }
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(notificationId, builder.build())
    }

    /** Android 14+ can deny full-screen intents; before that the declared permission suffices. */
    private fun canUseFullScreenIntent(manager: NotificationManager): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            runCatching { manager.canUseFullScreenIntent() }.getOrDefault(true)
    }

    /**
     * Audible call channel for the no-full-screen-intent fallback. Mirrors the per-tone message
     * channel trick: a custom ringtone gets its own channel id because channel sound is immutable.
     */
    private fun resolveAudibleCallChannelId(customRingtoneUri: String): String {
        if (customRingtoneUri.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Constants.CHANNEL_CALLS_AUDIBLE
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return Constants.CHANNEL_CALLS_AUDIBLE
        val channelId = "${Constants.CHANNEL_CALLS_AUDIBLE}_tone_${customRingtoneUri.hashCode()}"
        if (manager.getNotificationChannel(channelId) == null) {
            val parsedUri = runCatching { Uri.parse(customRingtoneUri) }.getOrNull() ?: return Constants.CHANNEL_CALLS_AUDIBLE
            val channel = NotificationChannel(
                channelId,
                getString(R.string.channel_calls_name),
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

    private fun showIncomingCallNotification(
        callId: String,
        callerUid: String,
        callerName: String,
        callerAvatar: String,
        callerVerified: Boolean,
        isVideo: Boolean,
        customRingtoneUri: String = ""
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

        val manager = getSystemService(NotificationManager::class.java) ?: return

        // Exactly one audible ringing source per call:
        // - Full-screen intent available → silent channel; IncomingCallActivity opens and plays
        //   the user's ringtone itself (previous behaviour, unchanged).
        // - Full-screen intent NOT available (Android 14+ denial, OEM restrictions) → the
        //   notification itself rings on the audible channel; the activity is only opened by a
        //   user tap, and it cancels this notification before starting its own ringtone.
        val fullScreenAvailable = canUseFullScreenIntent(manager)
        val builder = if (fullScreenAvailable) {
            NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
                .setSilent(true)
                .setFullScreenIntent(fullScreenPendingIntent, true)
        } else {
            NotificationCompat.Builder(this, resolveAudibleCallChannelId(customRingtoneUri))
                .setContentIntent(fullScreenPendingIntent)
                .setTimeoutAfter(INCOMING_CALL_RING_TIMEOUT_MS)
                .apply {
                    // Pre-O devices have no channel sound; set the ringtone on the notification.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        val uri = customRingtoneUri.takeIf { it.isNotBlank() }
                            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                        setSound(uri)
                    }
                }
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_wesal_notification)
            .setContentTitle(callerName)
            .setContentText(if (isVideo) "مكالمة فيديو واردة" else "مكالمة صوتية واردة")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "رفض", rejectPendingIntent)
            .addAction(android.R.drawable.ic_menu_call, "رد", answerPendingIntent)
            .build()

        manager.notify(notifId, notification)
    }

    private companion object {
        /** Matches the app's 45s no-answer window, plus a small grace period. */
        const val INCOMING_CALL_RING_TIMEOUT_MS = 50_000L
    }
}
