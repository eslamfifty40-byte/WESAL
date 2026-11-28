package com.wesal.messenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.StrictMode
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.wesal.messenger.core.util.BackendHealthStore
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.MessagePlaneRouter
import com.wesal.messenger.core.util.PresenceWriteGate
import com.wesal.messenger.core.util.PerfTrace
import com.wesal.messenger.core.util.SendFailureCopy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class WesalApplication : Application(), DefaultLifecycleObserver, ImageLoaderFactory {

    @Inject lateinit var firebaseAuth: FirebaseAuth
    @Inject lateinit var firestore: FirebaseFirestore
    @Inject lateinit var userPrefs: com.wesal.messenger.data.local.UserPreferencesDataStore
    // Lazy providers on purpose: ChatRepository (with its crypto manager + DAO graph)
    // and the Room DAO used to be constructed by Hilt during Application.onCreate, on
    // the main thread, before the first frame — pure cold-start cost, since both are
    // only ever used inside appScope coroutines (outbox flush / cross-account cache
    // clear). Provider.get() at first actual use keeps behaviour identical.
    @Inject lateinit var chatRepositoryProvider: Provider<com.wesal.messenger.domain.repository.ChatRepository>
    @Inject lateinit var wesalDaoProvider: Provider<com.wesal.messenger.data.local.dao.WesalDao>
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override fun onCreate() {
        // §13.2 measurement: cold-start timing origin + DEBUG-only StrictMode.
        // PerfTrace is a no-op in release; StrictMode is installed only under
        // BuildConfig.DEBUG and only logs (penaltyLog) — never crashes.
        PerfTrace.mark("app_oncreate_start")
        super<Application>.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
        FirebaseApp.initializeApp(this)
        configureFirestoreOfflinePersistence()
        configureAppCheck()
        // Failover design §5.1: the backend health state survives process
        // death — hydrate the store from DataStore and persist every
        // transition, so a killed app does not resurrect into hammering a
        // quota-dead backend (and the router's stickiness holds across
        // restarts). Stage 1: the store observes and records only.
        BackendHealthStore.attachPersister { state ->
            appScope.launch { runCatching { userPrefs.persistBackendHealthState(state) } }
        }
        appScope.launch {
            runCatching { BackendHealthStore.hydrate(userPrefs.loadBackendHealthState()) }
        }
        // Failover design §9.3 (stage 2): the message-plane config.
        // Hydrate the router from the DataStore cache immediately, then
        // follow system_config/message_plane live for the process
        // lifetime. Fail-open in the SAFE direction: a missing doc or a
        // listener error keeps the current config — the dark default
        // is Firestore primary, and the plane lights only on the
        // doc's explicit "supabase" value.
        appScope.launch {
            runCatching {
                userPrefs.cachedMessagePlaneValue()?.let { cached ->
                    MessagePlaneRouter.hydrateConfig(
                        MessagePlaneRouter.configFromPlaneValue(cached)
                    )
                }
            }
        }
        runCatching {
            firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
                .document("message_plane")
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null || !snap.exists()) {
                        return@addSnapshotListener
                    }
                    val value = snap.getString("plane")
                        ?.trim()?.lowercase()
                        ?.takeIf { it == "supabase" || it == "firestore" }
                        ?: return@addSnapshotListener
                    MessagePlaneRouter.hydrateConfig(
                        MessagePlaneRouter.configFromPlaneValue(value)
                    )
                    appScope.launch {
                        runCatching { userPrefs.setCachedMessagePlaneValue(value) }
                    }
                }
        }
        rememberSignedInAccountLocally()
        // Deferred until the main thread first goes idle (i.e. after the first frame):
        // creating 6 channels is a series of binder calls that bought nothing on the
        // cold-start path — channels only need to exist before a notification posts,
        // and the idle handler runs long before that in practice.
        android.os.Looper.myQueue().addIdleHandler {
            createNotificationChannels()
            false // one-shot
        }
        com.wesal.messenger.core.util.WesalFrameLibrary.startWithAuth()
        com.wesal.messenger.core.util.WesalFeatureFlags.startWithAuth()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // Animated GIF frames (uploaded via the frame library) need a GIF decoder;
                // without one they rendered as a static first frame.
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024L * 1024L)
                    .build()
            }
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }

    private fun configureFirestoreOfflinePersistence() {
        runCatching {
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
            firestore.firestoreSettings = settings
        }
        // NOTE: clearPersistence() is intentionally NOT called any more. The previous
        // "clear the stuck write queue once per build" step discarded Firestore's queued
        // (not-yet-acknowledged) writes — those queued writes are the user's pending messages,
        // so the clear silently lost messages that had not reached the server (spec §1.3).
        // Pending sends are now protected properly: they live in the per-account outbox
        // (UserPreferencesDataStore) and are retried with stable message ids until they land.
        com.wesal.messenger.chat.WesalDiag.clearResult.value = "clr=disabled-preserve-queue"
    }

    /**
     * Retries every persisted pending (outbox) message for the signed-in account, once per
     * process per account. Sends are serialized through OutboxSendGuard and use the original
     * message ids, so a message that landed before the app died is never duplicated.
     */
    private val outboxFlushedForUids = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun flushOutboxOnce(uid: String) {
        if (!outboxFlushedForUids.add(uid)) return
        appScope.launch {
            runCatching {
                val pending = userPrefs.pendingOutboxMessages()
                for (entry in pending) {
                    // Failover design §5.2 rule 5: replay re-runs the
                    // router and checks existence on the target store
                    // BEFORE re-sending. A message that already landed
                    // (server-committed, or durably queued in the SDK's
                    // mutation queue) is never re-sent — its original
                    // atomic batch already carried the chat summary, so
                    // a blind re-send would double-increment the peer's
                    // unread count for a single message. Stage 2 acts
                    // on BOTH arms: Firestore existence via the message
                    // doc, Supabase existence via msg-list's id mode.
                    val target = MessagePlaneRouter.replayTarget(
                        MessagePlaneRouter.storeFromWire(entry.attemptedStore),
                        MessagePlaneRouter.currentConfig(),
                        BackendHealthStore.currentState()
                    )
                    val landed = when (target) {
                        MessagePlaneRouter.Store.FIRESTORE -> runCatching {
                            chatRepositoryProvider.get()
                                .isOutboxMessageLanded(entry.chatId, entry.messageId)
                        }.getOrDefault(false)
                        MessagePlaneRouter.Store.SUPABASE -> runCatching {
                            chatRepositoryProvider.get()
                                .isOutboxMessageLandedOnPlane(entry.chatId, entry.messageId)
                        }.getOrDefault(false)
                    }
                    if (landed) {
                        userPrefs.removeOutboxMessage(entry.messageId)
                        continue
                    }
                    val replyStub = if (!entry.replyToMessageId.isNullOrBlank()) {
                        com.wesal.messenger.domain.model.WesalMessage(
                            messageId = entry.replyToMessageId,
                            text = entry.replyToPreview.orEmpty()
                        )
                    } else null
                    val result = com.wesal.messenger.data.local.OutboxSendGuard.mutex.withLock {
                        chatRepositoryProvider.get().sendTextMessage(
                            chatId = entry.chatId,
                            text = entry.text,
                            replyTo = replyStub,
                            forwardedFromName = entry.forwardedFromName,
                            clientMessageId = entry.messageId
                        )
                    }
                    if (result.isSuccess) {
                        userPrefs.removeOutboxMessage(entry.messageId)
                    } else if (SendFailureCopy.isQueued(result.exceptionOrNull())) {
                        // The attempt's outcome is now unknown: stamp
                        // the store the SEND PATH actually targeted
                        // (recorded per message id in stage 2) so the
                        // next replay checks existence there first.
                        runCatching {
                            userPrefs.enqueueOutboxMessage(
                                entry.copy(
                                    attemptedStore = chatRepositoryProvider.get()
                                        .attemptedStoreFor(entry.messageId)
                                        ?: MessagePlaneRouter.wireName(
                                            MessagePlaneRouter.Store.FIRESTORE
                                        )
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Remember every signed-in account on the device right away, from the Firebase Auth user only.
     * Saving used to happen only after server writes finished, so accounts were lost when Firestore stalled.
     */
    /** Last account seen signed in during this process (survives intermediate sign-outs). */
    @Volatile
    private var lastKnownSignedInUid: String? = null

    private fun rememberSignedInAccountLocally() {
        firebaseAuth.addAuthStateListener { a ->
            // Out-of-app notifications (no push server): the listener service runs
            // exactly while a session exists — start on sign-in/app start with a
            // persisted session, stop on sign-out. The service additionally rebinds
            // its own listeners when the account changes and stops itself if it
            // ever observes a signed-out state.
            val u = a.currentUser
            if (u == null) {
                com.wesal.messenger.notifications.WesalListenerService.stop(this@WesalApplication)
                return@addAuthStateListener
            }
            com.wesal.messenger.notifications.WesalListenerService.start(this@WesalApplication)
            // Direct uid → uid switch (e.g. Google credential sign-in replaces the current
            // user WITHOUT going through signOut, which is the path that used to clear the
            // caches): the Room read-through caches have no owner-uid column, so the previous
            // account's cached chats/messages would otherwise bleed into the new account
            // until the first remote snapshot. Clearing is safe: these tables are caches of
            // server data and are rebuilt by the existing listeners; nothing is lost.
            val previousUid = lastKnownSignedInUid
            if (previousUid != null && previousUid != u.uid) {
                appScope.launch {
                    runCatching {
                        wesalDaoProvider.get().clearAllChats()
                        wesalDaoProvider.get().clearAllMessages()
                    }
                    runCatching { coil.Coil.imageLoader(this@WesalApplication).memoryCache?.clear() }
                    // §3.3 remainder: the Coil disk cache is global (no per-account
                    // partitioning), so clear it on a uid→uid switch too — otherwise
                    // the previous account's cached images survive on disk and can
                    // be served into the new account's UI.
                    runCatching { coil.Coil.imageLoader(this@WesalApplication).diskCache?.clear() }
                }
            }
            lastKnownSignedInUid = u.uid
            flushOutboxOnce(u.uid)
            appScope.launch {
                runCatching {
                    val known = userPrefs.savedAccountsFlow.first().any { it.uid == u.uid }
                    if (!known) {
                        userPrefs.upsertSavedAccount(
                            com.wesal.messenger.domain.model.SavedWesalAccount(
                                uid = u.uid,
                                displayName = u.displayName.orEmpty(),
                                email = u.email.orEmpty(),
                                phoneNumber = u.phoneNumber.orEmpty(),
                                photoUrl = u.photoUrl?.toString().orEmpty(),
                                provider = if (!u.phoneNumber.isNullOrBlank()) "phone" else "email",
                                lastUsedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }

    private fun configureAppCheck() {
        runCatching {
            val appCheck = FirebaseAppCheck.getInstance()
            // Round-3: DEBUG builds now install the App Check DEBUG provider
            // (firebase-appcheck-debug is already a debugImplementation dependency).
            // Previously DEBUG returned early and installed NO provider at all, so a
            // debug build carried no App Check token: if App Check enforcement is
            // ever enabled for Firestore in the console, every request from the
            // shipped debug APK is denied (PERMISSION_DENIED) while offline cache
            // makes reads look healthy — writes fail with no visible cause. The
            // debug provider's token is printed to logcat on first launch and must
            // be registered in the Firebase console (App Check → Apps → debug
            // tokens) for enforcement-enabled projects. Release builds are
            // unchanged: Play Integrity only.
            val factory = if (BuildConfig.DEBUG) {
                DebugAppCheckProviderFactory.getInstance()
            } else {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
            appCheck.installAppCheckProviderFactory(factory)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        updateUserPresence(isOnline = true)
    }

    override fun onStop(owner: LifecycleOwner) {
        updateUserPresence(isOnline = false)
    }

    private fun updateUserPresence(isOnline: Boolean) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        // Scaling pass: this lifecycle writer and the repository heartbeat
        // share PresenceWriteGate, so a foreground/background transition is
        // stamped by whichever of them runs first and the other stands
        // down — previously both wrote, and each also maintained a
        // presence/{uid} document that nothing in the app ever reads.
        if (!PresenceWriteGate.shouldWriteNow(uid, isOnline, "", "", now)) {
            return
        }
        val updates = hashMapOf<String, Any>(
            "isOnline" to isOnline,
            "lastSeen" to FieldValue.serverTimestamp(),
            "lastHeartbeatAt" to if (isOnline) now else 0L
        )
        if (!isOnline) {
            updates["lastActivityType"] = "NONE"
            updates["lastActivityChatId"] = ""
            updates["lastActivityParticipantsCount"] = 0
        }
        firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .update(updates)
        PresenceWriteGate.recordWriteNow(
            uid,
            isOnline,
            if (isOnline) null else "NONE",
            if (isOnline) null else "",
            now
        )
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val messagesChannel = NotificationChannel(
            Constants.CHANNEL_MESSAGES,
            getString(R.string.channel_messages_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableLights(true)
            lightColor = 0xFF00E5FF.toInt()
            enableVibration(true)
        }

        // Delete legacy ringing channel so Android never double-rings alongside IncomingCallActivity
        runCatching { manager.deleteNotificationChannel(Constants.CHANNEL_CALLS_LEGACY) }

        val callsChannel = NotificationChannel(
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

        // Audible fallback channel: used ONLY when the full-screen intent cannot fire
        // (e.g. Android 14+ full-screen permission denied). In the normal path the incoming
        // call activity itself plays the ringtone over the silent channel above, so the two
        // never ring at the same time — exactly one audible source per call.
        val callsAudibleChannel = NotificationChannel(
            Constants.CHANNEL_CALLS_AUDIBLE,
            getString(R.string.channel_calls_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableLights(true)
            lightColor = 0xFF00C853.toInt()
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 600L, 400L, 600L, 1000L)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val storiesChannel = NotificationChannel(
            Constants.CHANNEL_STORIES,
            getString(R.string.channel_stories_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val channelsChannel = NotificationChannel(
            Constants.CHANNEL_CHANNELS,
            getString(R.string.channel_channels_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val systemChannel = NotificationChannel(
            Constants.CHANNEL_SYSTEM,
            getString(R.string.channel_system_name),
            NotificationManager.IMPORTANCE_LOW
        )

        manager.createNotificationChannels(
            listOf(messagesChannel, callsChannel, callsAudibleChannel, storiesChannel, channelsChannel, systemChannel)
        )
    }
}
