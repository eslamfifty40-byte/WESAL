package com.wesal.messenger.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
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
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.domain.model.VerificationAuditLog
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class AdminVerificationViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    val isSuperAdmin: StateFlow<Boolean> = userRepository.observeIsSuperAdmin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val auditLogs: StateFlow<List<VerificationAuditLog>> = userRepository.observeVerificationAuditLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _users = MutableStateFlow<List<WesalUser>>(emptyList())
    val users: StateFlow<List<WesalUser>> = _users.asStateFlow()

    private val _busyUid = MutableStateFlow<String?>(null)
    val busyUid: StateFlow<String?> = _busyUid.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            isSuperAdmin.collect { allowed ->
                if (!allowed) {
                    _users.value = emptyList()
                } else {
                    refreshUsers()
                }
            }
        }
    }

    fun updateSearch(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250L)
            refreshUsers()
        }
    }

    fun refreshUsers() {
        viewModelScope.launch {
            if (!userRepository.isSuperAdmin()) {
                _users.value = emptyList()
                return@launch
            }
            val q = _searchQuery.value.trim()
            val list = runCatching {
                if (q.isBlank()) userRepository.listDiscoverableUsers(limit = 40)
                else userRepository.searchUsers(q)
            }.getOrDefault(emptyList())
            _users.value = list.sortedByDescending { it.isVerified }
        }
    }

    fun toggleVerification(target: WesalUser, verified: Boolean, note: String) {
        viewModelScope.launch {
            _busyUid.value = target.uid
            userRepository.setAccountVerification(target.uid, verified, note)
                .onSuccess {
                    _users.update { current ->
                        current.map { u ->
                            if (u.uid == target.uid) u.copy(isVerified = verified, verificationNote = note) else u
                        }
                    }
                    _statusMessage.value = if (verified) {
                        "تم منح شارة التوثيق لـ @${target.publicId.ifBlank { target.displayName }}"
                    } else {
                        "تمت إزالة شارة التوثيق"
                    }
                }
                .onFailure { err ->
                    _statusMessage.value = err.message ?: "تعذر تحديث حالة التوثيق"
                }
            _busyUid.value = null
        }
    }

    fun clearMessage() {
        _statusMessage.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminVerificationScreen(
    onBack: () -> Unit,
    onOpenUserProfile: (String) -> Unit,
    viewModel: AdminVerificationViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val logs by viewModel.auditLogs.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val busyUid by viewModel.busyUid.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(0) } // 0 = Users, 1 = Verified Only, 2 = Audit Logs
    var dialogTarget by remember { mutableStateOf<WesalUser?>(null) }
    var dialogTargetVerify by remember { mutableStateOf(true) }
    var noteInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.adminVerificationTitle,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        if (!isSuperAdmin) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = palette.error, modifier = Modifier.size(48.dp))
                    Text(
                        text = if (strings.isEnglish) {
                            "Restricted to the primary WESAL Super Admin."
                        } else {
                            "هذه اللوحة مخصصة لمدير النظام الرئيسي (Super Admin) فقط."
                        },
                        color = palette.textPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            statusMessage?.let { msg ->
                Surface(
                    color = palette.emerald.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, color = palette.emerald, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = viewModel::clearMessage, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = strings.close, tint = palette.emerald)
                        }
                    }
                }
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = palette.surface,
                contentColor = palette.emerald
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(if (strings.isEnglish) "Accounts" else "الحسابات", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(strings.verifiedUsersTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(strings.auditLogsTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }

            if (selectedTab != 2) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::updateSearch,
                    placeholder = { Text(strings.searchContactsPlaceholder) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.turquoise) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateSearch("") }) {
                                Icon(Icons.Default.Close, contentDescription = strings.clear, tint = palette.textSecondary)
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedPlaceholderColor = palette.textMuted,
                        unfocusedPlaceholderColor = palette.textMuted,
                        focusedBorderColor = palette.turquoise,
                        unfocusedBorderColor = palette.border,
                        cursorColor = palette.turquoise
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )

                val visibleUsers = if (selectedTab == 1) users.filter { it.isVerified } else users
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleUsers, key = { it.uid }) { user ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = palette.surface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenUserProfile(user.uid) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                WesalAvatar(
                                    imageUrl = user.photoUrl,
                                    name = user.displayName.ifBlank { "@${user.publicId}" },
                                    size = 46.dp,
                                    isOnline = user.isOnline
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    WesalNameWithBadge(
                                        name = user.displayName.ifBlank { "WESAL User" },
                                        isVerified = user.isVerified,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val subIds = listOfNotNull(
                                        user.numericId.takeIf { it.isNotBlank() }?.let { "ID: $it" },
                                        user.publicId.takeIf { it.isNotBlank() }?.let { "@$it" }
                                    ).joinToString(" • ")
                                    if (subIds.isNotBlank()) {
                                        Text(
                                            text = subIds,
                                            color = palette.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        text = if (user.isVerified) strings.verifiedAccountLabel else strings.unverifiedAccountLabel,
                                        color = if (user.isVerified) palette.verifiedBlue else palette.textMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (user.verificationNote.isNotBlank()) {
                                        Text(
                                            text = user.verificationNote,
                                            color = palette.verifiedBlue,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (busyUid == user.uid) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = palette.verifiedBlue)
                                } else {
                                    FilledTonalButton(
                                        onClick = {
                                            dialogTarget = user
                                            dialogTargetVerify = !user.isVerified
                                            noteInput = user.verificationNote
                                        },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (user.isVerified) palette.error.copy(alpha = 0.14f) else palette.verifiedBlue.copy(alpha = 0.16f),
                                            contentColor = if (user.isVerified) palette.error else palette.verifiedBlue
                                        )
                                    ) {
                                        com.wesal.messenger.presentation.components.WesalVerifiedMark(androidx.compose.ui.unit.Dp(16f))
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (user.isVerified) strings.revokeVerifiedBadge else strings.grantVerifiedBadge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs, key = { it.logId }) { log ->
                        val dateText = remember(log.timestamp) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(log.timestamp))
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = palette.surface),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "${log.targetName.ifBlank { log.targetUid }} (@${log.targetPublicId})",
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = log.action,
                                        color = if (log.action == "GRANTED") palette.verifiedBlue else palette.error,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                if (log.note.isNotBlank()) {
                                    Text(log.note, color = palette.textSecondary, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    text = "${log.adminEmail} • $dateText",
                                    color = palette.textMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    dialogTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { dialogTarget = null },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (dialogTargetVerify) strings.grantVerifiedBadge else strings.revokeVerifiedBadge,
                    color = palette.textPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WesalNameWithBadge(
                        name = target.displayName.ifBlank { "@${target.publicId}" },
                        isVerified = target.isVerified
                    )
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text(strings.verificationNoteLabel) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            focusedLabelColor = palette.turquoise,
                            unfocusedLabelColor = palette.textSecondary,
                            focusedBorderColor = palette.turquoise,
                            unfocusedBorderColor = palette.border,
                            cursorColor = palette.turquoise
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.toggleVerification(target, dialogTargetVerify, noteInput)
                        dialogTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dialogTargetVerify) palette.verifiedBlue else palette.error
                    )
                ) {
                    Text(strings.confirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogTarget = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}
