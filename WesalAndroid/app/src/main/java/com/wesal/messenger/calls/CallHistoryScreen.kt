package com.wesal.messenger.calls

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.domain.model.CallRecord
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.CallRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CallHistoryViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository
) : ViewModel() {
    val myUid: String
        get() = authRepository.currentUserId.orEmpty()
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

    private val _selectedCallIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedCallIds: StateFlow<Set<String>> = _selectedCallIds.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.observeAuthState()
                .distinctUntilChanged()
                .collect { uid ->
                    searchJob?.cancel()
                    _selectedCallIds.value = emptySet()
                    _searchQuery.value = ""
                    _searchResults.value = emptyList()
                    _discoverableUsers.value = emptyList()
                    if (uid != null) {
                        loadDiscoverableUsers()
                    }
                }
        }
    }

    fun loadDiscoverableUsers() {
        viewModelScope.launch {
            runCatching { userRepository.listDiscoverableUsers(limit = 30) }
                .onSuccess { _discoverableUsers.value = it }
        }
    }

    fun searchUsers(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            delay(250L)
            runCatching { userRepository.searchUsers(query) }
                .onSuccess { _searchResults.value = it }
            _isSearching.value = false
        }
    }

    fun toggleSelection(callId: String) {
        _selectedCallIds.update { current ->
            if (callId in current) current - callId else current + callId
        }
    }

    fun clearSelection() {
        _selectedCallIds.value = emptySet()
    }

    fun deleteSingleCall(callId: String) {
        viewModelScope.launch {
            callRepository.deleteCallForMe(callId)
            _selectedCallIds.update { it - callId }
        }
    }

    fun deleteSelectedCalls() {
        val ids = _selectedCallIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            callRepository.deleteCallsForMe(ids)
            _selectedCallIds.value = emptySet()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            callRepository.clearCallHistoryForMe()
            _selectedCallIds.value = emptySet()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CallHistoryScreen(viewModel: CallHistoryViewModel = hiltViewModel()) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val calls by viewModel.history.collectAsStateWithLifecycle()
    val discoverableUsers by viewModel.discoverableUsers.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedCallIds.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showNewCallSheet by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmDeleteSingleCall by remember { mutableStateOf<CallRecord?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        if (calls.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(strings.noCallsYet, color = palette.textSecondary)
                    Button(
                        onClick = {
                            viewModel.loadDiscoverableUsers()
                            showNewCallSheet = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.emerald,
                            contentColor = palette.onPrimary
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.AddIcCall, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.newCall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(color = palette.surface, tonalElevation = 1.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (selectedIds.isNotEmpty()) {
                            Text(
                                text = if (strings.isEnglish) "${selectedIds.size} selected" else "تم تحديد ${selectedIds.size}",
                                color = palette.emerald,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = viewModel::deleteSelectedCalls) {
                                    Text(strings.deleteSelectedCalls, color = palette.error)
                                }
                                TextButton(onClick = viewModel::clearSelection) {
                                    Text(strings.cancel, color = palette.textSecondary)
                                }
                            }
                        } else {
                            Text(
                                text = strings.tabCalls,
                                color = palette.textSecondary,
                                style = MaterialTheme.typography.labelLarge
                            )
                            TextButton(onClick = { confirmClearAll = true }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(strings.clearAllCalls, color = palette.error)
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(calls, key = { it.callId }) { call ->
                        val outgoing = call.callerId == viewModel.myUid
                        val peerName = if (outgoing) call.calleeName else call.callerName
                        val peerAvatar = if (outgoing) call.calleeAvatarUrl else call.callerAvatarUrl
                        val peerUid = if (outgoing) call.calleeId else call.callerId
                        val peerVerified = if (outgoing) call.calleeVerified else call.callerVerified
                        val isMissed = call.status == CallStatus.MISSED || call.status == CallStatus.REJECTED
                        val isSelected = call.callId in selectedIds

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) palette.emerald.copy(alpha = 0.12f)
                                    else palette.background
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (selectedIds.isNotEmpty()) {
                                            viewModel.toggleSelection(call.callId)
                                        }
                                    },
                                    onLongClick = { viewModel.toggleSelection(call.callId) }
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (selectedIds.isNotEmpty()) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { viewModel.toggleSelection(call.callId) }
                                )
                            }
                            WesalAvatar(imageUrl = peerAvatar, name = peerName)
                            Column(modifier = Modifier.weight(1f)) {
                                WesalNameWithBadge(
                                    name = peerName.ifBlank { "WESAL User" },
                                    isVerified = peerVerified,
                                    color = if (isMissed) palette.error else palette.textPrimary
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
                                        tint = if (isMissed) palette.error else palette.emerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "${DateTimeUtils.formatChatListTime(call.startedAt)} • ${DateTimeUtils.formatDurationSeconds(call.durationSeconds)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary
                                    )
                                }
                            }
                            IconButton(onClick = { confirmDeleteSingleCall = call }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = if (strings.isEnglish) "Delete for me" else "حذف لدي",
                                    tint = palette.textMuted
                                )
                            }
                            IconButton(onClick = {
                                val peer = WesalUser(
                                    uid = peerUid,
                                    displayName = peerName,
                                    photoUrl = peerAvatar,
                                    isVerified = peerVerified
                                )
                                context.startActivity(CallActivity.createOutgoingIntent(context, peer, isVideo = call.isVideo))
                            }) {
                                Icon(
                                    imageVector = if (call.isVideo) Icons.Default.Videocam else Icons.Default.Call,
                                    contentDescription = strings.newCall,
                                    tint = palette.turquoise
                                )
                            }
                        }
                        HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = {
                viewModel.loadDiscoverableUsers()
                showNewCallSheet = true
            },
            containerColor = palette.emerald,
            contentColor = palette.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.AddIcCall, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(strings.newCall, fontWeight = FontWeight.Bold)
        }
    }

    confirmDeleteSingleCall?.let { call ->
        AlertDialog(
            onDismissRequest = { confirmDeleteSingleCall = null },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Delete Call Entry" else "حذف لدي",
                    color = palette.textPrimary
                )
            },
            text = {
                Text(
                    text = if (strings.isEnglish) {
                        "Delete this call record from your history only?"
                    } else {
                        "سيتم حذف هذا السجل من جهازك وحسابك فقط دون التأثير على الطرف الآخر."
                    },
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSingleCall(call.callId)
                        confirmDeleteSingleCall = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                ) {
                    Text(if (strings.isEnglish) "Delete for me" else "حذف لدي")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSingleCall = null }) { Text(strings.cancel) }
            }
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            containerColor = palette.surface,
            title = { Text(strings.confirmClearCallsTitle, color = palette.textPrimary) },
            text = { Text(strings.confirmClearCallsMessage, color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllHistory()
                        confirmClearAll = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                ) {
                    Text(strings.clearAllCalls)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text(strings.cancel) }
            }
        )
    }

    if (showNewCallSheet) {
        val displayList = if (searchQuery.isNotBlank()) searchResults else discoverableUsers
        ModalBottomSheet(
            onDismissRequest = { showNewCallSheet = false },
            containerColor = palette.surface
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
                        text = strings.newCall,
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { showNewCallSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = strings.close, tint = palette.textSecondary)
                    }
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::searchUsers,
                    placeholder = { Text(strings.searchContactsPlaceholder) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.turquoise) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedPlaceholderColor = palette.textMuted,
                        unfocusedPlaceholderColor = palette.textMuted,
                        focusedBorderColor = palette.turquoise,
                        unfocusedBorderColor = palette.border,
                        cursorColor = palette.turquoise
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                if (isSearching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.emerald)
                }
                if (displayList.isEmpty() && !isSearching) {
                    Text(
                        text = if (strings.isEnglish) "No matching users found" else "لا توجد نتائج مطابقة",
                        color = palette.textSecondary,
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
                                    WesalNameWithBadge(
                                        name = user.displayName.ifBlank { "@${user.publicId}" },
                                        isVerified = user.isVerified,
                                        verificationType = user.verificationType,
                                        neonColor = user.neonColor,
                                        neonGlowIntensity = user.neonGlowIntensity,
                                        neonEnabled = user.neonEnabled,
                                        fontWeight = FontWeight.Medium,
                                        iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl)
                                    )
                                    val callSub = listOfNotNull(
                                        user.numericId.takeIf { it.isNotBlank() }?.let { "ID: $it" },
                                        user.publicId.takeIf { it.isNotBlank() }?.let { "@$it" }
                                    ).joinToString(" • ")
                                    if (callSub.isNotBlank()) {
                                        Text(
                                            text = callSub,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    showNewCallSheet = false
                                    context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo = false))
                                }) {
                                    Icon(Icons.Default.Call, contentDescription = null, tint = palette.emerald)
                                }
                                IconButton(onClick = {
                                    showNewCallSheet = false
                                    context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo = true))
                                }) {
                                    Icon(Icons.Default.Videocam, contentDescription = null, tint = palette.turquoise)
                                }
                            }
                            HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}
