package com.wesal.messenger.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    callRepository: CallRepository,
    authRepository: AuthRepository,
    private val userRepository: UserRepository
) : ViewModel() {
    val myUid = authRepository.currentUserId.orEmpty()
    val history: StateFlow<List<CallRecord>> = callRepository.observeCallHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _discoverableUsers = MutableStateFlow<List<WesalUser>>(emptyList())
    val discoverableUsers: StateFlow<List<WesalUser>> = _discoverableUsers.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<WesalUser>>(emptyList())
    val searchResults: StateFlow<List<WesalUser>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    init {
        loadDiscoverableUsers()
    }

    fun loadDiscoverableUsers() {
        viewModelScope.launch {
            runCatching { userRepository.listDiscoverableUsers(limit = 30) }
                .onSuccess { _discoverableUsers.value = it }
        }
    }

    fun searchUsers(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            runCatching { userRepository.searchUsers(query) }
                .onSuccess { _searchResults.value = it }
            _isSearching.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(viewModel: CallHistoryViewModel = hiltViewModel()) {
    val calls by viewModel.history.collectAsStateWithLifecycle()
    val discoverableUsers by viewModel.discoverableUsers.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showNewCallSheet by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(WesalDeepBlack)) {
        if (calls.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("لا توجد مكالمات مسجلة حتى الآن", color = WesalTextSecondary)
                    Button(
                        onClick = {
                            viewModel.loadDiscoverableUsers()
                            showNewCallSheet = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WesalEmerald,
                            contentColor = WesalDeepBlack
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.AddIcCall, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("بدء مكالمة جديدة", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
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

        ExtendedFloatingActionButton(
            onClick = {
                viewModel.loadDiscoverableUsers()
                showNewCallSheet = true
            },
            containerColor = WesalEmerald,
            contentColor = WesalDeepBlack,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.AddIcCall, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("مكالمة جديدة", fontWeight = FontWeight.Bold)
        }
    }

    if (showNewCallSheet) {
        val displayList = if (searchQuery.isNotBlank()) searchResults else discoverableUsers
        ModalBottomSheet(
            onDismissRequest = { showNewCallSheet = false },
            containerColor = WesalSurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "مكالمة جديدة",
                        style = MaterialTheme.typography.titleLarge,
                        color = WesalTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { showNewCallSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = WesalTextSecondary)
                    }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::searchUsers,
                    placeholder = { Text("ابحث بالاسم أو @Wesal ID...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = WesalTurquoise) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                if (isSearching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = WesalEmerald)
                }
                if (displayList.isEmpty() && !isSearching) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "لا توجد نتائج مطابقة" else "لا يوجد مستخدمون متاحون حالياً",
                        color = WesalTextSecondary,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayList, key = { it.uid }) { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                WesalAvatar(imageUrl = user.photoUrl, name = user.displayName, isOnline = user.isOnline)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.displayName.ifBlank { "@${user.publicId}" },
                                        color = WesalTextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            user.publicId.takeIf { it.isNotBlank() }?.let { "@$it" },
                                            user.email.takeIf { it.isNotBlank() }
                                        ).joinToString(" • "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WesalTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = {
                                    showNewCallSheet = false
                                    context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo = false))
                                }) {
                                    Icon(Icons.Default.Call, contentDescription = "مكالمة صوتية", tint = WesalEmerald)
                                }
                                IconButton(onClick = {
                                    showNewCallSheet = false
                                    context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo = true))
                                }) {
                                    Icon(Icons.Default.Videocam, contentDescription = "مكالمة فيديو", tint = WesalTurquoise)
                                }
                            }
                            HorizontalDivider(color = WesalSurfaceBorder, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}
