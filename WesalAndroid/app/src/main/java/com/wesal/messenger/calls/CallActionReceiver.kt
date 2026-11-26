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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REJECT_CALL) return
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        if (callId.isBlank()) return

        context.getSystemService(NotificationManager::class.java)?.cancel(callId.hashCode())

        CallRingingController.stop(callId)
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
        const val EXTRA_CALL_ID = "extra_call_id"
    }
}
