package com.wesal.messenger.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.wesal.messenger.MainActivity
import com.wesal.messenger.R
import com.wesal.messenger.calls.CallActionReceiver
import com.wesal.messenger.calls.CallActivity
import com.wesal.messenger.calls.IncomingCallActivity
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.CallStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Persistent listener service: the no-push-server notification path.
 *
 * Cloud Functions cannot be deployed on this Firebase project, so nothing can send
 * FCM data messages and [WesalFirebaseMessagingService] never fires while the app is
 * dead. This service is the free, server-less substitute: while the user is signed in
 * it keeps exactly two Firestore listeners alive —
 *
 *  1. incoming calls for the current user (same query as
 *     `CallRepositoryImpl.observeIncomingCalls`), surfaced as the same full-screen /
 *     audible incoming-call notification the FCM path posts, reusing
 *     [IncomingCallActivity] and [CallActionReceiver];
 *  2. the user's chats list, diffed on `lastMessageTime`/`lastMessageSenderId`, so a
 *     genuinely new incoming message posts a normal message notification.
 *
 * Privacy/behaviour rules mirror the FCM path and the chat list:
 *  - hidden chats never produce a notification (not even a silent one);
 *  - per-chat muted chats (`mutedBy`) and — when the user keeps archived chats
 *    muted — archived chats are skipped;
 *  - the user's own messages never notify;
 *  - the per-type global toggles (messages / groups / calls) are respected;
 *  - while the app is in the foreground nothing is posted: the in-app UI already
 *    surfaces incoming calls (MainViewModel.incomingCall) and unread badges.
 *
 * Battery: two listeners, no polling, no partial wake locks. The service stops on
 * sign-out and rebinds its listeners when the signed-in account changes.
 */
@AndroidEntryPoint
class WesalListenerService : Service() {

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var firestore: FirebaseFirestore
    @Inject lateinit var prefs: UserPreferencesDataStore
    @Inject lateinit var callRepository: com.wesal.messenger.domain.repository.CallRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var latestSettings: AppUserSettings = AppUserSettings()

    private var authStateListener: FirebaseAuth.AuthStateListener? = null
    private var boundUid: String? = null
    private var callsRegistration: ListenerRegistration? = null
    private var chatsRegistration: ListenerRegistration? = null

    /** Call ids that already produced (or deliberately skipped) a notification. Main-thread confined. */
    private val seenCallIds = mutableSetOf<String>()

    /**
     * Round 11 (item 3): call ids whose ringing acknowledgement this service
     * has already stamped. Until now ONLY IncomingCallActivity acked — so
     * when the full-screen intent could not fire (Android 14+ background
     * restrictions) and the call was presented by THIS service's
     * notification alone, the call doc stayed CONNECTING forever and the
     * caller's honest state never advanced to «يرن». The service IS a
     * callee-side presentation surface, so it acks on first sight of an
     * actionable call — before the foreground/settings checks below,
     * because the ack means "the callee side received the call", not
     * "a notification was posted".
     */
    private val ringAckedCallIds = mutableSetOf<String>()

    /** Last observed (lastMessageTime, lastMessageSenderId) per chat. Main-thread confined. */
    private val chatBaseline = mutableMapOf<String, Pair<Long, String>>()
    private var chatsBaselineReady = false

    private val userNameCache = ConcurrentHashMap<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureSystemChannel()
        startForegroundNotification()
        serviceScope.launch {
            prefs.settingsFlow.collect { latestSettings = it }
        }
        val listener = FirebaseAuth.AuthStateListener { fa ->
            onAuthChanged(fa.currentUser?.uid)
        }
        authStateListener = listener
        auth.addAuthStateListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (auth.currentUser == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        authStateListener?.let { runCatching { auth.removeAuthStateListener(it) } }
        authStateListener = null
        removeListeners()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ auth / binding

    private fun onAuthChanged(uid: String?) {
        if (uid == null) {
            removeListeners()
            boundUid = null
            stopSelf()
            return
        }
        if (uid == boundUid) return
        bindListeners(uid)
    }

    private fun bindListeners(uid: String) {
        removeListeners()
        boundUid = uid
        seenCallIds.clear()
        ringAckedCallIds.clear()
        chatBaseline.clear()
        chatsBaselineReady = false
        userNameCache.clear()

        // R6 FIX: same rules-provability bug as CallRepositoryImpl.observeIncomingCalls —
        // the calls read rule is `uid in resource.data.participants`, which a
        // `calleeId == uid` LIST query cannot prove, so this listener was rejected with
        // PERMISSION_DENIED and silently swallowed (err -> return), meaning NO
        // background incoming-call notification/ring ever fired. Query on the
        // array-contains constraint the rule can prove and filter callee/status
        // client-side in handleCallsSnapshot instead.
        callsRegistration = firestore.collection(Constants.COLLECTION_CALLS)
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) {
                    if (err != null) {
                        android.util.Log.e("WesalListener", "calls listener error: ${err.message}", err)
                    }
                    return@addSnapshotListener
                }
                handleCallsSnapshot(uid, snap.documents)
            }

