package com.wesal.messenger.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wesal.messenger.calls.CallActivity
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.domain.model.BusinessHoursSchedule
import com.wesal.messenger.domain.model.ContactRequest
import com.wesal.messenger.domain.model.ContactRequestStatus
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.media.FullscreenImageViewer
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.wesalImageModel
import com.wesal.messenger.presentation.components.wesalNeonTextStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class UserProfileDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    val targetUid: String = checkNotNull(savedStateHandle["uid"])
    val myUid: String
        get() = authRepository.currentUserId.orEmpty()

    val peerUser: StateFlow<WesalUser?> = userRepository.observeUser(targetUid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isSuperAdmin: StateFlow<Boolean> = userRepository.observeIsSuperAdmin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val contactRequests: StateFlow<List<ContactRequest>> = userRepository.observeContactRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var visitRecorded = false

    fun recordVisitOnce() {
        if (visitRecorded || targetUid == myUid) return
        visitRecorded = true
        viewModelScope.launch {
            userRepository.recordProfileVisit(targetUid)
        }
    }

    fun sendContactRequest() {
        viewModelScope.launch {
            userRepository.sendContactRequest(targetUid)
                .onFailure { _statusMessage.value = it.localizedMessage }
        }
    }

    fun acceptContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.acceptContactRequest(requestId)
                .onFailure { _statusMessage.value = it.localizedMessage }
        }
    }

    fun rejectContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.rejectContactRequest(requestId)
                .onFailure { _statusMessage.value = it.localizedMessage }
        }
    }

    fun startDirectChat(onOpenChat: (String) -> Unit) {
        viewModelScope.launch {
            chatRepository.getOrCreateDirectChat(targetUid)
                .onSuccess(onOpenChat)
                .onFailure { _statusMessage.value = it.localizedMessage }
        }
    }

    fun toggleBlock(currentlyBlocked: Boolean, isEn: Boolean) {
        viewModelScope.launch {
            val res = if (currentlyBlocked) userRepository.unblockUser(targetUid) else userRepository.blockUser(targetUid)
            res.onSuccess {
                _statusMessage.value = if (currentlyBlocked) {
                    if (isEn) "User unblocked" else "تم إلغاء حظر المستخدم"
                } else {
                    if (isEn) "User blocked" else "تم حظر المستخدم"
                }
            }
        }
    }

    fun reportUser(reason: String, isEn: Boolean) {
        viewModelScope.launch {
            userRepository.reportUser(targetUid, reason, targetType = "USER").onSuccess {
                _statusMessage.value = if (isEn) "Report submitted" else "تم إرسال البلاغ بنجاح"
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileDetailScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: UserProfileDetailViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val peer by viewModel.peerUser.collectAsStateWithLifecycle()
    val me by viewModel.currentUser.collectAsStateWithLifecycle()
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val requests by viewModel.contactRequests.collectAsStateWithLifecycle()
    val statusMsg by viewModel.statusMessage.collectAsStateWithLifecycle()

    var fullscreenMediaUrl by remember { mutableStateOf<String?>(null) }
    var showReportDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMsg) {
        statusMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    LaunchedEffect(peer?.uid) {
        if (peer != null) {
            viewModel.recordVisitOnce()
        }
    }

    val target = peer
    val showAvatar = target != null &&
        target.photoUrl.isNotBlank() &&
        target.privacySettings.profilePhotoVisibility != PrivacyVisibility.NOBODY

    if (!fullscreenMediaUrl.isNullOrBlank()) {
        FullscreenImageViewer(imageModel = fullscreenMediaUrl!!, onClose = { fullscreenMediaUrl = null })
        return
    }

    val req = remember(requests, target?.uid, viewModel.myUid) {
        if (target == null) null
        else requests.firstOrNull {
            (it.senderUid == target.uid && it.receiverUid == viewModel.myUid) ||
                (it.receiverUid == target.uid && it.senderUid == viewModel.myUid)
        }
    }

    fun openExternalUrl(rawUrl: String) {
        val clean = rawUrl.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
        }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(clean)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Scaffold(
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (strings.isEnglish) "Account Details" else "بيانات الحساب", color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        if (target == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.turquoise)
            }
        } else if ((target.hiddenFromSearch && !isSuperAdmin && target.uid != viewModel.myUid) || target.deletedAt > 0L) {
            // Section 14 & Section 40 privacy/deleted safeguard
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.PersonOff, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(56.dp))
                    Text(
                        text = if (strings.isEnglish) "This account is currently unavailable" else "هذا الحساب غير متاح حاليًا",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            val isBlocked = me?.blockedUsers?.contains(target.uid) == true
            val canSeeOnline = target.privacySettings.onlineVisibility != PrivacyVisibility.NOBODY
            val canSeeLastSeen = target.privacySettings.lastSeenVisibility != PrivacyVisibility.NOBODY
            val presenceLabel = when {
                canSeeOnline && target.isOnline -> strings.onlineNow
                canSeeLastSeen && target.lastSeen > 0L -> DateTimeUtils.formatChatListTime(target.lastSeen)
                else -> if (strings.isEnglish) "Privacy enabled" else "الخصوصية مفعّلة"
            }
            val neonColor = target.neonColor
            val neonGlow = target.neonGlowIntensity
            val canMessageDirectly = isSuperAdmin ||
                target.numericId == Constants.OWNER_ADMIN_NUMERIC_ID ||
                req?.status == ContactRequestStatus.ACCEPTED

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 16:9 Cover + Avatar Header
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (target.coverUrl.isNotBlank()) {
                            AsyncImage(
                                model = wesalImageModel(target.coverUrl),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { fullscreenMediaUrl = target.coverUrl },
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.height(54.dp))
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    Box(modifier = Modifier.clickable(enabled = showAvatar) { fullscreenMediaUrl = target.photoUrl }) {
                        WesalAvatar(
                            imageUrl = if (showAvatar) target.photoUrl else "",
                            name = target.displayName.ifBlank { target.publicId },
                            size = 114.dp,
                            isOnline = canSeeOnline && target.isOnline,
                            frameId = target.frameId,
                            frameExpiresAt = target.frameExpiresAt,
                            isCutout = target.photoRemovedBg,
                            iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(target.iconId, target.iconUrl),
                            neonColor = if (target.neonEnabled) target.neonColor else "",
                            neonGlowIntensity = neonGlow
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WesalNameWithBadge(
                        name = target.displayName.ifBlank { "@${target.publicId}" },
                        isVerified = target.isVerified,
                        style = MaterialTheme.typography.headlineSmall,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        verificationType = target.verificationType,
                        neonColor = neonColor,
                        neonGlowIntensity = neonGlow,
                        neonEnabled = target.neonEnabled
                    )
                    if (target.numericId.isNotBlank()) {
                        Surface(
                            color = palette.surfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.4f)),
                            modifier = Modifier.clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("wesal_numeric_id", target.numericId))
                                Toast.makeText(context, "${strings.wesalNumericIdCopied} ${target.numericId}", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "ID: ${target.numericId}",
                                    style = wesalNeonTextStyle(MaterialTheme.typography.titleSmall, neonColor, neonGlow, palette.turquoise),
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(Icons.Default.ContentCopy, contentDescription = strings.copy, tint = palette.turquoise, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    if (target.publicId.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("wesal_id", "@${target.publicId}"))
                                Toast.makeText(context, "@${target.publicId}", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(
                                text = "@${target.publicId}",
                                style = wesalNeonTextStyle(MaterialTheme.typography.titleMedium, neonColor, neonGlow, palette.textSecondary),
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(15.dp))
                        }
                    }
                    Text(
                        text = presenceLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canSeeOnline && target.isOnline) palette.emerald else palette.textSecondary
                    )
                }

                // Action Buttons (Sec 32: Clean horizontal non-wrapping buttons; Sec 24/25: Support 10001 / Admin bypass)
                if (target.uid != viewModel.myUid) {
                    when {
                        canMessageDirectly -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.startDirectChat(onOpenChat) },
                                    modifier = Modifier.weight(1.1f).height(46.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (strings.isEnglish) "Message" else "مراسلة",
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedButton(
                                    onClick = { context.startActivity(CallActivity.createOutgoingIntent(context, target, isVideo = false)) },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, tint = palette.emerald, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (strings.isEnglish) "Call" else "اتصال",
                                        color = palette.textPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedButton(
                                    onClick = { context.startActivity(CallActivity.createOutgoingIntent(context, target, isVideo = true)) },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Videocam, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (strings.isEnglish) "Video" else "فيديو",
                                        color = palette.textPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        req?.status == ContactRequestStatus.PENDING && req.receiverUid == viewModel.myUid -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { viewModel.acceptContactRequest(req.requestId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent),
                                    modifier = Modifier.weight(1f)
                                ) { Text(strings.acceptContactRequest, fontWeight = FontWeight.Bold, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { viewModel.rejectContactRequest(req.requestId) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(strings.rejectContactRequest, color = palette.error, maxLines = 1) }
                            }
                        }
                        req?.status == ContactRequestStatus.PENDING && req.senderUid == viewModel.myUid -> {
                            Surface(color = palette.surfaceElevated, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.HourglassTop, contentDescription = null, tint = palette.turquoise)
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.pendingRequestSent, color = palette.turquoise, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        else -> {
                            Button(
                                onClick = { viewModel.sendContactRequest() },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.sendContactRequest, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Public Info Card (Never displays email; strictly honors hidePhoneNumber & hideBirthDate)
                Surface(
                    color = palette.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.phoneNumberLabel, style = MaterialTheme.typography.labelMedium, color = palette.turquoise)
                        Text(
                            text = if (target.privacySettings.hidePhoneNumber || target.phoneNumber.isBlank()) {
                                strings.phoneHiddenValue
                            } else {
                                target.phoneNumber
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textPrimary
                        )

                        if (!target.privacySettings.hideBirthDate && target.birthDate.isNotBlank()) {
                            HorizontalDivider(color = palette.border)
                            Text(if (strings.isEnglish) "Birth Date" else "تاريخ الميلاد", style = MaterialTheme.typography.labelMedium, color = palette.turquoise)
                            Text(target.birthDate, style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary)
                        }

                        HorizontalDivider(color = palette.border)
                        Text(if (strings.isEnglish) "Bio" else "نبذة عن الحساب", style = MaterialTheme.typography.labelMedium, color = palette.turquoise)
                        Text(
                            text = target.bio.ifBlank { "WESAL Messenger" },
                            style = wesalNeonTextStyle(MaterialTheme.typography.bodyLarge, neonColor, neonGlow, palette.textPrimary)
                        )
                    }
                }

                // Social Links (Sec 28)
                if (target.facebookUrl.isNotBlank() || target.instagramUrl.isNotBlank()) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (strings.isEnglish) "Social Links" else "روابط التواصل الاجتماعي",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.turquoise
                            )
                            if (target.facebookUrl.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { openExternalUrl(target.facebookUrl) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Public, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Facebook", color = palette.textPrimary, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                                }
                            }
                            if (target.instagramUrl.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { openExternalUrl(target.instagramUrl) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Instagram", color = palette.textPrimary, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Business Hours Display (Sec 29)
                if (target.businessHours.enabled) {
                    val isOpenNow = remember(target.businessHours) { computeIsOpenNow(target.businessHours) }
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = target.businessHours.businessName.ifBlank {
                                        if (strings.isEnglish) "Business Hours" else "ساعات العمل"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    color = (if (isOpenNow) palette.emerald else palette.error).copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (isOpenNow) {
                                            if (strings.isEnglish) "Open Now" else "مفتوح الآن"
                                        } else {
                                            if (strings.isEnglish) "Closed Now" else "مغلق الآن"
                                        },
                                        color = if (isOpenNow) palette.emerald else palette.error,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            target.businessHours.days.forEach { d ->
                                val dayName = when (d.dayKey) {
                                    "SAT" -> if (strings.isEnglish) "Sat" else "السبت"
                                    "SUN" -> if (strings.isEnglish) "Sun" else "الأحد"
                                    "MON" -> if (strings.isEnglish) "Mon" else "الاثنين"
                                    "TUE" -> if (strings.isEnglish) "Tue" else "الثلاثاء"
                                    "WED" -> if (strings.isEnglish) "Wed" else "الأربعاء"
                                    "THU" -> if (strings.isEnglish) "Thu" else "الخميس"
                                    else -> if (strings.isEnglish) "Fri" else "الجمعة"
                                }
                                val hoursText = when {
                                    d.isClosed -> if (strings.isEnglish) "Closed" else "مغلق"
                                    d.isOpen24Hours -> if (strings.isEnglish) "24 Hours" else "مفتوح 24 ساعة"
                                    else -> d.slots.joinToString(" , ") { "${it.openTime} - ${it.closeTime}" }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(dayName, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                                    Text(hoursText, style = MaterialTheme.typography.bodySmall, color = palette.textPrimary)
                                }
                            }
                        }
                    }
                }

                // Safety Controls ONLY (Block / Report) — E2EE Fingerprint removed per Sec 34
                if (target.uid != viewModel.myUid) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = if (isBlocked) (if (strings.isEnglish) "Unblock User" else "إلغاء حظر المستخدم") else (if (strings.isEnglish) "Block User" else "حظر المستخدم"),
                                        color = if (isBlocked) palette.emerald else palette.error
                                    )
                                },
                                leadingContent = { Icon(Icons.Default.Block, contentDescription = null, tint = if (isBlocked) palette.emerald else palette.error) },
                                modifier = Modifier.clickable { viewModel.toggleBlock(isBlocked, strings.isEnglish) },
                                colors = ListItemDefaults.colors(containerColor = palette.surface)
                            )
                            HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                            ListItem(
                                headlineContent = { Text(if (strings.isEnglish) "Report User" else "الإبلاغ عن المستخدم", color = palette.warning) },
                                leadingContent = { Icon(Icons.Default.Report, contentDescription = null, tint = palette.warning) },
                                modifier = Modifier.clickable { showReportDialog = true },
                                colors = ListItemDefaults.colors(containerColor = palette.surface)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showReportDialog) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Report Abuse" else "إبلاغ عن إساءة", color = palette.textPrimary) },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(if (strings.isEnglish) "Reason" else "سبب البلاغ") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedLabelColor = palette.turquoise,
                        unfocusedLabelColor = palette.textSecondary,
                        focusedBorderColor = palette.turquoise,
                        unfocusedBorderColor = palette.border,
                        cursorColor = palette.turquoise
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.reportUser(reason.ifBlank { "Abuse" }, strings.isEnglish)
                        showReportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.warning, contentColor = palette.onAccent)
                ) { Text(strings.send) }
            },
            dismissButton = { TextButton(onClick = { showReportDialog = false }) { Text(strings.cancel) } }
        )
    }
}

private fun computeIsOpenNow(schedule: BusinessHoursSchedule): Boolean {
    if (!schedule.enabled) return false
    return runCatching {
        val zone = ZoneId.of(schedule.timeZoneId.ifBlank { ZoneId.systemDefault().id })
        val now = ZonedDateTime.now(zone)
        val key = when (now.dayOfWeek) {
            DayOfWeek.SATURDAY -> "SAT"
            DayOfWeek.SUNDAY -> "SUN"
            DayOfWeek.MONDAY -> "MON"
            DayOfWeek.TUESDAY -> "TUE"
            DayOfWeek.WEDNESDAY -> "WED"
            DayOfWeek.THURSDAY -> "THU"
            DayOfWeek.FRIDAY -> "FRI"
        }
        val day = schedule.days.firstOrNull { it.dayKey == key } ?: return false
        if (day.isClosed) return false
        if (day.isOpen24Hours) return true
        val currentTime = now.toLocalTime()
        day.slots.any { slot ->
            val open = LocalTime.parse(slot.openTime.trim())
            val close = LocalTime.parse(slot.closeTime.trim())
            if (close.isAfter(open)) {
                !currentTime.isBefore(open) && currentTime.isBefore(close)
            } else {
                !currentTime.isBefore(open) || currentTime.isBefore(close)
            }
        }
    }.getOrDefault(false)
}
