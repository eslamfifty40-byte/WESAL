package com.wesal.messenger.calls

import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.repository.CallRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class IncomingCallActivity : AppCompatActivity() {

    @Inject lateinit var callRepository: CallRepository
    private var ringtone: Ringtone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "مستخدم وصال"
        val callerAvatar = intent.getStringExtra(EXTRA_CALLER_AVATAR).orEmpty()
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.play()
        }

        lifecycleScope.launch {
            callRepository.observeCall(callId).collect { call ->
                if (call != null && (call.status == CallStatus.ENDED || call.status == CallStatus.MISSED)) {
                    finish()
                }
            }
        }

        setContent {
            WesalTheme(darkTheme = true) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(WesalDeepBlack)
                        .padding(horizontal = 32.dp, vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = if (isVideo) "مكالمة فيديو واردة • وصال" else "مكالمة صوتية واردة • وصال",
                            style = MaterialTheme.typography.titleMedium,
                            color = WesalTurquoise
                        )
                        Spacer(Modifier.height(16.dp))
                        WesalAvatar(imageUrl = callerAvatar, name = callerName, size = 128.dp)
                        Text(
                            text = callerName,
                            style = MaterialTheme.typography.headlineLarge,
                            color = WesalTextPrimary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FloatingActionButton(
                            onClick = {
                                ringtone?.stop()
                                lifecycleScope.launch {
                                    callRepository.updateCallStatus(callId, CallStatus.REJECTED)
                                    finish()
                                }
                            },
                            containerColor = WesalError,
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "رفض", modifier = Modifier.size(32.dp))
                        }

                        FloatingActionButton(
                            onClick = {
                                ringtone?.stop()
                                startActivity(
                                    CallActivity.createIncomingAnswerIntent(
                                        this@IncomingCallActivity,
                                        callId = callId,
                                        peerName = callerName,
                                        peerAvatar = callerAvatar,
                                        isVideo = isVideo
                                    )
                                )
                                finish()
                            },
                            containerColor = WesalEmerald,
                            contentColor = WesalDeepBlack,
                            shape = CircleShape,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "رد", modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        ringtone?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_CALLER_AVATAR = "extra_caller_avatar"
        const val EXTRA_IS_VIDEO = "extra_is_video"
    }
}
