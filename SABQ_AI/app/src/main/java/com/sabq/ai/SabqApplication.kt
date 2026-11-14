package com.sabq.ai

import android.app.Application
import com.sabq.ai.ai.SabqSecondBrainEngine
import com.sabq.ai.data.local.SabqDatabase
import com.sabq.ai.data.local.UserProfileEntity
import com.sabq.ai.data.preferences.SabqPreferences
import com.sabq.ai.pdf.SabqArabicPdfGenerator
import com.sabq.ai.sensors.SabqBioFingerManager
import com.sabq.ai.sensors.WearableAudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SabqApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: SabqDatabase
        private set
    lateinit var preferences: SabqPreferences
        private set
    lateinit var bioFingerManager: SabqBioFingerManager
        private set
    lateinit var wearableAudioManager: WearableAudioManager
        private set
    lateinit var secondBrainEngine: SabqSecondBrainEngine
        private set
    lateinit var pdfGenerator: SabqArabicPdfGenerator
        private set

    override fun onCreate() {
        super.onCreate()
        database = SabqDatabase.getInstance(this)
        preferences = SabqPreferences(this)
        bioFingerManager = SabqBioFingerManager(this)
        wearableAudioManager = WearableAudioManager(this).also { it.initTts() }
        secondBrainEngine = SabqSecondBrainEngine(BuildConfig.SABQ_API_BASE_URL)
        pdfGenerator = SabqArabicPdfGenerator(this)

        appScope.launch {
            if (database.sabqDao().getUser("user_primary") == null) {
                database.sabqDao().upsertUser(
                    UserProfileEntity(
                        userId = "user_primary",
                        displayName = "المستخدم الرئيسي"
                    )
                )
            }
        }
    }
}
