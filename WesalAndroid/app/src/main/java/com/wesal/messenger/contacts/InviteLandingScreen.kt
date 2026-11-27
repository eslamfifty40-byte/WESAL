package com.wesal.messenger.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.domain.model.ContactRequest
import com.wesal.messenger.domain.model.ContactRequestStatus
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.wesalNeonTextStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InviteLandingUiState(
    val isLoading: Boolean = true,
    val inviter: WesalUser? = null,
    val error: String? = null,
    val actionInProgress: Boolean = false
)

@HiltViewModel
class InviteLandingViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(InviteLandingUiState())
    val state: StateFlow<InviteLandingUiState> = _state.asStateFlow()

    val myUid: String get() = authRepository.currentUserId.orEmpty()

    val isSuperAdmin: StateFlow<Boolean> = userRepository.observeIsSuperAdmin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val contactRequests: StateFlow<List<ContactRequest>> = userRepository.observeContactRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadInviter(inviteId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            // Invite/referral resolution must find the inviter even when their
            // account is hidden from search (e.g. admin 10002) — the guarded
            // resolver would report a valid invite as not found.
            val user = runCatching { userRepository.findUserByPublicIdOrUidUnfiltered(inviteId) }.getOrNull()
            _state.update {
                it.copy(
                    isLoading = false,
                    inviter = user,
                    error = if (user == null) "invite_not_found" else null
                )
            }
        }
    }

    fun sendRequest(targetUid: String, onFeedback: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(actionInProgress = true) }
            userRepository.sendContactRequest(targetUid)
                .onFailure { err -> onFeedback(err.message ?: "تعذر إرسال الطلب") }
            _state.update { it.copy(actionInProgress = false) }
        }
    }

    fun acceptRequest(requestId: String, onFeedback: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(actionInProgress = true) }
            userRepository.acceptContactRequest(requestId)
                .onFailure { err -> onFeedback(err.message ?: "تعذر قبول الطلب") }
            _state.update { it.copy(actionInProgress = false) }
        }
    }

    fun openChat(peerUid: String, onSuccess: (String) -> Unit, onFeedback: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(actionInProgress = true) }
            chatRepository.getOrCreateDirectChat(peerUid)
                .onSuccess(onSuccess)
                .onFailure { err -> onFeedback(err.message ?: "تعذر فتح المحادثة") }
            _state.update { it.copy(actionInProgress = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteLandingScreen(
    inviteId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenUserProfile: (String) -> Unit,
    viewModel: InviteLandingViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requests by viewModel.contactRequests.collectAsStateWithLifecycle()
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(inviteId) {
        viewModel.loadInviter(inviteId)
    }

    val inviter = state.inviter
    val myUid = viewModel.myUid
    val isHiddenOrUnavailable = inviter != null &&
        ((inviter.hiddenFromSearch && !isSuperAdmin && inviter.uid != myUid) || inviter.deletedAt > 0L)

    val matchingReq = remember(requests, inviter?.uid, myUid) {
        if (inviter == null) null
        else requests.firstOrNull {
            (it.senderUid == myUid && it.receiverUid == inviter.uid) ||
                (it.senderUid == inviter.uid && it.receiverUid == myUid)
        }
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = { Text(if (strings.isEnglish) "WESAL Invitation" else "دعوة WESAL", color = palette.textPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(color = palette.emerald)

                inviter == null || isHiddenOrUnavailable -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isHiddenOrUnavailable) Icons.Default.PersonOff else Icons.Default.PersonSearch,
                                contentDescription = null,
                                tint = palette.textSecondary,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = if (isHiddenOrUnavailable) {
                                    if (strings.isEnglish) "This account is currently unavailable" else "هذا الحساب غير متاح حاليًا"
                                } else {
                                    if (strings.isEnglish) "Could not find WESAL account \"$inviteId\"" else "تعذر العثور على حساب WESAL الخاص بالدعوة «$inviteId»"
                                },
                                color = palette.textPrimary,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = palette.emerald)) {
                                Text(strings.back)
                            }
                        }
                    }
                }

                else -> {
                    val isSelf = inviter.uid == myUid
                    val canDirectMessage = isSuperAdmin ||
                        inviter.numericId == Constants.OWNER_ADMIN_NUMERIC_ID ||
                        matchingReq?.status == ContactRequestStatus.ACCEPTED

                    Card(
                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            WesalAvatar(
                                imageUrl = inviter.photoUrl,
                                name = inviter.displayName.ifBlank { "@${inviter.publicId}" },
                                size = 92.dp,
                                isOnline = inviter.isOnline,
                                frameId = inviter.frameId,
                                frameExpiresAt = inviter.frameExpiresAt,
                                isCutout = inviter.photoRemovedBg,
                                isUserFramed = inviter.photoUserFramed,
                                iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(inviter.iconId, inviter.iconUrl),
                                neonColor = if (inviter.neonEnabled) inviter.neonColor else "",
                                neonGlowIntensity = inviter.neonGlowIntensity
                            )

                            WesalNameWithBadge(
                                name = inviter.displayName.ifBlank { "WESAL User" },
                                isVerified = inviter.isVerified,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                verificationType = inviter.verificationType,
                                neonColor = inviter.neonColor,
                                neonGlowIntensity = inviter.neonGlowIntensity,
                                neonEnabled = inviter.neonEnabled,
                                iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(inviter.iconId, inviter.iconUrl)
                            )

                            if (inviter.numericId.isNotBlank()) {
                                Text(
                                    text = "ID: ${inviter.numericId}",
                                    style = wesalNeonTextStyle(MaterialTheme.typography.titleSmall, inviter.neonColor, inviter.neonGlowIntensity, palette.turquoise),
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (inviter.publicId.isNotBlank()) {
                                Text(
                                    text = "@${inviter.publicId}",
                                    style = wesalNeonTextStyle(MaterialTheme.typography.titleSmall, inviter.neonColor, inviter.neonGlowIntensity, palette.emerald),
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            if (inviter.bio.isNotBlank()) {
                                Text(
                                    text = inviter.bio,
                                    style = wesalNeonTextStyle(MaterialTheme.typography.bodyMedium, inviter.neonColor, inviter.neonGlowIntensity, palette.textSecondary),
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (isSelf) {
                                Text(
                                    text = if (strings.isEnglish) "This is your own WESAL invite link." else "هذا هو رابط دعوتك الشخصي على WESAL.",
                                    color = palette.textSecondary,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                when {
                                    canDirectMessage -> {
                                        Button(
                                            onClick = { viewModel.openChat(inviter.uid, onOpenChat) { feedback = it } },
                                            enabled = !state.actionInProgress,
                                            colors = ButtonDefaults.buttonColors(containerColor = palette.emerald),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Chat, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(if (strings.isEnglish) "Start Chatting" else "ابدأ المحادثة الآن")
                                        }
                                    }

                                    matchingReq?.status == ContactRequestStatus.PENDING -> {
                                        if (matchingReq.receiverUid == myUid) {
                                            Button(
                                                onClick = { viewModel.acceptRequest(matchingReq.requestId) { feedback = it } },
                                                enabled = !state.actionInProgress,
                                                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text(strings.acceptContactRequest)
                                            }
                                        } else {
                                            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                                                Icon(Icons.Default.HourglassTop, contentDescription = null)
                                                Spacer(Modifier.width(8.dp))
                                                Text(strings.pendingRequestSent)
                                            }
                                        }
                                    }

                                    else -> {
                                        Button(
                                            onClick = { viewModel.sendRequest(inviter.uid) { feedback = it } },
                                            enabled = !state.actionInProgress,
                                            colors = ButtonDefaults.buttonColors(containerColor = palette.emerald),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(strings.sendContactRequest)
                                        }
                                    }
                                }

                                TextButton(onClick = { onOpenUserProfile(inviter.uid) }) {
                                    Text(
                                        text = if (strings.isEnglish) "View Full Profile" else "عرض الملف الشخصي",
                                        color = palette.turquoise
                                    )
                                }
                            }

                            feedback?.let { msg ->
                                Text(msg, color = palette.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
