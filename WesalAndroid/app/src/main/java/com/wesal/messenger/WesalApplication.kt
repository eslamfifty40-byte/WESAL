package com.wesal.messenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.wesal.messenger.core.util.Constants
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WesalApplication : Application(), DefaultLifecycleObserver {

    @Inject lateinit var firebaseAuth: FirebaseAuth
    @Inject lateinit var firestore: FirebaseFirestore

    override fun onCreate() {
        super<Application>.onCreate()
        FirebaseApp.initializeApp(this)
        configureFirestoreOfflinePersistence()
        configureAppCheck()
        createNotificationChannels()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    private fun configureFirestoreOfflinePersistence() {
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
        firestore.firestoreSettings = settings
    }

    private fun configureAppCheck() {
        val appCheck = FirebaseAppCheck.getInstance()
        val factory = if (BuildConfig.DEBUG) {
            DebugAppCheckProviderFactory.getInstance()
        } else {
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }
        appCheck.installAppCheckProviderFactory(factory)
    }

    override fun onStart(owner: LifecycleOwner) {
        updateUserPresence(isOnline = true)
    }

    override fun onStop(owner: LifecycleOwner) {
        updateUserPresence(isOnline = false)
    }

    private fun updateUserPresence(isOnline: Boolean) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        val updates = hashMapOf<String, Any>(
            "isOnline" to isOnline,
            "lastSeen" to FieldValue.serverTimestamp()
        )
        firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .update(updates)
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

        val systemChannel = NotificationChannel(
            Constants.CHANNEL_SYSTEM,
            getString(R.string.channel_system_name),
            NotificationManager.IMPORTANCE_LOW
        )

        manager.createNotificationChannels(
            listOf(messagesChannel, callsChannel, storiesChannel, systemChannel)
        )
    }
}
