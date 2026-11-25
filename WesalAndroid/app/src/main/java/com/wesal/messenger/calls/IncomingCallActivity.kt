package com.wesal.messenger.calls

import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.theme.WesalTheme
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.repository.CallRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class IncomingCallActivity : AppCompatActivity() {

    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var prefs: UserPreferencesDataStore
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)

        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val callerUid = intent.getStringExtra(EXTRA_CALLER_UID).orEmpty()
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "WESAL User"
        val callerAvatar = intent.getStringExtra(EXTRA_CALLER_AVATAR).orEmpty()
        val callerVerified = intent.getBooleanExtra(EXTRA_CALLER_VERIFIED, false)
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        if (callId.isBlank()) {
            finish()
            return
        }

        startRingingAndVibration()
        // Notify caller genuinely that this callee device is actively ringing now
        lifecycleScope.launch {
            callRepository.acknowledgeIncomingRing(callId)
        }

        lifecycleScope.launch {
            callRepository.observeCall(callId).collect { call ->
                if (call != null && (call.status == CallStatus.ENDED || call.status == CallStatus.MISSED || call.status == CallStatus.REJECTED)) {
                    stopRingingAndVibration()
                    cancelIncomingNotification(callId)
                    finish()
                }
            }
        }

        setContent {
            val settings by prefs.settingsFlow.collectAsStateWithLifecycle(initialValue = AppUserSettings())
            WesalTheme(
                darkTheme = settings.darkTheme,
                languageTag = settings.languageTag,
                fontScale = settings.fontScale
            ) {
                val palette = WesalColors.current
                val strings = LocalWesalStrings.current

                // Keep user on incoming call screen until answered or rejected
                BackHandler { }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.background)
                        .padding(horizontal = 32.dp, vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = if (isVideo) strings.incomingVideoCall else strings.incomingVoiceCall,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.turquoise
                        )
                        Spacer(Modifier.height(16.dp))
                        WesalAvatar(imageUrl = callerAvatar, name = callerName, size = 128.dp)
                        WesalNameWithBadge(
                            name = callerName,
                            isVerified = callerVerified,
                            style = MaterialTheme.typography.headlineLarge,
                            color = palette.textPrimary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FloatingActionButton(
                                onClick = {
                                    stopRingingAndVibration()
                                    cancelIncomingNotification(callId)
                                    lifecycleScope.launch {
                                        callRepository.updateCallStatus(callId, CallStatus.REJECTED)
                                        finish()
                                    }
                                },
                                containerColor = palette.error,
                                contentColor = Color.White,
                                shape = CircleShape,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = strings.rejectContactRequest, modifier = Modifier.size(32.dp))
                            }
                            Text(
                                text = strings.rejectContactRequest,
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.textPrimary
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FloatingActionButton(
                                onClick = {
                                    stopRingingAndVibration()
                                    cancelIncomingNotification(callId)
                                    startActivity(
                                        CallActivity.createIncomingAnswerIntent(
                                            this@IncomingCallActivity,
                                            callId = callId,
                                            peerName = callerName,
                                            peerAvatar = callerAvatar,
                                            isVideo = isVideo,
                                            peerVerified = callerVerified,
                                            peerUid = callerUid
                                        )
                                    )
                                    finish()
                                },
                                containerColor = palette.emerald,
                                contentColor = palette.onPrimary,
                                shape = CircleShape,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = strings.acceptContactRequest, modifier = Modifier.size(32.dp))
                            }
                            Text(
                                text = strings.acceptContactRequest,
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startRingingAndVibration() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
        }
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator = vib
            val pattern = longArrayOf(0L, 600L, 400L, 600L, 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(pattern, 0)
            }
        }
    }

    private fun stopRingingAndVibration() {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
    }

    private fun cancelIncomingNotification(callId: String) {
        if (callId.isNotBlank()) {
            getSystemService(NotificationManager::class.java)?.cancel(callId.hashCode())
        }
    }

    override fun onDestroy() {
        stopRingingAndVibration()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_UID = "extra_caller_uid"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_CALLER_AVATAR = "extra_caller_avatar"
        const val EXTRA_CALLER_VERIFIED = "extra_caller_verified"
        const val EXTRA_IS_VIDEO = "extra_is_video"
    }
}
