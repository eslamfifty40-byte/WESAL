package com.wesal.messenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import coil.ImageLoader
import coil.ImageLoaderFactory
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
import com.google.firebase.firestore.SetOptions
import com.wesal.messenger.core.util.Constants
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WesalApplication : Application(), DefaultLifecycleObserver, ImageLoaderFactory {

    @Inject lateinit var firebaseAuth: FirebaseAuth
    @Inject lateinit var firestore: FirebaseFirestore
    @Inject lateinit var userPrefs: com.wesal.messenger.data.local.UserPreferencesDataStore
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override fun onCreate() {
        super<Application>.onCreate()
        FirebaseApp.initializeApp(this)
        configureFirestoreOfflinePersistence()
        configureAppCheck()
        rememberSignedInAccountLocally()
        createNotificationChannels()
        com.wesal.messenger.core.util.WesalFrameLibrary.startWithAuth()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
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
            .crossfade(true)
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
        clearStuckWriteQueueOnce()
    }

    /**
     * A write that never gets acknowledged keeps the whole local write queue blocked, and the queue is saved
     * on disk, so every later write waits behind it even after reinstalling over the old build.
     * Clear the local cache once per build version, before Firestore is used. The server data is not touched.
     */
    private fun clearStuckWriteQueueOnce() {
        runCatching {
            val sp = getSharedPreferences("wesal_diag", MODE_PRIVATE)
            val key = "fs_cache_cleared_v2"
            if (sp.getBoolean(key, false)) {
                com.wesal.messenger.chat.WesalDiag.clearResult.value = "clr=done-before"
                return
            }
            firestore.clearPersistence()
                .addOnSuccessListener {
                    sp.edit().putBoolean(key, true).apply()
                    com.wesal.messenger.chat.WesalDiag.clearResult.value = "clr=OK"
                }
                .addOnFailureListener { e ->
                    com.wesal.messenger.chat.WesalDiag.clearResult.value = "clr=ERR:${e.javaClass.simpleName}"
                }
        }.onFailure {
            com.wesal.messenger.chat.WesalDiag.clearResult.value = "clr=EXC:${it.javaClass.simpleName}"
        }
    }

    /**
     * Remember every signed-in account on the device right away, from the Firebase Auth user only.
     * Saving used to happen only after server writes finished, so accounts were lost when Firestore stalled.
     */
    private fun rememberSignedInAccountLocally() {
        firebaseAuth.addAuthStateListener { a ->
            val u = a.currentUser ?: return@addAuthStateListener
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
            // Debug/CI builds have no registered App Check debug token, so a failing token request can stall
            // Firestore. Skip App Check there; release builds keep Play Integrity.
            if (BuildConfig.DEBUG) return@runCatching
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
        firestore.collection(Constants.COLLECTION_PRESENCE)
            .document(uid)
            .set(
                mapOf(
                    "uid" to uid,
                    "isOnline" to isOnline,
                    "lastHeartbeatAt" to if (isOnline) now else 0L,
                    "updatedAt" to now
                ),
                SetOptions.merge()
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

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val callsChannel = NotificationChannel(
            Constants.CHANNEL_CALLS,
            getString(R.string.channel_calls_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableLights(true)
            lightColor = 0xFF00C853.toInt()
            enableVibration(true)
            setSound(
                ringtoneUri,
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
            listOf(messagesChannel, callsChannel, storiesChannel, channelsChannel, systemChannel)
        )
    }
}
