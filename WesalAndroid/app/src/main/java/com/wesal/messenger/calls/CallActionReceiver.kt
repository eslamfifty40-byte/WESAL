package com.wesal.messenger.calls

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.repository.CallRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject lateinit var callRepository: CallRepository

    @Inject lateinit var webRtcManager: WebRtcCallManager

    @Inject lateinit var callController: CallSessionController

    override fun onReceive(context: Context, intent: Intent) {
        // Round 22: the picture-in-picture window's end-call action.
        // Goes through the session controller — the exact path the
        // in-call end button uses — so teardown (signaling stamp,
        // media, service, notification) is identical.
        if (intent.action == ACTION_END_CALL) {
            callController.endCall()
            return
        }
        // Round 21: «إيقاف المشاركة» on the ongoing-call notification
        // while a screen share is live. Stops the share on the manager
        // (video returns to the camera); the call itself is never
        // touched. The session controller observes the share state and
        // refreshes the notification (the action disappears).
        if (intent.action == ACTION_STOP_SCREEN_SHARE) {
            webRtcManager.stopScreenShare()
            return
        }
        if (intent.action != ACTION_REJECT_CALL) return
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        if (callId.isBlank()) return

        context.getSystemService(NotificationManager::class.java)?.cancel(callId.hashCode())

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                callRepository.updateCallStatus(callId, CallStatus.REJECTED, 0L)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REJECT_CALL = "com.wesal.messenger.ACTION_REJECT_CALL"
        const val ACTION_STOP_SCREEN_SHARE = "com.wesal.messenger.ACTION_STOP_SCREEN_SHARE"
        const val ACTION_END_CALL = "com.wesal.messenger.ACTION_END_CALL"
        const val EXTRA_CALL_ID = "extra_call_id"
    }
}