        chatsRegistration = firestore.collection(Constants.COLLECTION_CHATS)
            .whereArrayContains("participantIds", uid)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                handleChatsSnapshot(uid, snap.documents)
            }
    }

    private fun removeListeners() {
        runCatching { callsRegistration?.remove() }
        runCatching { chatsRegistration?.remove() }
        callsRegistration = null
        chatsRegistration = null
    }

    // ------------------------------------------------------------------ incoming calls

    private data class RingingCall(
        val callId: String,
        val callerId: String,
        val callerName: String,
        val callerAvatar: String,
        val callerVerified: Boolean,
        val isVideo: Boolean
    )

    private fun handleCallsSnapshot(uid: String, docs: List<com.google.firebase.firestore.DocumentSnapshot>) {
        val cutoff = System.currentTimeMillis() - CALL_RING_WINDOW_MS
        val ringing = docs.mapNotNull { doc ->
            val startedAt = doc.getLong("startedAt") ?: 0L
            val callerId = doc.getString("callerId").orEmpty()
            // R6: the query now returns every call I participate in (see bindListeners);
            // only calls actually addressed to me and still ringing are actionable here.
            if (doc.getString("calleeId").orEmpty() != uid) return@mapNotNull null
            val statusRaw = doc.getString("status").orEmpty()
            if (statusRaw != CallStatus.CONNECTING.raw && statusRaw != CallStatus.RINGING.raw) {
                return@mapNotNull null
            }
            if (startedAt < cutoff || callerId.isBlank() || callerId == uid) return@mapNotNull null
            RingingCall(
                callId = doc.id,
                callerId = callerId,
                callerName = doc.getString("callerName").orEmpty(),
                callerAvatar = doc.getString("callerAvatarUrl").orEmpty(),
                callerVerified = doc.getBoolean("callerVerified") ?: false,
                isVideo = doc.getBoolean("isVideo") ?: false
            )
        }
        val currentIds = ringing.map { it.callId }.toSet()

        // Round 11 (item 3): stamp the callee-side ringing ack for every
        // actionable call the moment this service first sees it — the
        // caller's «جاري الاتصال» → «يرن» transition depends on it, and the
        // repository transaction is idempotent (CONNECTING → RINGING only,
        // callee-only), so a duplicate ack from IncomingCallActivity is a
        // no-op.
        for (call in ringing) {
            if (ringAckedCallIds.add(call.callId)) {
                serviceScope.launch {
                    runCatching { callRepository.acknowledgeIncomingRing(call.callId) }
                }
            }
        }
        ringAckedCallIds.retainAll(currentIds)

        // Calls that stopped ringing (answered, rejected, timed out, caller hung up):
        // their notification must go away.
        val manager = getSystemService(NotificationManager::class.java)
        for (endedId in seenCallIds - currentIds) {
            runCatching { manager?.cancel(endedId.hashCode()) }
        }
        seenCallIds.retainAll(currentIds)

        for (call in ringing) {
            if (call.callId in seenCallIds) continue
            seenCallIds += call.callId
            if (!latestSettings.notificationsCalls) continue
            if (isAppInForeground()) continue // MainActivity drives the in-app ringing UI.
            val ringtoneUri = latestSettings.callRingtoneUri.trim()
            if (call.callerName.isNotBlank()) {
                showIncomingCallNotification(call, ringtoneUri)
            } else {
                // Round 9 (item 5): the call doc carried no caller name (older
                // caller build, or a caller doc with blank displayName/publicId)
                // — resolve the name from the caller's user doc (same cached
                // lookup the chat notifications use) instead of ringing with a
                // blank identity.
                serviceScope.launch {
                    val resolved = resolveUserName(call.callerId)
                    showIncomingCallNotification(call.copy(callerName = resolved), ringtoneUri)
                }
            }
        }
    }

    /** Mirrors the FCM path: exactly one audible ringing source per call. */
    private fun showIncomingCallNotification(call: RingingCall, customRingtoneUri: String) {
        val notifId = call.callId.hashCode()
        val callIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(IncomingCallActivity.EXTRA_CALL_ID, call.callId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_UID, call.callerId)
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, call.callerName)
            putExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR, call.callerAvatar)
            putExtra(IncomingCallActivity.EXTRA_CALLER_VERIFIED, call.callerVerified)
            putExtra(IncomingCallActivity.EXTRA_IS_VIDEO, call.isVideo)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = CallActivity.createIncomingAnswerIntent(
            context = this,
            callId = call.callId,
            peerName = call.callerName,
            peerAvatar = call.callerAvatar,
            isVideo = call.isVideo,
            peerVerified = call.callerVerified,
            peerUid = call.callerId
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
            putExtra(CallActionReceiver.EXTRA_CALL_ID, call.callId)
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            this,
            notifId + 2,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = getSystemService(NotificationManager::class.java) ?: return

        val fullScreenAvailable = canUseFullScreenIntent(manager)
        val builder = if (fullScreenAvailable) {
            // Silent channel: IncomingCallActivity opens and plays the ringtone itself.
            NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
                .setSilent(true)
                .setFullScreenIntent(fullScreenPendingIntent, true)
        } else {
            // The notification itself rings on the (possibly per-tone) audible channel.
            NotificationCompat.Builder(this, ToneChannels.ensureAudibleCallChannel(this, customRingtoneUri))
                .setContentIntent(fullScreenPendingIntent)
                .setTimeoutAfter(INCOMING_CALL_RING_TIMEOUT_MS)
                .apply {
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
            .setContentTitle(call.callerName.ifBlank { getString(R.string.app_name) })
            .setContentText(if (call.isVideo) "مكالمة فيديو واردة" else "مكالمة صوتية واردة")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "رفض", rejectPendingIntent)
            .addAction(android.R.drawable.ic_menu_call, "رد", answerPendingIntent)
            .build()

        runCatching { manager.notify(notifId, notification) }
    }

    /** Android 14+ can deny full-screen intents; before that the declared permission suffices. */
    private fun canUseFullScreenIntent(manager: NotificationManager): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            runCatching { manager.canUseFullScreenIntent() }.getOrDefault(true)
    }

    // ------------------------------------------------------------------ new messages

    private fun handleChatsSnapshot(uid: String, docs: List<com.google.firebase.firestore.DocumentSnapshot>) {
        val settings = latestSettings
        val now = System.currentTimeMillis()
        val presentIds = mutableSetOf<String>()

        for (doc in docs) {
            val chatId = doc.id
            @Suppress("UNCHECKED_CAST")
            val deletedBy = (doc.get("deletedForUsers") as? List<String>).orEmpty()
            if (deletedBy.contains(uid)) {
                chatBaseline.remove(chatId)
                continue
            }
            presentIds += chatId

            val lastTime = doc.getLong("lastMessageTime") ?: 0L
            val lastSenderId = doc.getString("lastMessageSenderId").orEmpty()
            val previous = chatBaseline[chatId]
            chatBaseline[chatId] = lastTime to lastSenderId

            // The first snapshot after (re)binding only establishes the baseline.
            // After that, a chat notifies when its latest message is new: either the
            // chat itself just appeared with a fresh incoming message, or its
            // lastMessageTime strictly advanced past the baseline with someone
            // else as the sender.
            if (!chatsBaselineReady) continue
            val isNewIncoming = if (previous == null) {
                lastSenderId.isNotBlank() && lastSenderId != uid
            } else {
                lastTime > previous.first && lastSenderId.isNotBlank() && lastSenderId != uid
            }
            if (!isNewIncoming) continue
            if (now - lastTime > MESSAGE_FRESH_WINDOW_MS) continue

            // Privacy + preference gates (mirror the FCM path and the chat list).
            if (settings.hiddenChatIds.contains(chatId)) continue
            @Suppress("UNCHECKED_CAST")
            val mutedBy = (doc.get("mutedBy") as? Map<String, Boolean>).orEmpty()
            if (mutedBy[uid] == true) continue
            @Suppress("UNCHECKED_CAST")
            val archivedBy = (doc.get("archivedBy") as? Map<String, Boolean>).orEmpty()
            if (archivedBy[uid] == true && settings.archiveKeepMutedChats) continue

            val isGroup = doc.getBoolean("isGroup") ?: false
            if (isGroup && !settings.notificationsGroups) continue
            if (!isGroup && !settings.notificationsMessages) continue
            if (isAppInForeground()) continue

            val rawPreview = doc.getString("lastMessagePreview").orEmpty()
            val preview = if (rawPreview == "رسالة مشفرة") "رسالة سابقة" else rawPreview
            @Suppress("UNCHECKED_CAST")
            val participantIds = (doc.get("participantIds") as? List<String>).orEmpty()
            val peerUid = participantIds.firstOrNull { it != uid }.orEmpty()
            val groupName = doc.getString("groupName").orEmpty()

            serviceScope.launch {
                val title: String
                val body: String
                if (isGroup) {
                    title = groupName.ifBlank { "مجموعة وصال" }
                    val senderName = resolveUserName(lastSenderId)
                    body = when {
                        preview.isBlank() -> if (senderName.isNotBlank()) "$senderName أرسل رسالة" else "رسالة جديدة"
                        senderName.isNotBlank() -> "$senderName: $preview"
                        else -> preview
                    }
                } else {
                    title = resolveUserName(peerUid).ifBlank { "وصال" }
                    body = preview.ifBlank { "رسالة جديدة" }
                }
                showMessageNotification(chatId, title, body, settings.notificationToneUri.trim())
            }
        }

        // Chats that vanished from the list (left/deleted) lose their baseline.
        chatBaseline.keys.retainAll(presentIds)
        chatsBaselineReady = true
    }

    private fun showMessageNotification(chatId: String, title: String, body: String, customToneUri: String) {
        val channelId = ToneChannels.ensureMessageChannel(this, customToneUri)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatId", chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            chatId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_wesal_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && customToneUri.isNotBlank()) {
            runCatching { Uri.parse(customToneUri) }.getOrNull()?.let { builder.setSound(it) }
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(chatId.hashCode(), builder.build()) }
    }

    private suspend fun resolveUserName(userUid: String): String {
        if (userUid.isBlank()) return ""
        userNameCache[userUid]?.let { return it }
        val name = runCatching {
            firestore.collection(Constants.COLLECTION_USERS)
                .document(userUid)
                .get()
                .await()
                .getString("displayName")
                .orEmpty()
        }.getOrDefault("")
        if (name.isNotBlank()) userNameCache[userUid] = name
        return name
    }

    // ------------------------------------------------------------------ foreground plumbing

    private fun isAppInForeground(): Boolean = runCatching {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }.getOrDefault(false)

    /**
     * The Application creates the channels deferred to the first idle moment, but
     * this service can start from the boot receiver before that runs — so it makes
     * sure the channels it posts on exist, with the same attributes the Application
     * uses (re-creating an existing channel is a no-op and never overrides it).
     */
    private fun ensureSystemChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            if (manager.getNotificationChannel(Constants.CHANNEL_SYSTEM) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        Constants.CHANNEL_SYSTEM,
                        getString(R.string.channel_system_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            if (manager.getNotificationChannel(Constants.CHANNEL_MESSAGES) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        Constants.CHANNEL_MESSAGES,
                        getString(R.string.channel_messages_name),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        enableLights(true)
                        lightColor = 0xFF00E5FF.toInt()
                        enableVibration(true)
                    }
                )
            }
            if (manager.getNotificationChannel(Constants.CHANNEL_CALLS) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        Constants.CHANNEL_CALLS,
                        getString(R.string.channel_calls_name),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        enableLights(true)
                        lightColor = 0xFF00C853.toInt()
                        enableVibration(false)
                        setSound(null, null)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
            }
            if (manager.getNotificationChannel(Constants.CHANNEL_CALLS_AUDIBLE) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        Constants.CHANNEL_CALLS_AUDIBLE,
                        getString(R.string.channel_calls_name),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        enableLights(true)
                        lightColor = 0xFF00C853.toInt()
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0L, 600L, 400L, 600L, 1000L)
                        setSound(
                            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                )
            }
        }
    }

    private fun startForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            FOREGROUND_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, Constants.CHANNEL_SYSTEM)
            .setSmallIcon(R.drawable.ic_wesal_notification)
            .setContentTitle("وصال متصل")
            .setContentText("الخدمة شغالة في الخلفية عشان توصلك الرسائل والمكالمات فوراً")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, FOREGROUND_NOTIFICATION_ID, notification, fgsType)
        }
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 7101

        /** Matches observeIncomingCalls' freshness window. */
        private const val CALL_RING_WINDOW_MS = 90_000L

        /** Matches the app's 45s no-answer window, plus a small grace period. */
        private const val INCOMING_CALL_RING_TIMEOUT_MS = 50_000L

        /** Messages older than this when first observed are treated as history, not news. */
        private const val MESSAGE_FRESH_WINDOW_MS = 120_000L

        fun start(context: Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, WesalListenerService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WesalListenerService::class.java)) }
        }
    }
}
