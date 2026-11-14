package com.wesal.messenger.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.domain.model.CallRecord
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.CallRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    callRepository: CallRepository,
    authRepository: AuthRepository
) : ViewModel() {
    val myUid = authRepository.currentUserId.orEmpty()
    val history: StateFlow<List<CallRecord>> = callRepository.observeCallHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun CallHistoryScreen(viewModel: CallHistoryViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val allCalls by viewModel.history.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { CallsClearState.load(context) }
    // CLEARCALLS
    val calls = allCalls.filter { it.startedAt > CallsClearState.clearedAt }

    Column(modifier = Modifier.fillMaxSize().background(WesalDeepBlack)) {
        if (calls.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا توجد مكالمات مسجلة حتى الآن", color = WesalTextSecondary)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(calls, key = { it.callId }) { call ->
                    val outgoing = call.callerId == viewModel.myUid
                    val peerName = if (outgoing) call.calleeName else call.callerName
                    val peerAvatar = if (outgoing) call.calleeAvatarUrl else call.callerAvatarUrl
                    val peerUid = if (outgoing) call.calleeId else call.callerId
                    val isMissed = call.status == CallStatus.MISSED || call.status == CallStatus.REJECTED

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        WesalAvatar(imageUrl = peerAvatar, name = peerName)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peerName.ifBlank { "مستخدم وصال" },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isMissed) WesalError else WesalTextPrimary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        isMissed -> Icons.AutoMirrored.Filled.CallMissed
                                        outgoing -> Icons.AutoMirrored.Filled.CallMade
                                        else -> Icons.AutoMirrored.Filled.CallReceived
                                    },
                                    contentDescription = null,
                                    tint = if (isMissed) WesalError else WesalEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${DateTimeUtils.formatChatListTime(call.startedAt)} • ${DateTimeUtils.formatDurationSeconds(call.durationSeconds)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WesalTextSecondary
                                )
                            }
                        }
                        IconButton(onClick = {
                            val peer = WesalUser(uid = peerUid, displayName = peerName, photoUrl = peerAvatar)
                            context.startActivity(CallActivity.createOutgoingIntent(context, peer, isVideo = call.isVideo))
                        }) {
                            Icon(
                                imageVector = if (call.isVideo) Icons.Default.Videocam else Icons.Default.Call,
                                contentDescription = "إعادة الاتصال",
                                tint = WesalTurquoise
                            )
                        }
                    }
                }
            }
        }
    }
}


object CallsClearState {
    var clearedAt by androidx.compose.runtime.mutableLongStateOf(0L)
    fun load(ctx: android.content.Context) {
        clearedAt = ctx.getSharedPreferences("wesal_calls", android.content.Context.MODE_PRIVATE).getLong("cleared", 0L)
    }
    fun clear(ctx: android.content.Context) {
        clearedAt = System.currentTimeMillis()
        ctx.getSharedPreferences("wesal_calls", android.content.Context.MODE_PRIVATE).edit().putLong("cleared", clearedAt).apply()
    }
}
