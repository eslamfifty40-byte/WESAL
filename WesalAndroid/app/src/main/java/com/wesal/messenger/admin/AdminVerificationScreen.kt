package com.wesal.messenger.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import coil.request.ImageRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.wesal.messenger.BuildConfig
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.theme.WesalNameplateColorPreset
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.FrameAnimationPolicy
import com.wesal.messenger.core.util.FramePickerItem
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.NeonColorPreset
import com.wesal.messenger.core.util.VerificationDurationOption
import com.wesal.messenger.core.util.VerificationSealType
import com.wesal.messenger.core.util.WesalFeatureFlags
import com.wesal.messenger.core.util.WesalFrameCatalog
import com.wesal.messenger.core.util.WesalFrameLibrary
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.domain.model.AdminDashboardStats
import com.wesal.messenger.domain.model.AdminPermissions
import com.wesal.messenger.domain.model.AppUpdateConfig
import com.wesal.messenger.domain.model.AdminReportRecord
import com.wesal.messenger.domain.model.ChannelPostType
import com.wesal.messenger.domain.model.GlobalAppearanceConfig
import com.wesal.messenger.domain.model.OfficialAnnouncement
import com.wesal.messenger.domain.model.SubscriptionRequest
import com.wesal.messenger.domain.model.SupportTicket
import com.wesal.messenger.domain.model.SupportTicketMessage
import com.wesal.messenger.domain.model.SystemWelcomeConfig
import com.wesal.messenger.domain.model.VerificationAuditLog
import com.wesal.messenger.domain.model.WesalChannel
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.ChannelRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.WesalRoyalNameplateBanner
import com.wesal.messenger.presentation.components.WesalVerifiedBadge
import com.wesal.messenger.presentation.components.WesalStillImageLoader
import com.wesal.messenger.presentation.components.wesalImageModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class AdminSection(
    val number: Int,
    val titleAr: String,
    val titleEn: String,
    val icon: ImageVector
) {
    OVERVIEW(1, "الرئيسية", "Overview", Icons.Default.Dashboard),
    STATISTICS(2, "الإحصائيات", "Stats", Icons.Default.BarChart),
    USERS(3, "المستخدمون", "Users", Icons.Default.People),
    SEARCH(4, "البحث", "Search", Icons.Default.Search),
    REPORTS(5, "البلاغات", "Reports", Icons.Default.Report),
    BANS_WARNINGS(6, "الحظر والإنذارات", "Bans & Warn", Icons.Default.Block),
    CHANNELS(7, "إدارة القنوات", "Channels", Icons.Default.Campaign),
    CONTENT_MOD(8, "إدارة المحتوى", "Moderation", Icons.Default.Gavel),
    VERIFICATION_APPEARANCE(9, "التوثيق والإطارات والنيون", "Verify & Frames", Icons.Default.Verified),
    WESAL_TEAM(10, "فريق وصال", "WESAL Team", Icons.Default.VerifiedUser),
    WELCOME_MSG(11, "رسالة الترحيب", "Welcome Msg", Icons.Default.AutoAwesome),
    ANNOUNCEMENTS(12, "الإعلانات الرسمية", "Broadcasts", Icons.Default.NotificationsActive),
    ACTION_LOGS(13, "سجل الإجراءات", "Action Logs", Icons.Default.History),
    ADMIN_SETTINGS(14, "إعدادات الإدارة", "Settings", Icons.Default.AdminPanelSettings),
    HIDDEN_ACCOUNTS(15, "إخفاء الحسابات", "Hidden Users", Icons.Default.VisibilityOff),
    HIDDEN_LOGS(16, "سجل الإخفاء", "Hidden Logs", Icons.Default.ManageHistory),
    ONLINE_NOW(17, "المتصلون الآن", "Online Now", Icons.Default.Wifi),
    CHAT_ACTIVITY(18, "نشاط المحادثات", "Chat Activity", Icons.Default.Timeline),
    CONTENT_REVIEW(19, "مراجعة المحتوى", "Content Review", Icons.Default.RateReview),
    SUBSCRIPTION_REQUESTS(20, "طلبات التوثيق", "Requests", Icons.Default.Badge),
    MODERATORS(21, "المشرفون المساعدون", "Moderators", Icons.Default.SupervisorAccount),
    GLOBAL_APPEARANCE(22, "مظهر وألوان التطبيق", "App Theme", Icons.Default.Palette),
    SUPPORT_TICKETS(23, "تذاكر الدعم", "Support Tickets", Icons.Default.SupportAgent);

    fun isAllowedFor(p: AdminPermissions): Boolean {
        if (!p.hasAnyAccess()) return false
        if (p.isFullSuperAdmin) return true
        return when (this) {
            OVERVIEW, STATISTICS, USERS, SEARCH -> true
            REPORTS, CONTENT_MOD, CONTENT_REVIEW -> p.canManageReports
            BANS_WARNINGS, HIDDEN_ACCOUNTS, HIDDEN_LOGS -> p.canManageUsersModeration
            CHANNELS, WESAL_TEAM -> p.canManageUsersModeration || p.canBroadcastAnnouncements
            VERIFICATION_APPEARANCE, SUBSCRIPTION_REQUESTS -> p.canManageVerification || p.canManageFramesAndNeon || p.canManageLibrary
            WELCOME_MSG, ANNOUNCEMENTS -> p.canBroadcastAnnouncements
            ACTION_LOGS, ONLINE_NOW, CHAT_ACTIVITY -> p.canInspectPrivateData || p.canManageReports || p.canManageUsersModeration
            ADMIN_SETTINGS -> p.canManageAdmins || p.canCustomizeGlobalTheme
            MODERATORS -> p.canManageAdmins
            GLOBAL_APPEARANCE -> p.canCustomizeGlobalTheme
            SUPPORT_TICKETS -> p.canManageReports || p.canManageUsersModeration
        }
    }
}

enum class AdminUserFilter(val labelAr: String, val isPrimaryChip: Boolean = false) {
    ALL("الكل", true),
    ONLINE("متصل الآن", true),
    OFFLINE("غير متصل", true),
    VERIFIED("موثق", true),
    BANNED("محظور", true),
    HIDDEN("مخفي", true),
    DELETED("محذوف", true),
    NEW_TODAY("مسجل اليوم"),
    NEW_2DAYS("مسجل خلال يومين"),
    NEW_WEEK("مسجل خلال أسبوع"),
    NEW_MONTH("مسجل خلال شهر"),
    ACTIVE_TODAY("نشط اليوم"),
    ACTIVE_WEEK("نشط خلال أسبوع"),
    ACTIVE_MONTH("نشط خلال شهر");

    fun matches(u: WesalUser, now: Long): Boolean {
        val dayMs = 24L * 60L * 60L * 1000L
        val online = u.isOnline || (maxOf(u.lastHeartbeatAt, u.lastSeen) > 0L && now - maxOf(u.lastHeartbeatAt, u.lastSeen) <= Constants.PRESENCE_ONLINE_TTL_MS)
        return when (this) {
            ALL -> true
            ONLINE -> online
            OFFLINE -> !online
            VERIFIED -> u.isVerified
            BANNED -> u.isBanned
            HIDDEN -> u.hiddenFromSearch
            DELETED -> u.deletedAt > 0L
            NEW_TODAY -> u.createdAt > 0L && now - u.createdAt <= dayMs
            NEW_2DAYS -> u.createdAt > 0L && now - u.createdAt <= 2 * dayMs
            NEW_WEEK -> u.createdAt > 0L && now - u.createdAt <= 7 * dayMs
            NEW_MONTH -> u.createdAt > 0L && now - u.createdAt <= 30 * dayMs
            ACTIVE_TODAY -> u.lastSeen > 0L && now - u.lastSeen <= dayMs
            ACTIVE_WEEK -> u.lastSeen > 0L && now - u.lastSeen <= 7 * dayMs
            ACTIVE_MONTH -> u.lastSeen > 0L && now - u.lastSeen <= 30 * dayMs
        }
    }
}

@HiltViewModel
class AdminVerificationViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val channelRepository: ChannelRepository,
    private val chatRepository: ChatRepository,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    val isSuperAdmin: StateFlow<Boolean> = userRepository.observeIsSuperAdmin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val adminPermissions: StateFlow<AdminPermissions> = userRepository.observeMyAdminPermissions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AdminPermissions(active = false))

    val moderators: StateFlow<List<Pair<WesalUser, AdminPermissions>>> = userRepository.observeModerators()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val globalAppearance: StateFlow<GlobalAppearanceConfig> = userRepository.observeGlobalAppearance()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlobalAppearanceConfig())

    val appUpdateConfig: StateFlow<AppUpdateConfig> = userRepository.observeAppUpdateConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppUpdateConfig())

    val subscriptionRequests: StateFlow<List<SubscriptionRequest>> = userRepository.observeSubscriptionRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val verificationLogs: StateFlow<List<VerificationAuditLog>> = userRepository.observeVerificationAuditLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hiddenLogs: StateFlow<List<VerificationAuditLog>> = userRepository.observeHiddenAuditLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val adminActionLogs: StateFlow<List<VerificationAuditLog>> = userRepository.observeAdminActionLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reports: StateFlow<List<AdminReportRecord>> = userRepository.observeAdminReports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Support tickets (spec §9.8): until now observeAllSupportTickets() had NO admin UI
    // caller, so user support requests never reached the panel. Wired end-to-end here:
    // list -> thread -> reply / status change (repository already implemented).
    val supportTickets: StateFlow<List<SupportTicket>> = userRepository.observeAllSupportTickets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedSupportTicketId = MutableStateFlow<String?>(null)
    val selectedSupportTicketId: StateFlow<String?> = _selectedSupportTicketId

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val supportTicketMessages: StateFlow<List<SupportTicketMessage>> = _selectedSupportTicketId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) kotlinx.coroutines.flow.flowOf(emptyList())
            else userRepository.observeSupportTicketMessages(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectSupportTicket(ticketId: String?) {
        _selectedSupportTicketId.value = ticketId
    }

    fun replySupportTicket(ticketId: String, text: String, newStatus: String? = null) {
        val clean = text.trim()
        if (clean.isBlank()) return
        runOp("ticket_reply_$ticketId") {
            _busy.value = true
            userRepository.replySupportTicket(ticketId, clean, newStatus)
                .onSuccess { _statusMessage.value = "تم إرسال الرد على التذكرة ✓" }
                .onFailure { _statusMessage.value = it.message ?: "فشل إرسال الرد" }
            _busy.value = false
        }
    }

    fun setSupportTicketStatus(ticketId: String, status: String) {
        runOp("ticket_status_$ticketId") {
            _busy.value = true
            userRepository.updateSupportTicketStatus(ticketId, status)
                .onSuccess { _statusMessage.value = "تم تحديث حالة التذكرة: $status" }
                .onFailure { _statusMessage.value = it.message ?: "فشل تحديث حالة التذكرة" }
            _busy.value = false
        }
    }

    val announcements: StateFlow<List<OfficialAnnouncement>> = userRepository.observeOfficialAnnouncements()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _stats = MutableStateFlow(AdminDashboardStats())
    val stats: StateFlow<AdminDashboardStats> = _stats.asStateFlow()

    private val _users = MutableStateFlow<List<WesalUser>>(emptyList())
    val users: StateFlow<List<WesalUser>> = _users.asStateFlow()

    private val _searchResults = MutableStateFlow<List<WesalUser>>(emptyList())
    val searchResults: StateFlow<List<WesalUser>> = _searchResults.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _channels = MutableStateFlow<List<WesalChannel>>(emptyList())
    val channels: StateFlow<List<WesalChannel>> = _channels.asStateFlow()

    private val _officialTeamChannel = MutableStateFlow<WesalChannel?>(null)
    val officialTeamChannel: StateFlow<WesalChannel?> = _officialTeamChannel.asStateFlow()

    private val _welcomeConfig = MutableStateFlow(SystemWelcomeConfig())
    val welcomeConfig: StateFlow<SystemWelcomeConfig> = _welcomeConfig.asStateFlow()

    private val _globalCustomerFrameEnabled = MutableStateFlow(true)
    val globalCustomerFrameEnabled: StateFlow<Boolean> = _globalCustomerFrameEnabled.asStateFlow()

    private val _inspectedUser = MutableStateFlow<WesalUser?>(null)
    val inspectedUser: StateFlow<WesalUser?> = _inspectedUser.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // §9.11 per-operation busy keys: every admin action runs under a stable op
    // key via [runOp]; a second call with the same key while the first is in
    // flight is dropped, and the UI binds each action button's `enabled` to its
    // key not being present in [busyOps]. The pre-existing global [busy] flag is
    // kept unchanged alongside this.
    private val _busyOps = MutableStateFlow<Set<String>>(emptySet())
    val busyOps: StateFlow<Set<String>> = _busyOps.asStateFlow()

    fun runOp(key: String, block: suspend () -> Unit) {
        if (key in _busyOps.value) return
        _busyOps.value = _busyOps.value + key
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busyOps.value = _busyOps.value - key
            }
        }
    }

    /**
     * Round 6 (item 8, SPEED — owner's top priority): grant operations used to
     * call refreshAll() on success, which re-walked the ENTIRE users collection
     * for stats (500-doc pages), re-fetched the 250-user list, the welcome
     * config, the global-frame flag, the official team channel and 150 channels
     * — hundreds of document reads after a grant that itself is ONE merge write.
     * That reload was the "saving a frame spins forever" the owner reported.
     * A grant now patches the already-loaded lists in memory instead: zero
     * extra Firestore reads, and the busy state clears as soon as the grant
     * write (+ its audit log) lands. refreshAll() remains for structural ops
     * (delete / warn / subscription review) where counts genuinely change.
     */
    private fun patchUserLocally(uid: String, transform: (WesalUser) -> WesalUser) {
        _users.value = _users.value.map { if (it.uid == uid) transform(it) else it }
        _searchResults.value = _searchResults.value.map { if (it.uid == uid) transform(it) else it }
        _inspectedUser.value?.takeIf { it.uid == uid }?.let { _inspectedUser.value = transform(it) }
    }

    // §9.6: last official-chat/channel photo set from the identity editor card.
    private val _officialChatPhotoUrl = MutableStateFlow("")
    val officialChatPhotoUrl: StateFlow<String> = _officialChatPhotoUrl.asStateFlow()

    private var searchJob: Job? = null

    private fun hasPanelAccess(): Boolean = isSuperAdmin.value || adminPermissions.value.hasAnyAccess()

    init {
        viewModelScope.launch {
            combine(isSuperAdmin, adminPermissions) { sa, perms -> sa || perms.hasAnyAccess() }
                .distinctUntilChanged()
                .collect { allowed ->
                    if (!allowed) {
                        _users.value = emptyList()
                        _searchResults.value = emptyList()
                        _channels.value = emptyList()
                    } else {
                        refreshAll()
                    }
                }
        }
    }

    fun refreshAll() {
        runOp("refresh") {
            if (!hasPanelAccess() && !userRepository.isSuperAdmin()) return@runOp
            _busy.value = true
            runCatching { _stats.value = userRepository.computeAdminStats() }
            runCatching {
                val allUsers = userRepository.getAdminUsersList(limit = 250)
                _users.value = allUsers
                if (_searchQuery.value.isBlank()) _searchResults.value = allUsers
            }
            runCatching { _welcomeConfig.value = userRepository.getWelcomeConfig() }
            runCatching { _globalCustomerFrameEnabled.value = userRepository.getGlobalCustomerFrameEnabled() }
            if (isSuperAdmin.value || adminPermissions.value.canBroadcastAnnouncements) {
                runCatching { _officialTeamChannel.value = channelRepository.ensureOfficialTeamChannel() }
            }
            loadAllChannels()
            _busy.value = false
        }
    }

    fun loadAllChannels() {
        runOp("channels_load") {
            if (!hasPanelAccess() && !userRepository.isSuperAdmin()) return@runOp
            val snap = runCatching {
                firestore.collection(Constants.COLLECTION_CHANNELS).limit(150).get().await()
            }.getOrNull() ?: return@runOp
            val mapped = snap.documents.mapNotNull { d ->
                if (!d.exists()) return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val subs = (d.get("subscriberIds") as? List<String>).orEmpty()
                @Suppress("UNCHECKED_CAST")
                val mods = (d.get("moderatorIds") as? List<String>).orEmpty()
                WesalChannel(
                    channelId = d.getString("channelId") ?: d.id,
                    title = d.getString("title").orEmpty(),
                    description = d.getString("description").orEmpty(),
                    avatarUrl = d.getString("avatarUrl").orEmpty(),
                    officialWallpaperUrl = d.getString("officialWallpaperUrl").orEmpty(),
                    isPublic = d.getBoolean("isPublic") ?: true,
                    isOfficialTeamChannel = d.getBoolean("isOfficialTeamChannel")
                        ?: (d.id == Constants.OFFICIAL_WESAL_TEAM_CHANNEL_ID),
                    isSuspended = d.getBoolean("isSuspended") ?: false,
                    inviteCode = d.getString("inviteCode").orEmpty(),
                    ownerUid = d.getString("ownerUid").orEmpty(),
                    moderatorIds = mods,
                    subscriberIds = subs,
                    subscribersCount = (d.getLong("subscribersCount") ?: subs.size.toLong()).toInt(),
                    isVerified = d.getBoolean("isVerified") ?: false,
                    verificationType = d.getString("verificationType") ?: "BLUE",
                    createdAt = d.getLong("createdAt") ?: 0L
                )
            }.sortedWith(
                compareByDescending<WesalChannel> { it.isOfficialTeamChannel }
                    .thenByDescending { it.subscribersCount }
            )
            _channels.value = mapped
            _officialTeamChannel.value = mapped.firstOrNull { it.isOfficialTeamChannel }
                ?: _officialTeamChannel.value
        }
    }

    fun updateSearch(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250L)
            if (!hasPanelAccess() && !userRepository.isSuperAdmin()) return@launch
            val trimmed = q.trim()
            _searchResults.value = if (trimmed.isBlank()) {
                _users.value
            } else {
                runCatching { userRepository.adminSearchUsers(trimmed) }.getOrDefault(emptyList())
            }
        }
    }

    fun inspectUser(uid: String) {
        runOp("inspect_$uid") {
            _busy.value = true
            userRepository.adminGetUserFullDetails(uid, reason = "معاينة الحساب من لوحة المشرف الأعلى")
                .onSuccess { _inspectedUser.value = it }
                .onFailure { _statusMessage.value = it.message ?: "تعذر جلب تفاصيل المستخدم" }
            _busy.value = false
        }
    }

    fun dismissInspectedUser() {
        _inspectedUser.value = null
    }

    fun applyVerification(
        target: WesalUser,
        verified: Boolean,
        sealType: VerificationSealType,
        verificationGlowColor: NeonColorPreset = NeonColorPreset.NONE,
        expiresAt: Long,
        durationLabel: String,
        note: String
    ) {
        runOp("verify_${target.uid}") {
            _busy.value = true
            userRepository.setAccountVerification(
                targetUid = target.uid,
                verified = verified,
                note = note,
                verificationType = sealType.key,
                verificationGlowColor = if (verified) verificationGlowColor.key else "",
                expiresAt = if (verified) expiresAt else 0L,
                durationLabel = if (verified) durationLabel else "سحب"
            ).onSuccess {
                _statusMessage.value = if (verified) {
                    "تم منح ${sealType.labelAr} (${durationLabel}) لـ ${target.displayName.ifBlank { target.numericId }}"
                } else {
                    "تم إلغاء توثيق ${target.displayName.ifBlank { target.numericId }}"
                }
                // Item 8: local patch instead of refreshAll() (see patchUserLocally).
                patchUserLocally(target.uid) {
                    it.copy(
                        isVerified = verified,
                        verificationType = sealType.key,
                        verificationGlowColor = if (verified) verificationGlowColor.key else "",
                        verificationExpiresAt = if (verified) expiresAt else 0L
                    )
                }
            }.onFailure { _statusMessage.value = it.message ?: "فشل تحديث التوثيق" }
            _busy.value = false
        }
    }

    fun applyFrame(
        target: WesalUser,
        frameId: String,
        expiresAt: Long,
        durationLabel: String
    ) {
        runOp("frame_${target.uid}") {
            _busy.value = true
            userRepository.setAccountFrame(target.uid, frameId, expiresAt, durationLabel)
                .onSuccess {
                    _statusMessage.value = if (frameId.isBlank()) {
                        "تمت إزالة الإطار الشخصي"
                    } else {
                        "تم تعيين الإطار ($durationLabel) بنجاح"
                    }
                    // Item 8: local patch instead of refreshAll() (see patchUserLocally).
                    patchUserLocally(target.uid) {
                        it.copy(
                            frameId = frameId.trim(),
                            frameExpiresAt = if (frameId.isBlank()) 0L else expiresAt,
                            grantedFrameIds = if (frameId.isBlank()) {
                                it.grantedFrameIds
                            } else {
                                (it.grantedFrameIds + frameId.trim()).distinct()
                            }
                        )
                    }
                }
                .onFailure { _statusMessage.value = it.message ?: "فشل تحديث الإطار" }
            _busy.value = false
        }
    }

    fun saveAppUpdateConfig(config: AppUpdateConfig) {
        runOp("app_update_save") {
            _busy.value = true
            userRepository.updateAppUpdateConfig(config)
                .onSuccess {
                    _statusMessage.value = if (config.enabled) {
                        "تم حفظ إعدادات تحديث التطبيق (أحدث إصدار: ${config.latestVersionCode}) ✓"
                    } else {
                        "تم إيقاف مطالبة تحديث التطبيق ✓"
                    }
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر حفظ إعدادات التحديث" }
            _busy.value = false
        }
    }

    fun toggleGlobalCustomerFrame(enabled: Boolean) {
        runOp("global_frame_toggle") {
            _busy.value = true
            userRepository.setGlobalCustomerFrameEnabled(enabled)
                .onSuccess { updatedCount ->
                    _globalCustomerFrameEnabled.value = enabled
                    _statusMessage.value = if (enabled) {
                        "تم تفعيل إطار عملاء WESAL لـ $updatedCount حساب"
                    } else {
                        "تم إيقاف الإطار الافتراضي لـ $updatedCount حساب"
                    }
                    refreshAll()
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر تطبيق الإطار الجماعي" }
            _busy.value = false
        }
    }

    fun saveGlobalCustomerDefaults(
        enabled: Boolean,
        frameId: String,
        iconId: String,
        iconUrl: String
    ) {
        runOp("global_defaults_save") {
            _busy.value = true
            userRepository.setGlobalCustomerDefaults(enabled, frameId, iconId, iconUrl)
                .onSuccess { updatedCount ->
                    _globalCustomerFrameEnabled.value = enabled
                    _statusMessage.value = if (enabled) {
                        "تم تطبيق الإطار والأيقونة الافتراضية على $updatedCount حساب ✓"
                    } else {
                        "تم إيقاف التعيين الجماعي لـ $updatedCount حساب ✓"
                    }
                    refreshAll()
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر حفظ الإعدادات الجماعية" }
            _busy.value = false
        }
    }

    fun applyNeon(target: WesalUser, preset: NeonColorPreset, glowIntensity: Float, enabled: Boolean) {
        runOp("neon_${target.uid}") {
            _busy.value = true
            userRepository.setAccountNeonStyle(target.uid, preset.key, glowIntensity, enabled)
                .onSuccess {
                    _statusMessage.value = if (!enabled || preset == NeonColorPreset.NONE) {
                        "تم إيقاف النيون"
                    } else {
                        "تم تحديث لون النيون (${preset.labelAr}) بنسبة ${(glowIntensity * 100).toInt()}%"
                    }
                    // Item 8: local patch instead of refreshAll() (see patchUserLocally).
                    // Mirrors setAccountNeonStyle's stored values exactly.
                    patchUserLocally(target.uid) {
                        it.copy(
                            neonEnabled = enabled && preset.key.isNotBlank(),
                            neonColor = if (enabled) preset.key else "",
                            neonGlowIntensity = glowIntensity.coerceIn(0.15f, 1.0f)
                        )
                    }
                }
                .onFailure { _statusMessage.value = it.message ?: "فشل تحديث النيون" }
            _busy.value = false
        }
    }

    fun applyIcon(target: WesalUser, iconId: String, iconUrl: String) {
        runOp("icon_${target.uid}") {
            _busy.value = true
            userRepository.setAccountIcon(target.uid, iconId, iconUrl)
                .onSuccess {
                    _statusMessage.value = if (iconId.isBlank()) "تمت إزالة الأيقونة" else "تم تعيين الأيقونة بنجاح"
                    // Item 8: local patch instead of refreshAll() (see patchUserLocally).
                    // Mirrors setAccountIcon's stored values (blank id clears both;
                    // a blank URL is resolved from the library by the repository).
                    patchUserLocally(target.uid) {
                        it.copy(
                            iconId = iconId.trim(),
                            iconUrl = if (iconId.isBlank()) {
                                ""
                            } else {
                                iconUrl.trim().ifBlank { WesalFrameLibrary.resolveIconUrl(iconId.trim()) }
                            }
                        )
                    }
                }
                .onFailure { _statusMessage.value = it.message ?: "فشل تحديث الأيقونة" }
            _busy.value = false
        }
    }

    fun applyNameplateColor(target: WesalUser, colorKey: String) {
        runOp("nameplate_${target.uid}") {
            _busy.value = true
            userRepository.setAccountNameplateColor(target.uid, colorKey)
                .onSuccess {
                    _statusMessage.value = if (colorKey.isBlank()) {
                        "تمت إعادة تعيين لون لوحة الاسم"
                    } else {
                        "تم تعيين لون لوحة الاسم بنجاح"
                    }
                    // Item 8: local patch instead of refreshAll() (see patchUserLocally).
                    // Mirrors setAccountNameplateColor's stored value (trimmed, upper-cased).
                    patchUserLocally(target.uid) {
                        it.copy(nameplateColor = colorKey.trim().uppercase())
                    }
                }
                .onFailure { _statusMessage.value = it.message ?: "فشل تحديث لون لوحة الاسم" }
            _busy.value = false
        }
    }

    fun deleteUser(target: WesalUser, permanent: Boolean) {
        if (Constants.isProtectedAdminNumericId(target.numericId) || Constants.isSuperAdminEmail(target.email)) {
            _statusMessage.value = "لا يمكن حذف حساب الإدارة المحمي ${target.numericId}"
            return
        }
        runOp("delete_${target.uid}") {
            _busy.value = true
            userRepository.adminDeleteUser(target.uid, permanent)
                .onSuccess {
                    if (_inspectedUser.value?.uid == target.uid) _inspectedUser.value = null
                    _statusMessage.value = if (permanent) "تم حذف الحساب نهائيًا" else "تم تعطيل الحساب"
                    refreshAll()
                }
                .onFailure { _statusMessage.value = it.message ?: "فشل حذف الحساب" }
            _busy.value = false
        }
    }

    fun saveGlobalAppearance(config: GlobalAppearanceConfig) {
        runOp("appearance_save") {
            _busy.value = true
            userRepository.updateGlobalAppearance(config)
                .onSuccess {
                    _statusMessage.value = if (config.enabled) "تم حفظ وتفعيل المظهر العام للتطبيق ✓" else "تمت استعادة ألوان WESAL الافتراضية ✓"
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر حفظ المظهر العام" }
            _busy.value = false
        }
    }

    fun assignModerator(targetUid: String, permissions: AdminPermissions?) {
        runOp("moderator_$targetUid") {
            _busy.value = true
            userRepository.setModeratorPermissions(targetUid, permissions)
                .onSuccess {
                    _statusMessage.value = if (permissions == null) "تم سحب صلاحيات المشرف" else "تم حفظ صلاحيات المشرف المساعد ✓"
                }
                .onFailure { _statusMessage.value = it.message ?: "فشل تعديل صلاحيات المشرف" }
            _busy.value = false
        }
    }

    fun reviewSubscription(reqId: String, approved: Boolean, note: String) {
        runOp("sub_review_$reqId") {
            _busy.value = true
            userRepository.reviewSubscriptionRequest(reqId, approved, note)
                .onSuccess {
                    _statusMessage.value = if (approved) "تمت الموافقة ومنح التوثيق للمستخدم ✓" else "تم رفض الطلب"
                    refreshAll()
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر معالجة الطلب" }
            _busy.value = false
        }
    }

    fun warnUser(target: WesalUser, reason: String) {
        if (reason.isBlank()) {
            _statusMessage.value = "اكتب سبب الإنذار أولاً"
            return
        }
        runOp("warn_${target.uid}") {
            _busy.value = true
            userRepository.warnUser(target.uid, reason)
                .onSuccess {
                    _statusMessage.value = "تم إرسال الإنذار الرسمي إلى ${target.displayName.ifBlank { target.numericId }}"
                    refreshAll()
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر إرسال الإنذار" }
            _busy.value = false
        }
    }

    fun deleteAccountPermanently(target: WesalUser, reason: String) {
        if (Constants.isProtectedAdminNumericId(target.numericId) || Constants.isSuperAdminEmail(target.email)) {
            _statusMessage.value = "لا يمكن حذف حساب الإدارة المحمي ${target.numericId}"
            return
        }
        runOp("delete_permanent_${target.uid}") {
            _busy.value = true
            userRepository.deleteAccountPermanently(target.uid, reason)
                .onSuccess {
                    _statusMessage.value = "تم حذف الحساب نهائيًا"
                    refreshAll()
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر حذف الحساب" }
            _busy.value = false
        }
    }

    fun banUser(
        target: WesalUser,
        permanent: Boolean,
        expiresAt: Long,
        reason: String,
        blockIdentifiers: Boolean
    ) {
        if (Constants.isProtectedAdminNumericId(target.numericId) || Constants.isSuperAdminEmail(target.email)) {
            _statusMessage.value = "لا يمكن حظر حساب الإدارة المحمي ${target.numericId}"
            return
        }
        if (reason.isBlank()) {
            _statusMessage.value = "يرجى كتابة سبب الحظر"
            return
        }
        runOp("ban_${target.uid}") {
            _busy.value = true
            userRepository.banUser(target.uid, permanent, expiresAt, reason, blockIdentifiers)
                .onSuccess {
                    _statusMessage.value = if (permanent) "تم حظر الحساب نهائياً" else "تم تطبيق الحظر المؤقت بنجاح"
                    refreshAll()
                }
                .onFailure { _statusMessage.value = it.message ?: "فشل تطبيق الحظر" }
            _busy.value = false
        }
    }

    fun unbanUser(target: WesalUser) {
        runOp("ban_${target.uid}") {
            _busy.value = true
            userRepository.unbanUser(target.uid, note = "رفع الحظر من لوحة الإدارة")
                .onSuccess {
                    _statusMessage.value = "تم رفع الحظر عن الحساب"
                    refreshAll()
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر رفع الحظر" }
            _busy.value = false
        }
    }

    fun setHiddenFromSearch(target: WesalUser, hidden: Boolean, reason: String) {
        runOp("hide_${target.uid}") {
            _busy.value = true
            userRepository.setAccountHiddenFromSearch(target.uid, hidden, reason)
                .onSuccess {
                    _statusMessage.value = if (hidden) "تم إخفاء الحساب من البحث" else "تم إعادة إظهار الحساب"
                    refreshAll()
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر تحديث حالة الإخفاء" }
            _busy.value = false
        }
    }

    fun updateReport(reportId: String, status: String, note: String, resolution: String) {
        runOp("report_$reportId") {
            _busy.value = true
            userRepository.updateReportStatus(reportId, status, note, resolution)
                .onSuccess { _statusMessage.value = "تم تحديث البلاغ إلى: $status" }
                .onFailure { _statusMessage.value = it.message ?: "فشل تحديث البلاغ" }
            _busy.value = false
        }
    }

    fun inspectReportedChat(report: AdminReportRecord, onOpenChat: (String) -> Unit) {
        if (report.targetChatId.isBlank()) {
            _statusMessage.value = "لا يوجد معرف محادثة مرتبط بهذا البلاغ"
            return
        }
        runOp("report_chat_${report.reportId}") {
            userRepository.adminLogReportChatInspection(
                reportId = report.reportId,
                chatId = report.targetChatId,
                targetUid = report.reportedUid,
                reason = report.reason
            )
            onOpenChat(report.targetChatId)
        }
    }

    fun setChannelSuspended(channel: WesalChannel, suspended: Boolean) {
        if (channel.isOfficialTeamChannel) {
            _statusMessage.value = "لا يمكن تعليق قناة فريق وصال الرسمية"
            return
        }
        runOp("channel_suspend_${channel.channelId}") {
            _busy.value = true
            channelRepository.adminSetChannelSuspended(channel.channelId, suspended)
                .onSuccess {
                    _statusMessage.value = if (suspended) "تم تعليق القناة" else "تم تفعيل القناة"
                    loadAllChannels()
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر تعديل حالة القناة" }
            _busy.value = false
        }
    }

    fun deleteChannel(channel: WesalChannel) {
        if (channel.isOfficialTeamChannel) {
            _statusMessage.value = "لا يمكن حذف قناة فريق وصال الرسمية"
            return
        }
        runOp("channel_delete_${channel.channelId}") {
            _busy.value = true
            channelRepository.deleteChannel(channel.channelId)
                .onSuccess {
                    _statusMessage.value = "تم حذف القناة"
                    loadAllChannels()
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر حذف القناة" }
            _busy.value = false
        }
    }

    fun publishOfficialTeamPost(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            _statusMessage.value = "اكتب نص المنشور أولاً"
            return
        }
        runOp("team_post") {
            _busy.value = true
            val ch = channelRepository.ensureOfficialTeamChannel()
            if (ch == null) {
                _statusMessage.value = "تعذر العثور على قناة فريق وصال"
                _busy.value = false
                return@runOp
            }
            channelRepository.publishPost(ch.channelId, ChannelPostType.TEXT, clean)
                .collect { res ->
                    res.onSuccess { frac ->
                        if (frac >= 1f) {
                            _statusMessage.value = "تم النشر في قناة فريق وصال بنجاح"
                            _busy.value = false
                        }
                    }.onFailure { err ->
                        _statusMessage.value = err.message ?: "فشل النشر في القناة"
                        _busy.value = false
                    }
                }
        }
    }

    fun saveWelcomeConfig(enabled: Boolean, template: String) {
        runOp("welcome_save") {
            _busy.value = true
            userRepository.updateWelcomeConfig(enabled, template)
                .onSuccess {
                    _welcomeConfig.value = userRepository.getWelcomeConfig()
                    _statusMessage.value = "تم حفظ إعدادات رسالة الترحيب"
                }
                .onFailure { _statusMessage.value = it.message ?: "تعذر حفظ رسالة الترحيب" }
            _busy.value = false
        }
    }

    fun sendWelcomeTestToSelf() {
        runOp("welcome_test") {
            _busy.value = true
            userRepository.sendWelcomeTestToSelf()
                .onSuccess { _statusMessage.value = "تم إرسال رسالة ترحيب تجريبية إلى محادثة WESAL Messenger لديك" }
                .onFailure { _statusMessage.value = it.message ?: "فشل إرسال الرسالة التجريبية" }
            _busy.value = false
        }
    }

    fun publishOfficialAnnouncement(title: String, body: String) {
        runOp("announcement_publish") {
            _busy.value = true
            userRepository.publishOfficialAnnouncement(title, body)
                .onSuccess { _statusMessage.value = "تم إرسال الإعلان الرسمي إلى جميع المستخدمين بنجاح" }
                .onFailure { _statusMessage.value = it.message ?: "تعذر إرسال الإعلان" }
            _busy.value = false
        }
    }

    fun deleteAuditLog(collectionName: String, logId: String) {
        runOp("log_delete_$logId") {
            _busy.value = true
            userRepository.deleteAuditLog(collectionName, logId)
                .onSuccess { _statusMessage.value = "تم حذف السجل بنجاح ✓" }
                .onFailure { _statusMessage.value = it.message ?: "تعذر حذف السجل" }
            _busy.value = false
        }
    }

    fun clearAuditLogs(collectionName: String) {
        runOp("logs_clear_$collectionName") {
            _busy.value = true
            userRepository.clearAuditLogs(collectionName)
                .onSuccess { _statusMessage.value = "تم تفريغ السجل بنجاح ✓" }
                .onFailure { _statusMessage.value = it.message ?: "تعذر تفريغ السجل" }
            _busy.value = false
        }
    }

    fun openDirectChatWithUser(uid: String, onOpenChat: (String) -> Unit) {
        runOp("open_chat_$uid") {
            chatRepository.getOrCreateDirectChat(uid)
                .onSuccess { chatId -> onOpenChat(chatId) }
                .onFailure { _statusMessage.value = it.message ?: "تعذر فتح المحادثة" }
        }
    }

    /**
     * §9.6 official identity editor: pick → upload via the existing profile-media
     * helper → write through the user repository. Cross-agent dependency:
     * `userRepository.updateOfficialChatIdentity(url)` (suspend, Result<Unit>)
     * is added to the user repository by another round-4 agent with exactly that
     * name/signature; it writes the official chat doc's photo + the team
     * channel's avatarUrl. The upload helper fails loudly on Storage errors; a
     * blank result or a leftover inline `data:` payload is treated as failure
     * and never written as the official photo.
     */
    fun updateOfficialIdentityPhoto(context: android.content.Context, uri: android.net.Uri) {
        runOp("official_identity") {
            _busy.value = true
            val uploaded = runCatching {
                com.wesal.messenger.data.repository.uploadProfileMediaToStorageOrSmallFallback(
                    ctx = context,
                    storagePath = "official_identity/official_photo_${System.currentTimeMillis()}.jpg",
                    uri = uri,
                    maxSide = 720,
                    quality = 88
                )
            }.getOrDefault("")
            if (uploaded.isBlank() || uploaded.startsWith("data:", ignoreCase = true)) {
                _statusMessage.value = "تعذر رفع صورة الهوية الرسمية — حاول مرة أخرى"
            } else {
                userRepository.updateOfficialChatIdentity(uploaded)
                    .onSuccess {
                        _officialChatPhotoUrl.value = uploaded
                        _statusMessage.value = "تم تحديث صورة المحادثة والقناة الرسمية ✓"
                        loadAllChannels()
                    }
                    .onFailure { _statusMessage.value = it.message ?: "تعذر حفظ صورة الهوية الرسمية" }
            }
            _busy.value = false
        }
    }

    fun clearMessage() {
        _statusMessage.value = null
    }
}

private fun calculateDurationExpiresAt(option: VerificationDurationOption, customCount: Int): Pair<Long, String> {
    val now = System.currentTimeMillis()
    val safeCount = customCount.coerceAtLeast(1)
    return when (option) {
        VerificationDurationOption.HOUR_1 -> (now + 3600_000L) to "ساعة واحدة"
        VerificationDurationOption.CUSTOM_HOURS -> (now + safeCount * 3600_000L) to "$safeCount ساعات"
        VerificationDurationOption.DAY_1 -> (now + 24L * 3600_000L) to "يوم واحد"
        VerificationDurationOption.CUSTOM_DAYS -> (now + safeCount * 24L * 3600_000L) to "$safeCount أيام"
        VerificationDurationOption.MONTH_1 -> {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.MONTH, 1)
            }
            cal.timeInMillis to "شهر تقويمي"
        }
        VerificationDurationOption.CUSTOM_MONTHS -> {
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.MONTH, safeCount)
            }
            cal.timeInMillis to "$safeCount شهور تقويمية"
        }
        VerificationDurationOption.PERMANENT -> 0L to "دائم"
    }
}

private fun formatAdminTime(ts: Long): String {
    if (ts <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}

private fun matchesAdminQuery(user: WesalUser, rawQuery: String): Boolean {
    val q = rawQuery.trim()
    if (q.isEmpty()) return true
    val lower = q.lowercase()
    val normQ = WesalIdUtils.normalizeDigits(q)
    val cleanHandle = normQ.removePrefix("@").lowercase()
    return user.displayName.lowercase().contains(lower) ||
        user.numericId.contains(normQ) ||
        user.publicId.lowercase().contains(cleanHandle) ||
        user.email.lowercase().contains(lower) ||
        user.uid.lowercase().contains(lower)
}

@Composable
private fun AdminInlineSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "ابحث بالاسم أو WESAL ID أو @username أو UID"
) {
    val palette = WesalColors.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.turquoise) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = palette.textSecondary)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminVerificationScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    onOpenUserProfile: (String) -> Unit,
    onOpenChannel: (String) -> Unit = {},
    viewModel: AdminVerificationViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current

    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val adminPermissions by viewModel.adminPermissions.collectAsStateWithLifecycle()
    val moderators by viewModel.moderators.collectAsStateWithLifecycle()
    val globalAppearance by viewModel.globalAppearance.collectAsStateWithLifecycle()
    val appUpdateConfig by viewModel.appUpdateConfig.collectAsStateWithLifecycle()
    val subscriptionRequests by viewModel.subscriptionRequests.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val users by viewModel.users.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val officialTeamChannel by viewModel.officialTeamChannel.collectAsStateWithLifecycle()
    val welcomeConfig by viewModel.welcomeConfig.collectAsStateWithLifecycle()
    val globalFrameEnabled by viewModel.globalCustomerFrameEnabled.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val supportTickets by viewModel.supportTickets.collectAsStateWithLifecycle()
    val selectedSupportTicketId by viewModel.selectedSupportTicketId.collectAsStateWithLifecycle()
    val supportTicketMessages by viewModel.supportTicketMessages.collectAsStateWithLifecycle()
    val verificationLogs by viewModel.verificationLogs.collectAsStateWithLifecycle()
    val hiddenLogs by viewModel.hiddenLogs.collectAsStateWithLifecycle()
    val adminActionLogs by viewModel.adminActionLogs.collectAsStateWithLifecycle()
    val announcements by viewModel.announcements.collectAsStateWithLifecycle()
    val inspectedUser by viewModel.inspectedUser.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val busyOps by viewModel.busyOps.collectAsStateWithLifecycle()
    val officialChatPhotoUrl by viewModel.officialChatPhotoUrl.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val allowedSections = remember(adminPermissions) {
        AdminSection.entries.filter { it.isAllowedFor(adminPermissions) }
    }
    var currentSection by remember { mutableStateOf(AdminSection.OVERVIEW) }
    var activeUserFilter by remember { mutableStateOf(AdminUserFilter.ALL) }

    // Modal action targets
    var verifyTarget by remember { mutableStateOf<WesalUser?>(null) }
    var frameNeonTarget by remember { mutableStateOf<WesalUser?>(null) }
    // Round 6 (item 7a): the tab AdminFrameAndNeonDialog must OPEN on, set by
    // whichever action opened it (frames actions -> 0 Customer frames; the
    // dialog's own modeTab used to be an unkeyed remember, so a previous
    // session's tab — e.g. Icons — could greet the next «إطار» tap).
    var frameNeonInitialTab by remember { mutableStateOf(0) }
    var warnTarget by remember { mutableStateOf<WesalUser?>(null) }
    var banTarget by remember { mutableStateOf<WesalUser?>(null) }
    var hideTarget by remember { mutableStateOf<WesalUser?>(null) }
    var deleteTarget by remember { mutableStateOf<WesalUser?>(null) }
    var reviewReportTarget by remember { mutableStateOf<AdminReportRecord?>(null) }

    val hasAccess = isSuperAdmin || adminPermissions.hasAnyAccess()
    val myNumericLabel = currentUser?.numericId?.takeIf { it.isNotBlank() } ?: if (adminPermissions.isFullSuperAdmin) Constants.OWNER_ADMIN_NUMERIC_ID else "مشرف"

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (strings.isEnglish) "WESAL Admin Console" else "لوحة إدارة WESAL",
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${currentSection.number}. ${if (strings.isEnglish) currentSection.titleEn else currentSection.titleAr} • ID: $myNumericLabel",
                            color = palette.emerald,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                actions = {
                    if (hasAccess) {
                        IconButton(onClick = { currentSection = AdminSection.SEARCH }) {
                            Icon(Icons.Default.Search, contentDescription = "بحث عن مستخدم", tint = palette.turquoise)
                        }
                        IconButton(onClick = { viewModel.refreshAll() }, enabled = !busy && "refresh" !in busyOps) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = palette.turquoise)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        if (!hasAccess) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = palette.error, modifier = Modifier.size(56.dp))
                    Text(
                        text = if (strings.isEnglish) {
                            "Restricted strictly to authorized WESAL Administrators."
                        } else {
                            "هذه اللوحة مقفولة لحسابات الإدارة المعتمدة (${Constants.OWNER_ADMIN_NUMERIC_ID} / ${Constants.SECONDARY_ADMIN_NUMERIC_ID}) والمشرفين المصرّح لهم فقط."
                        },
                        color = palette.textPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = Constants.SUPER_ADMIN_EMAIL,
                        color = palette.textMuted,
                        style = MaterialTheme.typography.bodySmall
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
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.emerald,
                    trackColor = palette.surfaceElevated
                )
            }

            statusMessage?.let { msg ->
                Surface(
                    color = palette.emerald.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            color = palette.emerald,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::clearMessage, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = strings.close, tint = palette.emerald)
                        }
                    }
                }
            }

            // Section Horizontal Selector Ribbon (Filtered by Permissions)
            Surface(color = palette.surface, tonalElevation = 2.dp) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allowedSections, key = { it.number }) { section ->
                        val selected = section == currentSection
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) palette.emerald else palette.surfaceElevated,
                            modifier = Modifier
                                .border(
                                    width = 1.dp,
                                    color = if (selected) palette.emerald else palette.border,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { currentSection = section }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = section.icon,
                                    contentDescription = null,
                                    tint = if (selected) palette.onPrimary else palette.turquoise,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${section.number}. ${if (strings.isEnglish) section.titleEn else section.titleAr}",
                                    color = if (selected) palette.onPrimary else palette.textPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = palette.border)

            when (currentSection) {
                AdminSection.OVERVIEW -> AdminOverviewSection(
                    stats = stats,
                    reports = reports,
                    channels = channels,
                    allowedSections = allowedSections,
                    myNumericId = myNumericLabel,
                    currentAdminUser = currentUser,
                    canCustomizeAppearance = adminPermissions.isFullSuperAdmin || adminPermissions.canManageFramesAndNeon,
                    onCustomizeSelf = { u -> frameNeonInitialTab = 0; frameNeonTarget = u },
                    onJumpTo = { currentSection = it }
                )

                AdminSection.STATISTICS -> AdminStatisticsSection(
                    stats = stats,
                    onSelectFilter = { filter ->
                        activeUserFilter = filter
                        currentSection = AdminSection.USERS
                    }
                )

                AdminSection.USERS -> {
                    val now = System.currentTimeMillis()
                    var localQuery by remember { mutableStateOf("") }
                    val filteredUsers = remember(users, activeUserFilter, localQuery, now) {
                        users.filter { activeUserFilter.matches(it, now) && matchesAdminQuery(it, localQuery) }
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        AdminInlineSearchBar(
                            query = localQuery,
                            onQueryChange = { localQuery = it }
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AdminUserFilter.entries.filter { it.isPrimaryChip || it == activeUserFilter }.forEach { f ->
                                FilterChip(
                                    selected = activeUserFilter == f,
                                    onClick = { activeUserFilter = f },
                                    label = { Text(f.labelAr, maxLines = 1) }
                                )
                            }
                        }
                        AdminUsersListSection(
                            busyOps = busyOps,
                            title = "${activeUserFilter.labelAr} (${filteredUsers.size})",
                            users = filteredUsers,
                            canVerifyUsers = adminPermissions.isFullSuperAdmin || adminPermissions.canManageVerification,
                            canManageFrames = adminPermissions.isFullSuperAdmin || adminPermissions.canManageFramesAndNeon || adminPermissions.canManageLibrary,
                            canModerateUsers = adminPermissions.isFullSuperAdmin || adminPermissions.canManageUsersModeration,
                            canDeleteUsers = adminPermissions.isFullSuperAdmin,
                            onInspect = { viewModel.inspectUser(it.uid) },
                            onVerify = { verifyTarget = it },
                            onFrameNeon = { frameNeonInitialTab = 0; frameNeonTarget = it },
                            onWarn = { warnTarget = it },
                            onBanToggle = { u -> if (u.isBanned) viewModel.unbanUser(u) else banTarget = u },
                            onHideToggle = { hideTarget = it },
                            onDelete = { deleteTarget = it },
                            onOpenChat = { u -> viewModel.openDirectChatWithUser(u.uid, onOpenChat) }
                        )
                    }
                }

                AdminSection.SEARCH -> Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::updateSearch,
                        placeholder = { Text("ابحث بـ WESAL ID (مثل 10001) أو @username أو الاسم أو البريد أو UID") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.turquoise) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearch("") }) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = palette.textSecondary)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                    AdminUsersListSection(
                        busyOps = busyOps,
                        title = "نتائج البحث الإداري الشامل (${searchResults.size})",
                        users = searchResults,
                        canVerifyUsers = adminPermissions.isFullSuperAdmin || adminPermissions.canManageVerification,
                        canManageFrames = adminPermissions.isFullSuperAdmin || adminPermissions.canManageFramesAndNeon || adminPermissions.canManageLibrary,
                        canModerateUsers = adminPermissions.isFullSuperAdmin || adminPermissions.canManageUsersModeration,
                        canDeleteUsers = adminPermissions.isFullSuperAdmin,
                        onInspect = { viewModel.inspectUser(it.uid) },
                        onVerify = { verifyTarget = it },
                        onFrameNeon = { frameNeonInitialTab = 0; frameNeonTarget = it },
                        onWarn = { warnTarget = it },
                        onBanToggle = { u -> if (u.isBanned) viewModel.unbanUser(u) else banTarget = u },
                        onHideToggle = { hideTarget = it },
                        onDelete = { deleteTarget = it },
                        onOpenChat = { u -> viewModel.openDirectChatWithUser(u.uid, onOpenChat) }
                    )
                }

                AdminSection.REPORTS -> AdminReportsSection(
                    busyOps = busyOps,
                    reports = reports,
                    onlyWithContentSnapshot = false,
                    onReviewReport = { reviewReportTarget = it },
                    onOpenChat = { r -> viewModel.inspectReportedChat(r, onOpenChat) },
                    onOpenUser = onOpenUserProfile
                )

                AdminSection.SUPPORT_TICKETS -> AdminSupportTicketsSection(
                    busyOps = busyOps,
                    tickets = supportTickets,
                    selectedTicketId = selectedSupportTicketId,
                    messages = supportTicketMessages,
                    onSelectTicket = { viewModel.selectSupportTicket(it) },
                    onReply = { ticketId, text -> viewModel.replySupportTicket(ticketId, text) },
                    onStatusChange = { ticketId, status -> viewModel.setSupportTicketStatus(ticketId, status) }
                )

                AdminSection.BANS_WARNINGS -> AdminBansWarningsSection(
                    busyOps = busyOps,
                    users = users,
                    onWarn = { warnTarget = it },
                    onBan = { banTarget = it },
                    onUnban = { viewModel.unbanUser(it) },
                    onInspect = { viewModel.inspectUser(it.uid) }
                )

                AdminSection.CHANNELS -> AdminChannelsSection(
                    busyOps = busyOps,
                    channels = channels,
                    onOpenChannel = onOpenChannel,
                    onSuspendToggle = { ch -> viewModel.setChannelSuspended(ch, !ch.isSuspended) },
                    onDeleteChannel = { viewModel.deleteChannel(it) }
                )

                AdminSection.CONTENT_MOD -> AdminReportsSection(
                    busyOps = busyOps,
                    reports = reports.filter {
                        it.targetType != "USER" || it.targetContentSnapshot.isNotBlank() || it.targetChatId.isNotBlank()
                    },
                    onlyWithContentSnapshot = true,
                    onReviewReport = { reviewReportTarget = it },
                    onOpenChat = { r -> viewModel.inspectReportedChat(r, onOpenChat) },
                    onOpenUser = onOpenUserProfile
                )

                AdminSection.VERIFICATION_APPEARANCE -> AdminVerificationAndAppearanceSection(
                    busyOps = busyOps,
                    users = users,
                    logs = verificationLogs,
                    globalFrameEnabled = globalFrameEnabled,
                    globalAppearance = globalAppearance,
                    canVerifyUsers = adminPermissions.isFullSuperAdmin || adminPermissions.canManageVerification,
                    canManageFrames = adminPermissions.isFullSuperAdmin || adminPermissions.canManageFramesAndNeon || adminPermissions.canManageLibrary,
                    canManageAuditLogs = isSuperAdmin,
                    onToggleGlobalFrame = viewModel::toggleGlobalCustomerFrame,
                    onSaveGlobalCustomerDefaults = viewModel::saveGlobalCustomerDefaults,
                    onOpenVerifyDialog = { verifyTarget = it },
                    onOpenFrameNeonDialog = { frameNeonInitialTab = 0; frameNeonTarget = it },
                    onInspect = { viewModel.inspectUser(it.uid) },
                    onDeleteAuditLog = { logId -> viewModel.deleteAuditLog(Constants.COLLECTION_VERIFICATION_LOGS, logId) },
                    onClearAuditLogs = { viewModel.clearAuditLogs(Constants.COLLECTION_VERIFICATION_LOGS) }
                )

                AdminSection.WESAL_TEAM -> AdminWesalTeamSection(
                    busyOps = busyOps,
                    channel = officialTeamChannel,
                    onOpenChannel = {
                        val cid = officialTeamChannel?.channelId ?: Constants.OFFICIAL_WESAL_TEAM_CHANNEL_ID
                        onOpenChannel(cid)
                    },
                    onPublishPost = viewModel::publishOfficialTeamPost,
                    // §9.6: official identity editor, gated on the SAME permission
                    // expression this screen uses for frame/neon management.
                    canManageOfficialIdentity = adminPermissions.isFullSuperAdmin || adminPermissions.canManageFramesAndNeon || adminPermissions.canManageLibrary,
                    officialPhotoUrl = officialChatPhotoUrl.ifBlank { officialTeamChannel?.avatarUrl.orEmpty() },
                    officialIdentityBusy = "official_identity" in busyOps,
                    onPickOfficialPhoto = { ctx, uri -> viewModel.updateOfficialIdentityPhoto(ctx, uri) },
                    // Round 5: the official account (10001) edited exactly like any
                    // user — the same parent-hosted verify / frame-neon dialogs.
                    officialUser = users.firstOrNull { it.numericId == Constants.OWNER_ADMIN_NUMERIC_ID },
                    canEditOfficialAccount = adminPermissions.isFullSuperAdmin || adminPermissions.canManageFramesAndNeon || adminPermissions.canManageLibrary,
                    canVerifyOfficialAccount = adminPermissions.isFullSuperAdmin || adminPermissions.canManageVerification,
                    onOpenOfficialVerify = { verifyTarget = it },
                    onOpenOfficialFrameNeon = { frameNeonInitialTab = 0; frameNeonTarget = it }
                )

                AdminSection.WELCOME_MSG -> AdminWelcomeConfigSection(
                    busyOps = busyOps,
                    config = welcomeConfig,
                    onSave = viewModel::saveWelcomeConfig,
                    onSendTest = viewModel::sendWelcomeTestToSelf
                )

                AdminSection.ANNOUNCEMENTS -> AdminAnnouncementsSection(
                    busyOps = busyOps,
                    announcements = announcements,
                    onPublish = viewModel::publishOfficialAnnouncement
                )

                AdminSection.ACTION_LOGS -> AdminAuditLogsListSection(
                    busyOps = busyOps,
                    clearOpKey = "logs_clear_${Constants.COLLECTION_ADMIN_LOGS}",
                    title = "سجل الإجراءات الإدارية الشامل (${adminActionLogs.size})",
                    logs = adminActionLogs,
                    canDeleteLogs = isSuperAdmin,
                    onDeleteLog = { logId -> viewModel.deleteAuditLog(Constants.COLLECTION_ADMIN_LOGS, logId) },
                    onClearLogs = { viewModel.clearAuditLogs(Constants.COLLECTION_ADMIN_LOGS) }
                )

                AdminSection.ADMIN_SETTINGS -> AdminSettingsSection(
                    busyOps = busyOps,
                    globalFrameEnabled = globalFrameEnabled,
                    onToggleGlobalFrame = viewModel::toggleGlobalCustomerFrame,
                    appUpdateConfig = appUpdateConfig,
                    onSaveAppUpdateConfig = viewModel::saveAppUpdateConfig,
                    onRefreshAll = viewModel::refreshAll
                )

                AdminSection.HIDDEN_ACCOUNTS -> {
                    var hiddenQuery by remember { mutableStateOf("") }
                    val hiddenFiltered = remember(users, hiddenQuery) {
                        users.filter { matchesAdminQuery(it, hiddenQuery) }
                            .sortedByDescending { it.hiddenFromSearch }
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        AdminInlineSearchBar(
                            query = hiddenQuery,
                            onQueryChange = { hiddenQuery = it }
                        )
                        AdminUsersListSection(
                            busyOps = busyOps,
                            title = "التحكم في إخفاء الحسابات من البحث (المخفية: ${users.count { it.hiddenFromSearch }})",
                            users = hiddenFiltered,
                            canVerifyUsers = adminPermissions.isFullSuperAdmin || adminPermissions.canManageVerification,
                            canManageFrames = adminPermissions.isFullSuperAdmin || adminPermissions.canManageFramesAndNeon || adminPermissions.canManageLibrary,
                            canModerateUsers = adminPermissions.isFullSuperAdmin || adminPermissions.canManageUsersModeration,
                            canDeleteUsers = adminPermissions.isFullSuperAdmin,
                            onInspect = { viewModel.inspectUser(it.uid) },
                            onVerify = { verifyTarget = it },
                            onFrameNeon = { frameNeonInitialTab = 0; frameNeonTarget = it },
                            onWarn = { warnTarget = it },
                            onBanToggle = { u -> if (u.isBanned) viewModel.unbanUser(u) else banTarget = u },
                            onHideToggle = { hideTarget = it },
                            onDelete = { deleteTarget = it },
                            onOpenChat = { u -> viewModel.openDirectChatWithUser(u.uid, onOpenChat) }
                        )
                    }
                }

                AdminSection.HIDDEN_LOGS -> AdminAuditLogsListSection(
                    busyOps = busyOps,
                    clearOpKey = "logs_clear_${Constants.COLLECTION_HIDDEN_LOGS}",
                    title = "سجل تدقيق إخفاء الحسابات من البحث (${hiddenLogs.size})",
                    logs = hiddenLogs,
                    canDeleteLogs = isSuperAdmin,
                    onDeleteLog = { logId -> viewModel.deleteAuditLog(Constants.COLLECTION_HIDDEN_LOGS, logId) },
                    onClearLogs = { viewModel.clearAuditLogs(Constants.COLLECTION_HIDDEN_LOGS) }
                )

                AdminSection.ONLINE_NOW -> {
                    val now = System.currentTimeMillis()
                    val onlineUsers = users.filter { u ->
                        u.isOnline || (maxOf(u.lastHeartbeatAt, u.lastSeen) > 0L && now - maxOf(u.lastHeartbeatAt, u.lastSeen) <= Constants.PRESENCE_ONLINE_TTL_MS)
                    }
                    AdminOnlineUsersSection(
                        onlineUsers = onlineUsers,
                        now = now,
                        onInspect = { viewModel.inspectUser(it.uid) },
                        onOpenChat = { u -> viewModel.openDirectChatWithUser(u.uid, onOpenChat) }
                    )
                }

                AdminSection.CHAT_ACTIVITY -> AdminChatActivityMetadataSection(
                    users = users.filter { it.lastActivityAt > 0L || it.lastActivityType.isNotBlank() }
                        .sortedByDescending { it.lastActivityAt },
                    onInspect = { viewModel.inspectUser(it.uid) }
                )

                AdminSection.CONTENT_REVIEW -> AdminReportsSection(
                    busyOps = busyOps,
                    reports = reports,
                    onlyWithContentSnapshot = false,
                    onReviewReport = { reviewReportTarget = it },
                    onOpenChat = { r -> viewModel.inspectReportedChat(r, onOpenChat) },
                    onOpenUser = onOpenUserProfile
                )

                AdminSection.SUBSCRIPTION_REQUESTS -> AdminSubscriptionRequestsSection(
                    busyOps = busyOps,
                    requests = subscriptionRequests,
                    onReview = { reqId, approved, note -> viewModel.reviewSubscription(reqId, approved, note) },
                    onInspectUser = { uid -> if (uid.isNotBlank()) viewModel.inspectUser(uid) }
                )

                AdminSection.MODERATORS -> AdminModeratorsSection(
                    busyOps = busyOps,
                    users = users,
                    moderators = moderators,
                    onSavePermissions = viewModel::assignModerator
                )

                AdminSection.GLOBAL_APPEARANCE -> AdminGlobalAppearanceSection(
                    busyOps = busyOps,
                    config = globalAppearance,
                    onSave = viewModel::saveGlobalAppearance
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Modals & Action Dialogs
    // -----------------------------------------------------------------------

    inspectedUser?.let { u ->
        AlertDialog(
            onDismissRequest = viewModel::dismissInspectedUser,
            containerColor = palette.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WesalAvatar(
                        imageUrl = u.photoUrl,
                        name = u.displayName,
                        size = 44.dp,
                        isOnline = u.isOnline,
                        frameId = u.frameId,
                        frameExpiresAt = u.frameExpiresAt,
                        isCutout = u.photoRemovedBg,
                        isUserFramed = u.photoUserFramed,
                        iconUrl = WesalFrameLibrary.resolveIconUrl(u.iconId, u.iconUrl),
                        neonColor = if (u.neonEnabled) u.neonColor else "",
                        neonGlowIntensity = u.neonGlowIntensity
                    )
                    Column {
                        WesalNameWithBadge(
                            name = u.displayName.ifBlank { "WESAL User" },
                            isVerified = u.isVerified,
                            verificationType = u.verificationType,
                            verificationGlowColor = u.verificationGlowColor,
                            neonColor = u.neonColor,
                            neonGlowIntensity = u.neonGlowIntensity,
                            neonEnabled = u.neonEnabled,
                            fontWeight = FontWeight.Bold,
                            iconUrl = WesalFrameLibrary.resolveIconUrl(u.iconId, u.iconUrl)
                        )
                        Text("ID: ${u.numericId.ifBlank { "—" }} • @${u.publicId}", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AdminInfoLine("UID", u.uid)
                    AdminInfoLine("البريد الإلكتروني", u.email.ifBlank { "—" })
                    // Round 9 (owner rule): a phone number is visible ONLY to its
                    // owner — the admin inspect view shows the verification STATUS
                    // only, never the digits.
                    AdminInfoLine("توثيق الهاتف", if (u.isPhoneVerified) "موثّق ✓" else "غير موثّق")
                    AdminInfoLine("تاريخ الميلاد", u.birthDate.ifBlank { "—" })
                    AdminInfoLine("البايو", u.bio.ifBlank { "—" })
                    AdminInfoLine("الحالة", if (u.isBanned) "محظور (${u.banType}: ${u.banReason})" else if (u.deletedAt > 0L) "محذوف" else "نشط")
                    AdminInfoLine("الإنذارات", u.warningsCount.toString())
                    AdminInfoLine("الإخفاء من البحث", if (u.hiddenFromSearch) "مخفي (${u.hideFromSearchReason})" else "ظاهر")
                    AdminInfoLine("التوثيق", if (u.isVerified) "${u.verificationType} (${if (u.verificationExpiresAt == 0L) "دائم" else formatAdminTime(u.verificationExpiresAt)})" else "غير موثق")
                    AdminInfoLine("الإطار", u.frameId.ifBlank { "بدون" })
                    AdminInfoLine("الأيقونة", u.iconId.ifBlank { "بدون" })
                    AdminInfoLine("النيون", "${if (!u.neonEnabled || u.neonColor.isBlank()) "بدون" else u.neonColor} (${(u.neonGlowIntensity * 100).toInt()}%)")
                    AdminInfoLine("الجهاز / النسخة", "${u.lastDeviceName.ifBlank { "—" }} / ${u.lastAppVersion.ifBlank { "—" }}")
                    AdminInfoLine("الجلسات النشطة", u.activeSessionsCount.toString())
                    AdminInfoLine("تاريخ الإنشاء", formatAdminTime(u.createdAt))
                    AdminInfoLine("آخر ظهور", formatAdminTime(u.lastSeen))

                    Spacer(Modifier.height(6.dp))
                    if (adminPermissions.isFullSuperAdmin || adminPermissions.canManageVerification || adminPermissions.canManageFramesAndNeon || adminPermissions.canManageLibrary) {
                        // Round 9 (item 6): «إطار/أيقونة/نيون» cannot fit a
                        // half-width weighted button on one line — the two
                        // actions stack full-width instead of squeezing.
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            if (adminPermissions.isFullSuperAdmin || adminPermissions.canManageVerification) {
                                FilledTonalButton(
                                    onClick = { viewModel.dismissInspectedUser(); verifyTarget = u },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("التوثيق", maxLines = 1, softWrap = false) }
                            }
                            if (adminPermissions.isFullSuperAdmin || adminPermissions.canManageFramesAndNeon || adminPermissions.canManageLibrary) {
                                FilledTonalButton(
                                    onClick = { viewModel.dismissInspectedUser(); frameNeonInitialTab = 0; frameNeonTarget = u },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("إطار/أيقونة/نيون", maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                    if (adminPermissions.isFullSuperAdmin || adminPermissions.canManageUsersModeration) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(
                                onClick = { viewModel.dismissInspectedUser(); warnTarget = u },
                                modifier = Modifier.weight(1f)
                            ) { Text("إنذار", maxLines = 1) }
                            FilledTonalButton(
                                onClick = {
                                    viewModel.dismissInspectedUser()
                                    if (u.isBanned) viewModel.unbanUser(u) else banTarget = u
                                },
                                enabled = "ban_${u.uid}" !in busyOps,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (u.isBanned) palette.emerald.copy(alpha = 0.16f) else palette.error.copy(alpha = 0.16f)
                                )
                            ) { Text(if (u.isBanned) "رفع الحظر" else "حظر", maxLines = 1) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        if (adminPermissions.isFullSuperAdmin || adminPermissions.canManageUsersModeration) {
                            OutlinedButton(
                                onClick = { viewModel.dismissInspectedUser(); hideTarget = u },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (u.hiddenFromSearch) "إظهار" else "إخفاء", maxLines = 1) }
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.dismissInspectedUser()
                                viewModel.openDirectChatWithUser(u.uid, onOpenChat)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("محادثة", maxLines = 1) }
                        OutlinedButton(
                            onClick = {
                                viewModel.dismissInspectedUser()
                                onOpenUserProfile(u.uid)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("الملف", maxLines = 1) }
                    }
                    if (adminPermissions.isFullSuperAdmin && !Constants.isProtectedAdminNumericId(u.numericId) && !Constants.isSuperAdminEmail(u.email)) {
                        OutlinedButton(
                            onClick = { viewModel.dismissInspectedUser(); deleteTarget = u },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("حذف الحساب نهائيًا")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissInspectedUser) { Text(strings.close) }
            }
        )
    }

    verifyTarget?.let { target ->
        AdminVerificationDialog(
            user = target,
            busyOps = busyOps,
            onDismiss = { verifyTarget = null },
            onApply = { verified, seal, glowColor, expiresAt, durationLabel, note ->
                viewModel.applyVerification(target, verified, seal, glowColor, expiresAt, durationLabel, note)
                verifyTarget = null
            }
        )
    }

    frameNeonTarget?.let { target ->
        AdminFrameAndNeonDialog(
            user = target,
            busyOps = busyOps,
            initialTab = frameNeonInitialTab,
            onDismiss = { frameNeonTarget = null },
            onApplyFrame = { frameId, expiresAt, durationLabel ->
                viewModel.applyFrame(target, frameId, expiresAt, durationLabel)
                frameNeonTarget = null
            },
            onApplyIcon = { iconId, iconUrl ->
                viewModel.applyIcon(target, iconId, iconUrl)
                frameNeonTarget = null
            },
            onApplyNeon = { preset, glow, enabled ->
                viewModel.applyNeon(target, preset, glow, enabled)
                frameNeonTarget = null
            },
            onApplyNameplateColor = { colorKey ->
                viewModel.applyNameplateColor(target, colorKey)
                frameNeonTarget = null
            }
        )
    }

    warnTarget?.let { target ->
        var reason by remember(target.uid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { warnTarget = null },
            containerColor = palette.surface,
            title = { Text("إرسال إنذار إداري رسمي", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${target.displayName} (ID: ${target.numericId})", color = palette.textSecondary)
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("سبب الإنذار") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.warnUser(target, reason); warnTarget = null },
                    // AUDIT-C: was enabled with a blank reason — the dialog
                    // closed and the VM silently dropped the warn (only a
                    // banner the user may miss), looking like a dead button.
                    enabled = reason.isNotBlank() && "warn_${target.uid}" !in busyOps,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.warning)
                ) { Text("إرسال الإنذار") }
            },
            dismissButton = {
                TextButton(onClick = { warnTarget = null }) { Text(strings.cancel) }
            }
        )
    }

    banTarget?.let { target ->
        AdminBanDialog(
            user = target,
            busyOps = busyOps,
            onDismiss = { banTarget = null },
            onConfirmBan = { permanent, expiresAt, reason, blockIdentifiers ->
                viewModel.banUser(target, permanent, expiresAt, reason, blockIdentifiers)
                banTarget = null
            }
        )
    }

    hideTarget?.let { target ->
        var reason by remember(target.uid) { mutableStateOf(target.hideFromSearchReason) }
        val willHide = !target.hiddenFromSearch
        AlertDialog(
            onDismissRequest = { hideTarget = null },
            containerColor = palette.surface,
            title = { Text(if (willHide) "إخفاء الحساب من البحث" else "إعادة إظهار الحساب", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${target.displayName} (ID: ${target.numericId})", color = palette.textSecondary)
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("السبب (يُسجل في سجل الإخفاء)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setHiddenFromSearch(target, willHide, reason)
                        hideTarget = null
                    },
                    enabled = "hide_${target.uid}" !in busyOps
                ) { Text(strings.confirm) }
            },
            dismissButton = { TextButton(onClick = { hideTarget = null }) { Text(strings.cancel) } }
        )
    }

    reviewReportTarget?.let { report ->
        AdminReportReviewDialog(
            report = report,
            busyOps = busyOps,
            onDismiss = { reviewReportTarget = null },
            onSaveStatus = { status, note, resolution ->
                viewModel.updateReport(report.reportId, status, note, resolution)
                reviewReportTarget = null
            },
            onOpenReportedChat = {
                reviewReportTarget = null
                viewModel.inspectReportedChat(report, onOpenChat)
            },
            onInspectReportedUser = {
                reviewReportTarget = null
                if (report.reportedUid.isNotBlank()) viewModel.inspectUser(report.reportedUid)
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = palette.surface,
            title = { Text("تأكيد حذف الحساب", color = palette.error, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "هل تريد حذف حساب ${target.displayName.ifBlank { target.numericId }} (ID: ${target.numericId}) نهائيًا من قاعدة البيانات أو تعطيله مؤقتًا؟",
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.deleteUser(target, permanent = false); deleteTarget = null },
                        enabled = "delete_${target.uid}" !in busyOps
                    ) {
                        Text("تعطيل مؤقت")
                    }
                    Button(
                        onClick = { viewModel.deleteUser(target, permanent = true); deleteTarget = null },
                        enabled = "delete_${target.uid}" !in busyOps,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                    ) { Text("حذف نهائي") }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(strings.cancel) }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Sections 1 - 22 Composables
// ---------------------------------------------------------------------------

@Composable
private fun AdminOverviewSection(
    stats: AdminDashboardStats,
    reports: List<AdminReportRecord>,
    channels: List<WesalChannel>,
    allowedSections: List<AdminSection>,
    myNumericId: String,
    currentAdminUser: WesalUser? = null,
    canCustomizeAppearance: Boolean = false,
    onCustomizeSelf: (WesalUser) -> Unit = {},
    onJumpTo: (AdminSection) -> Unit
) {
    val palette = WesalColors.current
    val openReports = reports.count { it.status == "NEW" || it.status == "IN_REVIEW" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = palette.surfaceElevated),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.emerald.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = palette.emerald)
                        Text("مركز القيادة الإداري — WESAL Messenger", color = palette.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    Text("المعرّف النشط: WESAL ID $myNumericId • إدارة عليا 10001 / 10002", color = palette.emerald, style = MaterialTheme.typography.bodySmall)
                    Text("جميع الصلاحيات مرتبطة بالتحقق الخادمي + Firebase UID", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                    if (canCustomizeAppearance && currentAdminUser != null) {
                        OutlinedButton(
                            onClick = { onCustomizeSelf(currentAdminUser) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("تخصيص أيقونة وإطار ونيون حسابي الإداري (${currentAdminUser.numericId.ifBlank { myNumericId }})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                AdminKpiCard("إجمالي الحسابات", stats.totalAccounts.toString(), palette.turquoise, Modifier.weight(1f)) { onJumpTo(AdminSection.USERS) }
                AdminKpiCard("متصلون الآن", stats.onlineNowCount.toString(), palette.emerald, Modifier.weight(1f)) { onJumpTo(AdminSection.ONLINE_NOW) }
                AdminKpiCard("بلاغات مفتوحة", openReports.toString(), if (openReports > 0) palette.error else palette.textSecondary, Modifier.weight(1f)) { onJumpTo(AdminSection.REPORTS) }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                AdminKpiCard("حسابات موثقة", stats.verifiedCount.toString(), palette.verifiedBlue, Modifier.weight(1f)) { onJumpTo(AdminSection.VERIFICATION_APPEARANCE) }
                AdminKpiCard("محظورة", stats.bannedCount.toString(), palette.warning, Modifier.weight(1f)) { onJumpTo(AdminSection.BANS_WARNINGS) }
                AdminKpiCard("القنوات", channels.size.toString(), palette.turquoise, Modifier.weight(1f)) { onJumpTo(AdminSection.CHANNELS) }
            }
        }

        item {
            Text("الوصول السريع للأقسام المتاحة (${allowedSections.size})", color = palette.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }

        items(allowedSections.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { sec ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onJumpTo(sec) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(sec.icon, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                            Text("${sec.number}. ${sec.titleAr}", color = palette.textPrimary, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AdminKpiCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = WesalColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = accent, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, color = palette.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AdminStatisticsSection(
    stats: AdminDashboardStats,
    onSelectFilter: (AdminUserFilter) -> Unit
) {
    val palette = WesalColors.current
    val rows = listOf(
        Triple("إجمالي الحسابات المسجلة", stats.totalAccounts, AdminUserFilter.ALL),
        Triple("المتصلون الآن (Heartbeat حي)", stats.onlineNowCount, AdminUserFilter.ONLINE),
        Triple("غير متصلين حاليًا", stats.offlineCount, AdminUserFilter.OFFLINE),
        Triple("المسجلون اليوم (24 ساعة)", stats.newTodayCount, AdminUserFilter.NEW_TODAY),
        Triple("المسجلون خلال يومين", stats.newTwoDaysCount, AdminUserFilter.NEW_2DAYS),
        Triple("المسجلون خلال أسبوع", stats.newWeekCount, AdminUserFilter.NEW_WEEK),
        Triple("المسجلون خلال شهر", stats.newMonthCount, AdminUserFilter.NEW_MONTH),
        Triple("النشطون اليوم", stats.activeTodayCount, AdminUserFilter.ACTIVE_TODAY),
        Triple("النشطون خلال أسبوع", stats.activeWeekCount, AdminUserFilter.ACTIVE_WEEK),
        Triple("النشطون خلال شهر", stats.activeMonthCount, AdminUserFilter.ACTIVE_MONTH),
        Triple("الحسابات الموثقة", stats.verifiedCount, AdminUserFilter.VERIFIED),
        Triple("الحسابات المحظورة", stats.bannedCount, AdminUserFilter.BANNED),
        Triple("الحسابات المخفية من البحث", stats.hiddenCount, AdminUserFilter.HIDDEN),
        Triple("الحسابات المحذوفة", stats.deletedCount, AdminUserFilter.DELETED)
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("اضغط على أي إحصائية لفتح قائمة الحسابات المطابقة لها", color = palette.turquoise, style = MaterialTheme.typography.labelMedium)
        }
        items(rows) { (label, count, filter) ->
            Card(
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectFilter(filter) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = palette.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(count.toString(), color = palette.emerald, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminUsersListSection(
    busyOps: Set<String> = emptySet(),
    title: String,
    users: List<WesalUser>,
    canVerifyUsers: Boolean = true,
    canManageFrames: Boolean = true,
    canModerateUsers: Boolean = true,
    canDeleteUsers: Boolean,
    onInspect: (WesalUser) -> Unit,
    onVerify: (WesalUser) -> Unit,
    onFrameNeon: (WesalUser) -> Unit,
    onWarn: (WesalUser) -> Unit,
    onBanToggle: (WesalUser) -> Unit,
    onHideToggle: (WesalUser) -> Unit,
    onDelete: (WesalUser) -> Unit,
    onOpenChat: (WesalUser) -> Unit
) {
    val palette = WesalColors.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(title, color = palette.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        }
        items(users, key = { it.uid }) { user ->
            val isProtected = Constants.isProtectedAdminNumericId(user.numericId) || Constants.isSuperAdminEmail(user.email)
            Card(
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onInspect(user) }
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        WesalAvatar(
                            imageUrl = user.photoUrl,
                            name = user.displayName.ifBlank { "@${user.publicId}" },
                            size = 46.dp,
                            isOnline = user.isOnline,
                            frameId = user.frameId,
                            frameExpiresAt = user.frameExpiresAt,
                            isCutout = user.photoRemovedBg,
                            isUserFramed = user.photoUserFramed,
                            iconUrl = WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl),
                            neonColor = if (user.neonEnabled) user.neonColor else "",
                            neonGlowIntensity = user.neonGlowIntensity
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            WesalNameWithBadge(
                                name = user.displayName.ifBlank { "WESAL User" },
                                isVerified = user.isVerified,
                                verificationType = user.verificationType,
                                verificationGlowColor = user.verificationGlowColor,
                                neonColor = user.neonColor,
                                neonGlowIntensity = user.neonGlowIntensity,
                                neonEnabled = user.neonEnabled,
                                fontWeight = FontWeight.Bold,
                                iconUrl = WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl)
                            )
                            Text(
                                text = "ID: ${user.numericId.ifBlank { "—" }} • @${user.publicId.ifBlank { "—" }}",
                                color = palette.textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            val tags = buildList {
                                if (user.numericId == Constants.OWNER_ADMIN_NUMERIC_ID) add("المالك الرئيسي")
                                else if (user.numericId == Constants.SECONDARY_ADMIN_NUMERIC_ID) add("أدمن كامل")
                                if (user.isBanned) add("محظور")
                                if (user.deletedAt > 0L) add("معطل")
                                if (user.hiddenFromSearch) add("مخفي")
                                if (user.warningsCount > 0) add("إنذارات: ${user.warningsCount}")
                                if (user.frameId.isNotBlank()) add("إطار: ${user.frameId}")
                                if (user.iconId.isNotBlank()) add("أيقونة")
                                if (user.neonEnabled && user.neonColor.isNotBlank()) add("نيون: ${user.neonColor}")
                            }
                            if (tags.isNotEmpty()) {
                                Text(tags.joinToString(" • "), color = palette.turquoise, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmallAdminChip("التفاصيل", Icons.Default.Info) { onInspect(user) }
                        if (canVerifyUsers) {
                            SmallAdminChip("توثيق", Icons.Default.Verified) { onVerify(user) }
                        }
                        if (canManageFrames) {
                            SmallAdminChip("إطار/أيقونة/نيون", Icons.Default.AutoAwesome) { onFrameNeon(user) }
                        }
                        if (canModerateUsers) {
                            SmallAdminChip("إنذار", Icons.Default.Warning) { onWarn(user) }
                            if (!isProtected) {
                                // §9.11: unban fires the op directly — gate the chip on its op key.
                                SmallAdminChip(
                                    if (user.isBanned) "فك حظر" else "حظر",
                                    Icons.Default.Block,
                                    enabled = "ban_${user.uid}" !in busyOps
                                ) { onBanToggle(user) }
                            }
                            SmallAdminChip(if (user.hiddenFromSearch) "إظهار" else "إخفاء", Icons.Default.VisibilityOff) { onHideToggle(user) }
                        }
                        if (canDeleteUsers && !isProtected) {
                            SmallAdminChip("حذف", Icons.Default.DeleteForever) { onDelete(user) }
                        }
                        SmallAdminChip(
                            "مراسلة",
                            Icons.AutoMirrored.Filled.Chat,
                            enabled = "open_chat_${user.uid}" !in busyOps
                        ) { onOpenChat(user) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAdminChip(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = WesalColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = palette.surfaceElevated,
        modifier = Modifier
            .border(1.dp, palette.border, RoundedCornerShape(10.dp))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(14.dp))
            Text(text, color = palette.textPrimary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AdminSupportTicketsSection(
    busyOps: Set<String> = emptySet(),
    tickets: List<SupportTicket>,
    selectedTicketId: String?,
    messages: List<SupportTicketMessage>,
    onSelectTicket: (String?) -> Unit,
    onReply: (String, String) -> Unit,
    onStatusChange: (String, String) -> Unit
) {
    val palette = WesalColors.current
    val selectedTicket = tickets.firstOrNull { it.ticketId == selectedTicketId }
    // State is hoisted unconditionally: remember{} must never be called conditionally,
    // or switching between the list and thread branches corrupts the slot table.
    var statusFilter by remember { mutableStateOf("ALL") }
    var replyText by remember(selectedTicketId) { mutableStateOf("") }

    if (selectedTicket == null) {
        val filters = listOf("ALL", "OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED")
        val filtered =
            if (statusFilter == "ALL") tickets else tickets.filter { it.status == statusFilter }
        Column(modifier = Modifier.fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filters) { f ->
                    FilterChip(
                        selected = statusFilter == f,
                        onClick = { statusFilter = f },
                        label = { Text(f) }
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("لا توجد تذاكر دعم مطابقة", color = palette.textMuted)
                        }
                    }
                }
                items(filtered, key = { it.ticketId }) { ticket ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSelectTicket(ticket.ticketId) }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    ticket.referenceNumber.ifBlank { ticket.ticketId.take(8) },
                                    color = palette.turquoise,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    ticket.status,
                                    color = palette.textSecondary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Text(
                                ticket.subject.ifBlank { "طلب دعم" },
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "${ticket.userName} • ${ticket.userPublicId.ifBlank { ticket.userNumericId }}",
                                color = palette.textSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (ticket.lastReplyText.isNotBlank()) {
                                Text(
                                    ticket.lastReplyText,
                                    color = palette.textMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2
                                )
                            }
                            Text(
                                formatAdminTime(ticket.updatedAt),
                                color = palette.textMuted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
        return
    }

    // Thread view for the selected ticket.
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconButton(onClick = { onSelectTicket(null) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = palette.textPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${selectedTicket.referenceNumber.ifBlank { selectedTicket.ticketId.take(8) }} • ${selectedTicket.subject}",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Text(
                    "${selectedTicket.userName} • ${selectedTicket.userPublicId.ifBlank { selectedTicket.userNumericId }} • ${selectedTicket.status}",
                    color = palette.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            listOf("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED").forEach { status ->
                FilterChip(
                    selected = selectedTicket.status == status,
                    onClick = { onStatusChange(selectedTicket.ticketId, status) },
                    enabled = "ticket_status_${selectedTicket.ticketId}" !in busyOps,
                    label = { Text(status, maxLines = 1) }
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = palette.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(selectedTicket.message, color = palette.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            formatAdminTime(selectedTicket.createdAt),
                            color = palette.textMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            items(messages, key = { it.messageId }) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.isFromAdmin) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.isFromAdmin) palette.surfaceElevated else palette.surface
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                if (msg.isFromAdmin) "الدعم" else msg.senderName.ifBlank { selectedTicket.userName },
                                color = palette.turquoise,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(msg.text, color = palette.textPrimary, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatAdminTime(msg.createdAt),
                                color = palette.textMuted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            OutlinedTextField(
                value = replyText,
                onValueChange = { replyText = it },
                label = { Text("رد الدعم…") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            IconButton(
                onClick = {
                    onReply(selectedTicket.ticketId, replyText)
                    replyText = ""
                },
                enabled = replyText.isNotBlank() && "ticket_reply_${selectedTicket.ticketId}" !in busyOps
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = palette.turquoise)
            }
        }
    }
}

@Composable
private fun AdminReportsSection(
    busyOps: Set<String> = emptySet(),
    reports: List<AdminReportRecord>,
    onlyWithContentSnapshot: Boolean,
    onReviewReport: (AdminReportRecord) -> Unit,
    onOpenChat: (AdminReportRecord) -> Unit,
    onOpenUser: (String) -> Unit
) {
    val palette = WesalColors.current
    var statusFilter by remember { mutableStateOf("ALL") }
    val filters = listOf("ALL", "NEW", "IN_REVIEW", "RESOLVED", "REJECTED", "CLOSED")

    val filtered = remember(reports, statusFilter) {
        if (statusFilter == "ALL") reports else reports.filter { it.status == statusFilter }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filters) { f ->
                FilterChip(
                    selected = statusFilter == f,
                    onClick = { statusFilter = f },
                    label = { Text(f) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filtered.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (onlyWithContentSnapshot) "لا توجد بلاغات محتوى حالياً" else "لا توجد بلاغات مطابقة",
                            color = palette.textMuted
                        )
                    }
                }
            }
            items(filtered, key = { it.reportId }) { report ->
                Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${report.targetType} • ${report.reportedName.ifBlank { report.reportedUid }}",
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = report.status,
                                color = if (report.status == "NEW") palette.error else palette.emerald,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text("المُبلِّغ: ${report.reporterName.ifBlank { report.reporterUid }}", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                        Text("السبب: ${report.reason}", color = palette.textPrimary, style = MaterialTheme.typography.bodySmall)
                        if (report.targetContentSnapshot.isNotBlank()) {
                            Surface(color = palette.surfaceElevated, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "لقطة المحتوى المحفوظة:\n${report.targetContentSnapshot}",
                                    color = palette.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                        if (report.adminNote.isNotBlank()) {
                            Text("ملاحظة الإدارة: ${report.adminNote}", color = palette.turquoise, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(formatAdminTime(report.createdAt), color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(onClick = { onReviewReport(report) }, modifier = Modifier.weight(1f)) {
                                Text("معالجة البلاغ", maxLines = 1)
                            }
                            if (report.targetChatId.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { onOpenChat(report) },
                                    enabled = "report_chat_${report.reportId}" !in busyOps
                                ) {
                                    Text("فتح المحادثة", maxLines = 1)
                                }
                            }
                            if (report.reportedUid.isNotBlank()) {
                                OutlinedButton(onClick = { onOpenUser(report.reportedUid) }) {
                                    Text("الحساب", maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminBansWarningsSection(
    busyOps: Set<String> = emptySet(),
    users: List<WesalUser>,
    onWarn: (WesalUser) -> Unit,
    onBan: (WesalUser) -> Unit,
    onUnban: (WesalUser) -> Unit,
    onInspect: (WesalUser) -> Unit
) {
    val palette = WesalColors.current
    var query by remember { mutableStateOf("") }
    val relevant = remember(users, query) {
        users.filter { matchesAdminQuery(it, query) }
            .sortedWith(
                compareByDescending<WesalUser> { it.isBanned }
                    .thenByDescending { it.warningsCount }
            )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        AdminInlineSearchBar(query = query, onQueryChange = { query = it })
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(relevant, key = { it.uid }) { user ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = palette.surface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onInspect(user) }
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                WesalNameWithBadge(
                                    name = user.displayName.ifBlank { "@${user.publicId}" },
                                    isVerified = user.isVerified,
                                    verificationType = user.verificationType,
                                    verificationGlowColor = user.verificationGlowColor,
                                    neonColor = user.neonColor,
                                    neonGlowIntensity = user.neonGlowIntensity,
                                    neonEnabled = user.neonEnabled,
                                    iconUrl = WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl)
                                )
                                Text("ID: ${user.numericId} • إنذارات: ${user.warningsCount}", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                            if (user.isBanned) {
                                Text("محظور (${user.banType})", color = palette.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (user.banReason.isNotBlank()) {
                            Text("سبب الحظر: ${user.banReason}", color = palette.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { onWarn(user) }) { Text("إرسال إنذار") }
                            if (user.isBanned) {
                                Button(
                                    onClick = { onUnban(user) },
                                    enabled = "ban_${user.uid}" !in busyOps,
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald)
                                ) { Text("رفع الحظر") }
                            } else if (!Constants.isProtectedAdminNumericId(user.numericId) && !Constants.isSuperAdminEmail(user.email)) {
                                Button(
                                    onClick = { onBan(user) },
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                                ) { Text("حظر") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminChannelsSection(
    busyOps: Set<String> = emptySet(),
    channels: List<WesalChannel>,
    onOpenChannel: (String) -> Unit,
    onSuspendToggle: (WesalChannel) -> Unit,
    onDeleteChannel: (WesalChannel) -> Unit
) {
    val palette = WesalColors.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("جميع القنوات (${channels.size})", color = palette.textSecondary, style = MaterialTheme.typography.labelLarge)
        }
        items(channels, key = { it.channelId }) { ch ->
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(14.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WesalAvatar(imageUrl = ch.avatarUrl, name = ch.title, size = 42.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            WesalNameWithBadge(
                                name = ch.title,
                                isVerified = ch.isVerified,
                                verificationType = ch.verificationType,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${if (ch.isPublic) "عامة" else "خاصة"} • مشتركين: ${ch.subscribersCount}" +
                                    (if (ch.isOfficialTeamChannel) " • قناة رسمية" else "") +
                                    (if (ch.isSuspended) " • موقوفة" else ""),
                                color = if (ch.isSuspended) palette.error else palette.textSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onOpenChannel(ch.channelId) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("فتح")
                        }
                        if (!ch.isOfficialTeamChannel) {
                            OutlinedButton(
                                onClick = { onSuspendToggle(ch) },
                                enabled = "channel_suspend_${ch.channelId}" !in busyOps
                            ) {
                                Text(if (ch.isSuspended) "إعادة تفعيل" else "تعليق")
                            }
                            OutlinedButton(
                                onClick = { onDeleteChannel(ch) },
                                enabled = "channel_delete_${ch.channelId}" !in busyOps,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.error)
                            ) {
                                Text("حذف")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminVerificationAndAppearanceSection(
    busyOps: Set<String> = emptySet(),
    users: List<WesalUser>,
    logs: List<VerificationAuditLog>,
    globalFrameEnabled: Boolean,
    globalAppearance: GlobalAppearanceConfig,
    canVerifyUsers: Boolean = true,
    canManageFrames: Boolean = true,
    canManageAuditLogs: Boolean,
    onToggleGlobalFrame: (Boolean) -> Unit,
    onSaveGlobalCustomerDefaults: (Boolean, String, String, String) -> Unit,
    onOpenVerifyDialog: (WesalUser) -> Unit,
    onOpenFrameNeonDialog: (WesalUser) -> Unit,
    onInspect: (WesalUser) -> Unit,
    onDeleteAuditLog: (String) -> Unit,
    onClearAuditLogs: () -> Unit
) {
    val palette = WesalColors.current
    val localCtx = androidx.compose.ui.platform.LocalContext.current
    var subTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var showGlobalPicker by remember { mutableStateOf(false) }
    val subTabs = listOf("الحسابات", "الحسابات الموثقة", "الحسابات المنتهي توثيقها", "سجل تدقيق التوثيق")
    val now = System.currentTimeMillis()

    val currentDefaultFrameId = globalAppearance.defaultCustomerFrameId.ifBlank { WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID }
    val currentDefaultFrame = WesalFrameLibrary.resolve(currentDefaultFrameId, 0L)

    if (showGlobalPicker) {
        var draftEnabled by remember(globalFrameEnabled) { mutableStateOf(globalFrameEnabled) }
        var draftFrameId by remember(currentDefaultFrameId) { mutableStateOf(currentDefaultFrameId) }
        var draftIconId by remember(globalAppearance.defaultCustomerIconId) { mutableStateOf(globalAppearance.defaultCustomerIconId) }
        var draftIconUrl by remember(globalAppearance.defaultCustomerIconUrl) { mutableStateOf(globalAppearance.defaultCustomerIconUrl) }
        val allFrames = WesalFrameLibrary.pickerItems("CUSTOMER", false) + WesalFrameLibrary.pickerItems("ADMIN", false) + WesalFrameLibrary.pickerItems("ANIMATED", false)
        val allIcons = WesalFrameLibrary.pickerItems("ICON", false)
        val pickerCtx = androidx.compose.ui.platform.LocalContext.current

        AlertDialog(
            onDismissRequest = { showGlobalPicker = false },
            containerColor = palette.surface,
            title = { Text("تعيين الإطار والأيقونة الجماعية للعملاء", color = palette.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("تفعيل الإطار التلقائي لجميع العملاء", color = palette.textPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = draftEnabled, onCheckedChange = { draftEnabled = it })
                    }
                    Text("اختر أي إطار من المكتبة:", color = palette.turquoise, style = MaterialTheme.typography.labelLarge)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.height(170.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        gridItems(allFrames, key = { it.id }) { item ->
                            val isSel = draftFrameId == item.id
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.surfaceElevated)
                                    .border(
                                        width = if (isSel) 2.dp else 1.dp,
                                        color = if (isSel) palette.emerald else palette.border,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { draftFrameId = item.id },
                                contentAlignment = Alignment.Center
                            ) {
                                // Picker speed (owner P0): 192px Coil decode, never full-size.
                                val thumbRequest = remember(item.id, item.imageUrl, item.resId) {
                                    ImageRequest.Builder(pickerCtx)
                                        .data(if (item.imageUrl.isNotBlank()) wesalImageModel(item.imageUrl) else item.resId)
                                        .size(192)
                                        .crossfade(false)
                                        .build()
                                }
                                AsyncImage(
                                    model = thumbRequest,
                                    contentDescription = item.title,
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                    contentScale = ContentScale.Fit,
                                    imageLoader = if (FrameAnimationPolicy.effectiveAnimated(item.animated, WesalFrameLibrary.framesAnimationEnabled)) {
                                        LocalImageLoader.current
                                    } else {
                                        WesalStillImageLoader.get(pickerCtx)
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("أيقونة افتراضية جماعية (اختياري):", color = palette.turquoise, style = MaterialTheme.typography.labelLarge)
                        if (draftIconId.isNotBlank()) {
                            TextButton(onClick = {
                                draftIconId = ""
                                draftIconUrl = ""
                            }) { Text("بدون أيقونة") }
                        }
                    }
                    if (allIcons.isEmpty()) {
                        Text("لا توجد أيقونات مرفوعة في المكتبة بعد", color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.height(120.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            gridItems(allIcons, key = { it.id }) { item ->
                                val isSel = draftIconId == item.id
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.surfaceElevated)
                                        .border(
                                            width = if (isSel) 2.dp else 1.dp,
                                            color = if (isSel) palette.emerald else palette.border,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            draftIconId = item.id
                                            draftIconUrl = item.imageUrl
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Picker speed (owner P0): 192px Coil decode, never full-size.
                                    val thumbRequest = remember(item.id, item.imageUrl, item.resId) {
                                        ImageRequest.Builder(pickerCtx)
                                            .data(if (item.imageUrl.isNotBlank()) wesalImageModel(item.imageUrl) else item.resId)
                                            .size(192)
                                            .crossfade(false)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = thumbRequest,
                                        contentDescription = item.title,
                                        modifier = Modifier.fillMaxSize().padding(3.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveGlobalCustomerDefaults(draftEnabled, draftFrameId, draftIconId, draftIconUrl)
                        showGlobalPicker = false
                    },
                    enabled = "global_defaults_save" !in busyOps
                ) { Text("تطبيق على جميع العملاء") }
            },
            dismissButton = {
                TextButton(onClick = { showGlobalPicker = false }) { Text("إلغاء") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Global Customer Frame Banner ("إطار لكل العملاء البرنامج")
        Card(
            colors = CardDefaults.cardColors(containerColor = palette.surfaceElevated),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable { showGlobalPicker = true }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(localCtx)
                        .data(
                            if (currentDefaultFrame?.imageUrl?.isNotBlank() == true) {
                                wesalImageModel(currentDefaultFrame.imageUrl)
                            } else {
                                currentDefaultFrame?.resId ?: WesalFrameCatalog.defaultCustomerFrame.resId
                            }
                        )
                        .size(128)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                    contentScale = ContentScale.Fit
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("إطار لكل العملاء البرنامج (تطبيق جماعي)", color = palette.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (globalAppearance.defaultCustomerIconId.isNotBlank()) {
                            "اضغط لاختيار أي إطار أو أيقونة لكل عملاء WESAL دفعة واحدة"
                        } else {
                            "اضغط لتخصيص أي إطار/أيقونة ومنحه لكل عملاء WESAL"
                        },
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Switch(
                    checked = globalFrameEnabled,
                    onCheckedChange = onToggleGlobalFrame,
                    enabled = "global_frame_toggle" !in busyOps
                )
            }
        }

        // Master frame-animation switch (owner request): writes `framesAnimationEnabled`
        // in system_config/global_appearance via WesalFrameLibrary; clients observe it and
        // render every frame statically while it is off.
        val animScope = rememberCoroutineScope()
        Card(
            colors = CardDefaults.cardColors(containerColor = palette.surfaceElevated),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("حركة الإطارات المتحركة", color = palette.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "مفتاح رئيسي لكل المستخدمين: إيقافه يجعل كل الإطارات ثابتة بدون حركة",
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Switch(
                    checked = WesalFrameLibrary.framesAnimationEnabled,
                    onCheckedChange = { enabled ->
                        animScope.launch {
                            WesalFrameLibrary.setFramesAnimationEnabled(enabled)
                        }
                    }
                )
            }
        }

        // Bulk WhatsApp invites kill-switch (owner request): writes `bulkWhatsAppInvitesEnabled`
        // in system_config/features via WesalFeatureFlags; every client's contacts screen
        // observes it live and hides the bulk-invite send action while it is off (contact
        // selection for delete stays available). Default is ON.
        Card(
            colors = CardDefaults.cardColors(containerColor = palette.surfaceElevated),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("الدعوات الجماعية عبر واتساب", color = palette.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "مفتاح رئيسي لكل المستخدمين: إيقافه يخفي إرسال الدعوات الجماعية (تحديد الكل) من شاشة جهات الاتصال عند كل العملاء",
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Switch(
                    checked = WesalFeatureFlags.bulkWhatsAppInvitesEnabled,
                    onCheckedChange = { enabled ->
                        animScope.launch {
                            WesalFeatureFlags.setBulkWhatsAppInvitesEnabled(enabled)
                        }
                    }
                )
            }
        }

        ScrollableTabRow(
            selectedTabIndex = subTab,
            containerColor = palette.surface,
            contentColor = palette.emerald,
            edgePadding = 8.dp
        ) {
            subTabs.forEachIndexed { idx, label ->
                Tab(
                    selected = subTab == idx,
                    onClick = { subTab = idx },
                    text = { Text(label, maxLines = 1) }
                )
            }
        }

        if (subTab == 3) {
            AdminAuditLogsListSection(
                busyOps = busyOps,
                clearOpKey = "logs_clear_${Constants.COLLECTION_VERIFICATION_LOGS}",
                title = "سجل تدقيق التوثيق والإطارات (${logs.size})",
                logs = logs,
                canDeleteLogs = canManageAuditLogs,
                onDeleteLog = onDeleteAuditLog,
                onClearLogs = onClearAuditLogs
            )
        } else {
            AdminInlineSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it }
            )
            val filtered = remember(users, subTab, searchQuery, now) {
                val base = when (subTab) {
                    1 -> users.filter { it.isVerified }
                    2 -> users.filter {
                        (!it.isVerified && (it.verificationExpiresAt > 0L || it.verifiedAt > 0L)) ||
                            (it.verificationExpiresAt > 0L && it.verificationExpiresAt <= now)
                    }
                    else -> users.sortedByDescending { it.isVerified }
                }
                base.filter { matchesAdminQuery(it, searchQuery) }
            }

            LazyColumn(
                // Round 10 (item 10): weight(1f), not fillMaxSize() — inside the
                // section Column (toggle cards + tab chips + search bar above),
                // weight is the correct "remaining space" idiom; the list now
                // reliably scrolls to the last account under the fixed header.
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.uid }) { user ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onInspect(user) }
                    ) {
                        // Round 10 (item 10, owner screenshot 168771): the name
                        // used to share ONE row with a buttons column holding
                        // «إطار/أيقونة/نيون» — the name column was squeezed to
                        // a sliver and "WESALadmin" rendered as "WES...".
                        // Layout now: identity row (avatar + name/ID/badge
                        // lines) takes the FULL card width; the two actions
                        // sit on their own full-width row beneath it, each
                        // weight(1f) with softWrap off — nothing is squeezed
                        // into a small box and no label truncates.
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                WesalAvatar(
                                    imageUrl = user.photoUrl,
                                    name = user.displayName,
                                    size = 44.dp,
                                    frameId = user.frameId,
                                    frameExpiresAt = user.frameExpiresAt,
                                    isCutout = user.photoRemovedBg,
                                    isUserFramed = user.photoUserFramed,
                                    iconUrl = WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl),
                                    neonColor = if (user.neonEnabled) user.neonColor else "",
                                    neonGlowIntensity = user.neonGlowIntensity
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    WesalNameWithBadge(
                                        name = user.displayName.ifBlank { "@${user.publicId}" },
                                        isVerified = user.isVerified,
                                        verificationType = user.verificationType,
                                        verificationGlowColor = user.verificationGlowColor,
                                        neonColor = user.neonColor,
                                        neonGlowIntensity = user.neonGlowIntensity,
                                        neonEnabled = user.neonEnabled,
                                        fontWeight = FontWeight.Bold,
                                        iconUrl = WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl)
                                    )
                                    Text("ID: ${user.numericId} • @${user.publicId}", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                                    if (user.isVerified) {
                                        Text(
                                            "شارة ${user.verificationType} • ${if (user.verificationExpiresAt == 0L) "دائم" else "ينتهي: ${formatAdminTime(user.verificationExpiresAt)}"}",
                                            color = palette.verifiedBlue,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                            if (canVerifyUsers || canManageFrames) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (canVerifyUsers) {
                                        FilledTonalButton(
                                            onClick = { onOpenVerifyDialog(user) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(if (user.isVerified) "تعديل/سحب" else "توثيق", maxLines = 1, softWrap = false)
                                        }
                                    }
                                    if (canManageFrames) {
                                        OutlinedButton(
                                            onClick = { onOpenFrameNeonDialog(user) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("إطار/أيقونة/نيون", maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminWesalTeamSection(
    busyOps: Set<String> = emptySet(),
    channel: WesalChannel?,
    onOpenChannel: () -> Unit,
    onPublishPost: (String) -> Unit,
    canManageOfficialIdentity: Boolean = false,
    officialPhotoUrl: String = "",
    officialIdentityBusy: Boolean = false,
    onPickOfficialPhoto: (android.content.Context, android.net.Uri) -> Unit = { _, _ -> },
    officialUser: WesalUser? = null,
    canEditOfficialAccount: Boolean = false,
    canVerifyOfficialAccount: Boolean = false,
    onOpenOfficialVerify: (WesalUser) -> Unit = {},
    onOpenOfficialFrameNeon: (WesalUser) -> Unit = {}
) {
    val palette = WesalColors.current
    var postText by remember { mutableStateOf("") }
    // §9.6: image picker for the official identity card (same PickVisualMedia
    // pattern as AdminFrameAndNeonDialog). Declared unconditionally; the card
    // that launches it is permission-gated below.
    val teamCtx = androidx.compose.ui.platform.LocalContext.current
    val officialPhotoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPickOfficialPhoto(teamCtx, uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WesalNameWithBadge(
                    name = channel?.title ?: "فريق وصال | WESAL Team",
                    isVerified = true,
                    verificationType = "BLUE",
                    fontWeight = FontWeight.Bold
                )
                Text(channel?.description ?: "القناة الرسمية لأخبار وتحديثات WESAL Messenger", color = palette.textSecondary, style = MaterialTheme.typography.bodySmall)
                Text("المشتركين: ${channel?.subscribersCount ?: 1} • المالك الحصري: 10001", color = palette.emerald, style = MaterialTheme.typography.labelSmall)
                Button(onClick = onOpenChannel, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("فتح قناة فريق وصال الرسمية")
                }
            }
        }

        // Round 5 (owner requirement): the official account (10001) is a NATURAL
        // account — its frame (incl. the animated ones), verification badge,
        // nameplate colour/neon and icon are edited with the SAME per-user
        // dialogs as every other account, right from this section. Whatever is
        // set here renders identically in the chats list, the welcome
        // conversation header, the team channel and the account's profile page.
        if (officialUser != null && (canEditOfficialAccount || canVerifyOfficialAccount)) {
            val ou = officialUser
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("الحساب الرسمي (@${ou.publicId.ifBlank { "wesaladmin" }} — 10001)", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WesalAvatar(
                            imageUrl = ou.photoUrl,
                            name = ou.displayName,
                            size = 56.dp,
                            frameId = ou.frameId,
                            frameExpiresAt = ou.frameExpiresAt,
                            isCutout = ou.photoRemovedBg,
                            isUserFramed = ou.photoUserFramed,
                            iconUrl = ou.iconUrl,
                            neonColor = if (ou.neonEnabled) ou.neonColor else "",
                            neonGlowIntensity = ou.neonGlowIntensity
                        )
                        WesalNameWithBadge(
                            name = ou.displayName.ifBlank { "WESAL Messenger" },
                            isVerified = ou.isVerified,
                            verificationType = ou.verificationType,
                            neonColor = ou.neonColor,
                            neonGlowIntensity = ou.neonGlowIntensity,
                            neonEnabled = ou.neonEnabled,
                            fontWeight = FontWeight.Bold,
                            iconUrl = WesalFrameLibrary.resolveIconUrl(ou.iconId, ou.iconUrl)
                        )
                    }
                    Text(
                        "عدّل الحساب الرسمي زي أي حساب عادي: الإطار (بما فيه الإطارات المتحركة والملكية)، شارة التوثيق، لون الاسم والنيون، والأيقونة — والتعديل يظهر بنفس الشكل في كل مكان. صورة الحساب تُضبط من محرر «الهوية الرسمية» بالأسفل وتتزامن تلقائياً مع صفحة الحساب.",
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (canVerifyOfficialAccount) {
                        Button(onClick = { onOpenOfficialVerify(ou) }, modifier = Modifier.fillMaxWidth()) {
                            Text("التوثيق والشارة")
                        }
                    }
                    if (canEditOfficialAccount) {
                        Button(onClick = { onOpenOfficialFrameNeon(ou) }, modifier = Modifier.fillMaxWidth()) {
                            Text("الإطار والنيون ولون الاسم والأيقونة")
                        }
                    }
                }
            }
        } else if (officialUser == null) {
            Text(
                "الحساب الرسمي (10001) غير مُحمّل بعد: لو لم يظهر هنا فحسابه غير مسجّل في قاعدة البيانات — سجّل دخوله مرة واحدة من التطبيق ليُنشأ حسابه ويظهر للتحرير مثل أي حساب.",
                color = palette.textMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }

        // §9.6 official identity editor: the official/main chat's photo + the
        // team channel's avatar. Permission-gated by the caller on the same
        // frame/neon management permission used across this screen.
        if (canManageOfficialIdentity) {
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("الهوية الرسمية — صورة المحادثة والقناة الرسمية", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "هذه الصورة تظهر كصورة المحادثة الرسمية وقناة فريق وصال لدى جميع المستخدمين.",
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (officialPhotoUrl.isNotBlank()) {
                        AsyncImage(
                            model = wesalImageModel(officialPhotoUrl),
                            contentDescription = null,
                            modifier = Modifier.size(76.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("لا توجد صورة رسمية معيّنة بعد", color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = {
                            officialPhotoPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        enabled = !officialIdentityBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (officialIdentityBusy) "جارٍ رفع الصورة…" else "اختيار صورة رسمية جديدة")
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("نشر منشور سريع في قناة فريق وصال", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = postText,
                    onValueChange = { postText = it },
                    label = { Text("نص المنشور الرسمي") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onPublishPost(postText)
                        postText = ""
                    },
                    enabled = postText.isNotBlank() && "team_post" !in busyOps,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("نشر في القناة")
                }
            }
        }
    }
}

@Composable
private fun AdminWelcomeConfigSection(
    busyOps: Set<String> = emptySet(),
    config: SystemWelcomeConfig,
    onSave: (Boolean, String) -> Unit,
    onSendTest: () -> Unit
) {
    val palette = WesalColors.current
    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var template by remember(config) { mutableStateOf(config.messageTemplate) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("تفعيل رسالة الترحيب التلقائية للمستخدمين الجدد", color = palette.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    OutlinedTextField(
                        value = template,
                        onValueChange = { template = it },
                        label = { Text("نص رسالة الترحيب (استخدم [اسم المستخدم])") },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { template = "$template [اسم المستخدم]" },
                            label = { Text("+ [اسم المستخدم]") }
                        )
                        AssistChip(
                            onClick = { template = SystemWelcomeConfig.DEFAULT_OFFICIAL_WELCOME_TEMPLATE },
                            label = { Text("استعادة النص الافتراضي") }
                        )
                    }
                    // Round 9 (item 6): «إرسال رسالة تجريبية لنفسي» cannot fit
                    // a half-width weighted button on one line — stack the
                    // two actions full-width instead of squeezing them.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onSave(enabled, template) },
                            enabled = "welcome_save" !in busyOps,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("حفظ التعديل", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = onSendTest,
                            enabled = "welcome_test" !in busyOps,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("إرسال رسالة تجريبية لنفسي", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        if (config.previousVersions.isNotEmpty()) {
            item {
                Text("النسخ السابقة (اضغط للاستعادة)", color = palette.textSecondary, style = MaterialTheme.typography.labelLarge)
            }
            items(config.previousVersions) { prev ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = palette.surface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { template = prev }
                ) {
                    Text(
                        text = prev,
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminAnnouncementsSection(
    busyOps: Set<String> = emptySet(),
    announcements: List<OfficialAnnouncement>,
    onPublish: (String, String) -> Unit
) {
    val palette = WesalColors.current
    var title by remember { mutableStateOf("WESAL Messenger") }
    var body by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("إرسال إعلان رسمي لجميع المستخدمين (محادثة WESAL Messenger)", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("عنوان الإعلان") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("نص الإعلان الرسمي") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onPublish(title, body)
                            body = ""
                        },
                        enabled = body.isNotBlank() && "announcement_publish" !in busyOps,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Campaign, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("نشر الإعلان الرسمي للجميع")
                    }
                }
            }
        }

        item {
            Text("أرشيف الإعلانات الرسمية (${announcements.size})", color = palette.textSecondary, style = MaterialTheme.typography.labelLarge)
        }

        items(announcements, key = { it.announcementId }) { ann ->
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(14.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ann.title, color = palette.emerald, fontWeight = FontWeight.Bold)
                    Text(ann.body, color = palette.textPrimary, style = MaterialTheme.typography.bodySmall)
                    Text(formatAdminTime(ann.createdAt), color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AdminAuditLogsListSection(
    busyOps: Set<String> = emptySet(),
    clearOpKey: String = "",
    title: String,
    logs: List<VerificationAuditLog>,
    canDeleteLogs: Boolean = false,
    onDeleteLog: ((String) -> Unit)? = null,
    onClearLogs: (() -> Unit)? = null
) {
    val palette = WesalColors.current
    var logSearch by remember { mutableStateOf("") }
    var confirmClearAll by remember { mutableStateOf(false) }

    val filteredLogs = remember(logs, logSearch) {
        val q = logSearch.trim()
        if (q.isEmpty()) {
            logs
        } else {
            val lower = q.lowercase()
            val normDigits = WesalIdUtils.normalizeDigits(q)
            logs.filter { log ->
                log.targetName.lowercase().contains(lower) ||
                    log.targetNumericId.contains(normDigits) ||
                    log.targetPublicId.lowercase().contains(lower.removePrefix("@")) ||
                    log.targetUid.lowercase().contains(lower) ||
                    log.action.lowercase().contains(lower) ||
                    log.details.lowercase().contains(lower) ||
                    log.adminEmail.lowercase().contains(lower)
            }
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            containerColor = palette.surface,
            title = { Text("مسح السجل بالكامل؟", color = palette.error, fontWeight = FontWeight.Bold) },
            text = { Text("سيتم حذف جميع قيود هذا السجل نهائياً من قاعدة البيانات.", color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearAll = false
                        onClearLogs?.invoke()
                    },
                    enabled = clearOpKey.isBlank() || clearOpKey !in busyOps,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                ) { Text("تأكيد المسح") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("إلغاء") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AdminInlineSearchBar(
            query = logSearch,
            onQueryChange = { logSearch = it },
            placeholder = "ابحث في السجل بالاسم أو ID أو الإجراء أو المشرف"
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(title, color = palette.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    if (canDeleteLogs && onClearLogs != null && logs.isNotEmpty()) {
                        TextButton(
                            onClick = { confirmClearAll = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = palette.error)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("مسح السجل")
                        }
                    }
                }
            }
            items(filteredLogs, key = { it.operationId.ifBlank { it.logId } }) { log ->
                val docId = log.logId.ifBlank { log.operationId }
                Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = log.targetName.ifBlank { log.targetNumericId.ifBlank { log.targetUid.ifBlank { "النظام" } } },
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(log.action, color = palette.emerald, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                if (canDeleteLogs && onDeleteLog != null && docId.isNotBlank()) {
                                    IconButton(
                                        onClick = { onDeleteLog(docId) },
                                        enabled = "log_delete_$docId" !in busyOps,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "حذف السجل",
                                            tint = palette.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (log.details.isNotBlank()) {
                            Text(log.details, color = palette.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        if (log.note.isNotBlank()) {
                            Text("ملاحظة: ${log.note}", color = palette.turquoise, style = MaterialTheme.typography.labelSmall)
                        }
                        Text("${log.adminEmail} • ${formatAdminTime(log.timestamp)}", color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminSettingsSection(
    busyOps: Set<String> = emptySet(),
    globalFrameEnabled: Boolean,
    onToggleGlobalFrame: (Boolean) -> Unit,
    appUpdateConfig: AppUpdateConfig,
    onSaveAppUpdateConfig: (AppUpdateConfig) -> Unit,
    onRefreshAll: () -> Unit
) {
    val palette = WesalColors.current
    // Forced-update draft state, re-seeded whenever the saved config changes.
    var updEnabled by remember(appUpdateConfig) { mutableStateOf(appUpdateConfig.enabled) }
    var updMandatory by remember(appUpdateConfig) { mutableStateOf(appUpdateConfig.mandatory) }
    var updLatest by remember(appUpdateConfig) { mutableStateOf(appUpdateConfig.latestVersionCode.toString()) }
    var updMinSupported by remember(appUpdateConfig) { mutableStateOf(appUpdateConfig.minSupportedVersionCode.toString()) }
    var updApkUrl by remember(appUpdateConfig) { mutableStateOf(appUpdateConfig.apkUrl) }
    var updMessageAr by remember(appUpdateConfig) { mutableStateOf(appUpdateConfig.messageAr) }
    var updMessageEn by remember(appUpdateConfig) { mutableStateOf(appUpdateConfig.messageEn) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("إعدادات الأمان والصلاحيات", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                    AdminInfoLine("حساب المالك الأعلى الوحيد", "WESAL ID: ${Constants.OWNER_ADMIN_NUMERIC_ID}")
                    AdminInfoLine("البريد المعتمد", Constants.SUPER_ADMIN_EMAIL)
                    AdminInfoLine("رابط الدعوة الرسمي", Constants.APK_DOWNLOAD_URL)
                    AdminInfoLine("قاعدة بيانات Firestore", "ai-studio-livo-cd975dac-35e6-4dbb-864e-829e3b638424")
                    AdminInfoLine("حافظة Storage", "idyllic-axon-mw1rw.firebasestorage.app")
                    AdminInfoLine("TTL حالة الاتصال", "${Constants.PRESENCE_ONLINE_TTL_MS / 1000} ثانية")
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("الإطار الافتراضي التلقائي للعملاء الجدد", color = palette.textPrimary, modifier = Modifier.weight(1f))
                    Switch(
                        checked = globalFrameEnabled,
                        onCheckedChange = onToggleGlobalFrame,
                        enabled = "global_frame_toggle" !in busyOps
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تحديث التطبيق", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                    AdminInfoLine("إصدار هذا الجهاز (versionCode)", BuildConfig.VERSION_CODE.toString())
                    AdminInfoLine("اسم الإصدار", BuildConfig.VERSION_NAME)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("تفعيل مطالبة التحديث", color = palette.textPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = updEnabled, onCheckedChange = { updEnabled = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("تحديث إجباري (لا يمكن التخطي)", color = palette.textPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = updMandatory, onCheckedChange = { updMandatory = it })
                    }
                    OutlinedTextField(
                        value = updLatest,
                        onValueChange = { updLatest = it.filter { ch -> ch.isDigit() } },
                        label = { Text("أحدث إصدار متاح (latestVersionCode)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = updMinSupported,
                        onValueChange = { updMinSupported = it.filter { ch -> ch.isDigit() } },
                        label = { Text("أدنى إصدار مدعوم (minSupportedVersionCode)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = updApkUrl,
                        onValueChange = { updApkUrl = it },
                        label = { Text("رابط تحميل الـ APK (فارغ = الرابط الرسمي الافتراضي)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = updMessageAr,
                        onValueChange = { updMessageAr = it },
                        label = { Text("رسالة التحديث (عربي)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = updMessageEn,
                        onValueChange = { updMessageEn = it },
                        label = { Text("Update message (English)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onSaveAppUpdateConfig(
                                appUpdateConfig.copy(
                                    enabled = updEnabled,
                                    mandatory = updMandatory,
                                    latestVersionCode = updLatest.toIntOrNull() ?: 0,
                                    minSupportedVersionCode = updMinSupported.toIntOrNull() ?: 0,
                                    apkUrl = updApkUrl.trim(),
                                    messageAr = updMessageAr.trim(),
                                    messageEn = updMessageEn.trim()
                                )
                            )
                        },
                        enabled = "app_update_save" !in busyOps,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("حفظ إعدادات التحديث")
                    }
                    Text(
                        "عند التفعيل: أي جهاز بإصدار أقدم من «أحدث إصدار» يرى نافذة تحديث. «إجباري» أو إصدار أقدم من «أدنى مدعوم» = لا يمكن إغلاق النافذة. الأجهزة المحدّثة لا ترى شيئاً.",
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        item {
            Button(
                onClick = onRefreshAll,
                enabled = "refresh" !in busyOps,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("إعادة مزامنة كافة بيانات لوحة الإدارة")
            }
        }
    }
}

@Composable
private fun AdminOnlineUsersSection(
    onlineUsers: List<WesalUser>,
    now: Long,
    onInspect: (WesalUser) -> Unit,
    onOpenChat: (WesalUser) -> Unit
) {
    val palette = WesalColors.current
    var query by remember { mutableStateOf("") }
    val filteredOnline = remember(onlineUsers, query) {
        onlineUsers.filter { matchesAdminQuery(it, query) }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        AdminInlineSearchBar(query = query, onQueryChange = { query = it })
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("المتصلون الآن (${filteredOnline.size})", color = palette.emerald, fontWeight = FontWeight.Bold)
            }
            items(filteredOnline, key = { it.uid }) { user ->
                val hb = maxOf(user.lastHeartbeatAt, user.lastSeen)
                val secAgo = if (hb > 0L) ((now - hb) / 1000L).coerceAtLeast(0L) else 0L
                Card(
                    colors = CardDefaults.cardColors(containerColor = palette.surface),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onInspect(user) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        WesalAvatar(imageUrl = user.photoUrl, name = user.displayName, size = 42.dp, isOnline = true)
                        Column(modifier = Modifier.weight(1f)) {
                            WesalNameWithBadge(
                                name = user.displayName.ifBlank { "@${user.publicId}" },
                                isVerified = user.isVerified,
                                verificationType = user.verificationType,
                                verificationGlowColor = user.verificationGlowColor,
                                neonColor = user.neonColor,
                                neonGlowIntensity = user.neonGlowIntensity,
                                neonEnabled = user.neonEnabled,
                                iconUrl = WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl)
                            )
                            Text(
                                text = "ID: ${user.numericId} • ${user.lastDeviceName.ifBlank { "Android" }} (${user.lastAppVersion.ifBlank { "1.0" }})",
                                color = palette.textSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text("آخر نبض اتصال: منذ $secAgo ث • جلسات: ${user.activeSessionsCount.coerceAtLeast(1)}", color = palette.emerald, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onOpenChat(user) }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = palette.turquoise)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminChatActivityMetadataSection(
    users: List<WesalUser>,
    onInspect: (WesalUser) -> Unit
) {
    val palette = WesalColors.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Surface(color = palette.turquoise.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "خصوصية البيانات: يعرض هذا القسم بيانات وصفية تشغيلية فقط (نوع النشاط، معرّف المحادثة، عدد المشاركين، التوقيت) دون عرض أي نصوص رسائل.",
                    color = palette.turquoise,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
        items(users, key = { it.uid }) { user ->
            Card(
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().clickable { onInspect(user) }
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WesalNameWithBadge(
                        name = "${user.displayName.ifBlank { "@${user.publicId}" }} (ID: ${user.numericId})",
                        isVerified = user.isVerified,
                        verificationType = user.verificationType,
                        neonColor = user.neonColor,
                        neonGlowIntensity = user.neonGlowIntensity,
                        neonEnabled = user.neonEnabled,
                        iconUrl = WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl)
                    )
                    Text(
                        text = "نوع النشاط: ${user.lastActivityType.ifBlank { "NONE" }} • المعرف: ${user.lastActivityChatId.ifBlank { "—" }} • المشاركون: ${user.lastActivityParticipantsCount}",
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("التوقيت: ${formatAdminTime(user.lastActivityAt)}", color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AdminInfoLine(label: String, value: String) {
    val palette = WesalColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = palette.textMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
        Text(value, color = palette.textPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------
// Dialogs: Verification / Frames & Neon / Ban / Report Review
// ---------------------------------------------------------------------------

@Composable
private fun AdminVerificationDialog(
    user: WesalUser,
    busyOps: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onApply: (Boolean, VerificationSealType, NeonColorPreset, Long, String, String) -> Unit
) {
    val palette = WesalColors.current
    var selectedSeal by remember(user.uid) { mutableStateOf(VerificationSealType.fromKey(user.verificationType)) }
    var selectedGlow by remember(user.uid) { mutableStateOf(NeonColorPreset.fromKey(user.verificationGlowColor)) }
    var selectedDuration by remember(user.uid) { mutableStateOf(VerificationDurationOption.PERMANENT) }
    var customCountText by remember { mutableStateOf("3") }
    var note by remember(user.uid) { mutableStateOf(user.verificationNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("توثيق الحساب (الشارات والتوهج والمدد)", color = palette.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(user.displayName.ifBlank { "@${user.publicId}" }, color = palette.textPrimary, fontWeight = FontWeight.SemiBold)
                    WesalVerifiedBadge(
                        size = 20.dp,
                        verificationType = selectedSeal.key,
                        verificationGlowColor = selectedGlow.key
                    )
                }

                Text("اختر نوع الشارة:", color = palette.textSecondary, style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    VerificationSealType.entries.forEach { seal ->
                        FilterChip(
                            selected = selectedSeal == seal,
                            onClick = { selectedSeal = seal },
                            leadingIcon = {
                                WesalVerifiedBadge(
                                    size = 16.dp,
                                    verificationType = seal.key,
                                    verificationGlowColor = selectedGlow.key
                                )
                            },
                            label = { Text(seal.labelAr, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("لون توهج الشارة (30 لون):", color = palette.textSecondary, style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    NeonColorPreset.entries.forEach { preset ->
                        val isSel = selectedGlow == preset
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSel) palette.surfaceElevated else palette.surface,
                            border = BorderStroke(if (isSel) 2.dp else 1.dp, if (isSel) preset.glowColor else palette.border),
                            modifier = Modifier.clickable { selectedGlow = preset }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(13.dp)
                                        .clip(CircleShape)
                                        .background(preset.glowColor)
                                        .border(1.dp, palette.border, CircleShape)
                                )
                                Text(preset.labelAr, color = palette.textPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }

                Text("مدة التوثيق:", color = palette.textSecondary, style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    VerificationDurationOption.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            pair.forEach { opt ->
                                FilterChip(
                                    selected = selectedDuration == opt,
                                    onClick = { selectedDuration = opt },
                                    label = { Text(opt.labelAr, maxLines = 1) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                if (selectedDuration == VerificationDurationOption.CUSTOM_HOURS ||
                    selectedDuration == VerificationDurationOption.CUSTOM_DAYS ||
                    selectedDuration == VerificationDurationOption.CUSTOM_MONTHS
                ) {
                    OutlinedTextField(
                        value = customCountText,
                        onValueChange = { if (it.length <= 3) customCountText = it.filter(Char::isDigit) },
                        label = {
                            Text(
                                when (selectedDuration) {
                                    VerificationDurationOption.CUSTOM_HOURS -> "عدد الساعات"
                                    VerificationDurationOption.CUSTOM_DAYS -> "عدد الأيام"
                                    else -> "عدد الشهور"
                                }
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("ملاحظة التوثيق") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val isProtected = Constants.isProtectedAdminNumericId(user.numericId)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (user.isVerified && !isProtected) {
                    OutlinedButton(
                        onClick = { onApply(false, selectedSeal, NeonColorPreset.NONE, 0L, "سحب التوثيق", note) },
                        enabled = "verify_${user.uid}" !in busyOps,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.error)
                    ) { Text("سحب") }
                }
                Button(
                    onClick = {
                        val (exp, lbl) = calculateDurationExpiresAt(selectedDuration, customCountText.toIntOrNull() ?: 1)
                        onApply(true, selectedSeal, selectedGlow, exp, lbl, note)
                    },
                    enabled = "verify_${user.uid}" !in busyOps
                ) {
                    Text(if (user.isVerified) "تحديث" else "منح الشارة")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun AdminFrameAndNeonDialog(
    user: WesalUser,
    busyOps: Set<String> = emptySet(),
    // Round 6 (item 7a): explicit opening tab, threaded from the tapped action
    // (frames actions pass 0). Previously there was NO initial-tab parameter and
    // modeTab was a bare remember — the one selection state in this dialog not
    // keyed by user — so the tab from a previous dialog session could be the
    // one shown when the admin tapped «إطار» (owner: dialog opened on Icons).
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onApplyFrame: (String, Long, String) -> Unit,
    onApplyIcon: (String, String) -> Unit,
    onApplyNeon: (NeonColorPreset, Float, Boolean) -> Unit,
    onApplyNameplateColor: (String) -> Unit
) {
    val palette = WesalColors.current
    // Keyed by BOTH the target user and the requested opening tab: a new
    // invocation always starts on the tab matching the tapped action.
    var modeTab by remember(user.uid, initialTab) { mutableStateOf(initialTab) } // 0 = Customer frames, 1 = Admin frames, 2 = Icons, 3 = Neon, 4 = Nameplate colour, 5 = Official frames, 6 = Animated frames
    var selectedFrameId by remember(user.uid) { mutableStateOf(user.frameId) }
    var selectedNameplateKey by remember(user.uid) { mutableStateOf(user.nameplateColor) }
    var selectedIconId by remember(user.uid) { mutableStateOf(user.iconId) }
    var selectedIconUrl by remember(user.uid) { mutableStateOf(WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl)) }
    var previewCutout by remember(user.uid) { mutableStateOf(user.photoRemovedBg) }
    var selectedDuration by remember { mutableStateOf(VerificationDurationOption.PERMANENT) }
    var customCount by remember { mutableStateOf("7") }
    var neonEnabled by remember(user.uid) { mutableStateOf(user.neonEnabled && NeonColorPreset.fromKey(user.neonColor) != NeonColorPreset.NONE) }
    var selectedNeon by remember(user.uid) { mutableStateOf(NeonColorPreset.fromKey(user.neonColor)) }
    var neonGlow by remember(user.uid) { mutableStateOf(user.neonGlowIntensity.coerceIn(0.15f, 1.0f)) }
    var lastNeon by remember(user.uid) { mutableStateOf(NeonColorPreset.fromKey(user.neonColor).takeIf { it != NeonColorPreset.NONE } ?: NeonColorPreset.BLUE) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var manageMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<FramePickerItem?>(null) }
    var renameTarget by remember { mutableStateOf<FramePickerItem?>(null) }
    var pickAction by remember { mutableStateOf("NEW") } // NEW | REPLACE
    var replaceId by remember { mutableStateOf("") }
    // Round 5 (owner requirement): a NEW upload no longer uploads instantly with a
    // blank title — the admin first names it and chooses animated or static in the
    // details dialog below (GIFs are intrinsically animated and always stored as such).
    var pendingUploadUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingUploadCategory by remember { mutableStateOf("CUSTOMER") }
    var pendingUploadKind by remember { mutableStateOf("FRAME") }
    var uploadName by remember { mutableStateOf("") }
    var uploadAnimated by remember { mutableStateOf(false) }
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            if (pickAction == "REPLACE") {
                scope.launch {
                    busy = true
                    val res = WesalFrameLibrary.replaceFrameImage(ctx, replaceId, uri).map { replaceId }
                    busy = false
                    statusMsg = res.fold(
                        onSuccess = { "تم الحفظ على Firebase ✓" },
                        onFailure = { "فشل الحفظ: ${it.localizedMessage ?: "خطأ غير معروف"}" }
                    )
                }
            } else {
                pendingUploadCategory = when (modeTab) {
                    1 -> "ADMIN"
                    2 -> "ICON"
                    5 -> "OFFICIAL"
                    6 -> "ANIMATED"
                    else -> "CUSTOMER"
                }
                pendingUploadKind = if (modeTab == 2) "ICON" else "FRAME"
                uploadName = ""
                uploadAnimated = pendingUploadCategory == "ANIMATED"
                pendingUploadUri = uri
            }
        }
    }
    fun runOp(block: suspend () -> Result<Unit>, ok: String) {
        scope.launch {
            busy = true
            val r = block()
            busy = false
            statusMsg = r.fold({ ok }, { "فشلت العملية: ${it.localizedMessage ?: "خطأ"}" })
        }
    }

    pendingUploadUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingUploadUri = null },
            containerColor = palette.surface,
            title = { Text(if (pendingUploadKind == "ICON") "رفع أيقونة جديدة" else "رفع إطار جديد", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = uploadName,
                        onValueChange = { uploadName = it },
                        label = { Text("الاسم") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = uploadAnimated, onCheckedChange = { uploadAnimated = it })
                        Text(if (uploadAnimated) "متحرك" else "ثابت (بدون حركة)", color = palette.textPrimary)
                    }
                    Text(
                        "ملفات GIF متحركة بطبيعتها وتُحفظ متحركة دائماً. العنصر الجديد يظهر في المكتبة فوراً ويمكن منحه لأي حساب.",
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val cat = pendingUploadCategory
                        val kind = pendingUploadKind
                        val name = uploadName
                        val anim = uploadAnimated
                        pendingUploadUri = null
                        scope.launch {
                            busy = true
                            val res = WesalFrameLibrary.uploadNewFrame(ctx, uri, name, cat, kind = kind, animated = anim)
                            busy = false
                            statusMsg = res.fold(
                                onSuccess = { id ->
                                    if (kind == "ICON") {
                                        selectedIconId = id
                                        selectedIconUrl = WesalFrameLibrary.resolveIconUrl(id)
                                    } else {
                                        selectedFrameId = id
                                    }
                                    "تم الحفظ على Firebase ✓"
                                },
                                onFailure = { "فشل الحفظ: ${it.localizedMessage ?: "خطأ غير معروف"}" }
                            )
                        }
                    }
                ) { Text("رفع وحفظ") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUploadUri = null }) { Text("إلغاء") }
            }
        )
    }

    renameTarget?.let { item ->
        var newTitle by remember(item.id) { mutableStateOf(item.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = palette.surface,
            title = { Text("تعديل الاسم", color = palette.textPrimary) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("الاسم الجديد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = newTitle.isNotBlank(),
                    onClick = {
                        runOp({ WesalFrameLibrary.rename(item.id, newTitle) }, "تم تعديل الاسم ✓")
                        renameTarget = null
                    }
                ) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("إلغاء") }
            }
        )
    }

    menuFor?.let { item ->
        val isIconItem = item.kind == "ICON" || modeTab == 2
        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = palette.surface,
            title = { Text(item.title, color = palette.textPrimary, maxLines = 1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        if (isIconItem) {
                            selectedIconId = item.id
                            selectedIconUrl = item.imageUrl
                            menuFor = null
                            onApplyIcon(item.id, item.imageUrl)
                        } else {
                            selectedFrameId = item.id
                            menuFor = null
                        }
                    }) { Text(if (isIconItem) "تعيين هذه الأيقونة للمستخدم" else "تعيين هذا الإطار للمستخدم") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        renameTarget = item
                        menuFor = null
                    }) { Text("تعديل الاسم") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        runOp(
                            { WesalFrameLibrary.setAnimated(item.id, true, "ROTATE_AURA") },
                            "تم تفعيل حركة دوران المروحة الثابت ✓"
                        )
                        menuFor = null
                    }) { Text("تفعيل دوران المروحة الثابت (مروحة دوّارة)") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        val nextStyle = if (item.animated && item.animationStyle == "PULSE_GLOW") "SHIMMER" else "PULSE_GLOW"
                        runOp(
                            { WesalFrameLibrary.setAnimated(item.id, true, nextStyle) },
                            if (nextStyle == "SHIMMER") "تم تفعيل التوهج المتلألئ ✓" else "تم تفعيل النبض المتوهج ✓"
                        )
                        menuFor = null
                    }) { Text(if (item.animated && item.animationStyle == "PULSE_GLOW") "تبديل إلى التوهج المتلألئ (SHIMMER)" else "تفعيل النبض المتوهج (PULSE_GLOW)") }
                    if (item.animated) {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            runOp(
                                { WesalFrameLibrary.setAnimated(item.id, false, item.animationStyle) },
                                "تم إيقاف حركة العنصر"
                            )
                            menuFor = null
                        }) { Text("إيقاف الحركة (ثابت بدون حركة)") }
                    }
                    if (!isIconItem) {
                        // §5.8: per-frame glow intensity + animation/glow speed.
                        var glowI by remember(item.id) { mutableStateOf(item.glowIntensity) }
                        var glowS by remember(item.id) { mutableStateOf(item.glowSpeed) }
                        Text(
                            "شدة توهج الإطار: ${(glowI * 100).toInt()}%",
                            color = palette.textPrimary,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(value = glowI, onValueChange = { glowI = it }, valueRange = 0f..1f)
                        Text(
                            "سرعة الحركة والتوهج: ${(glowS * 10).toInt() / 10f}x",
                            color = palette.textPrimary,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(value = glowS, onValueChange = { glowS = it }, valueRange = 0.5f..2f)
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            runOp(
                                { WesalFrameLibrary.setGlow(item.id, glowI, glowS) },
                                "تم حفظ شدة التوهج والسرعة ✓"
                            )
                            menuFor = null
                        }) { Text("حفظ التوهج والسرعة") }
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        runOp({ WesalFrameLibrary.setHidden(item.id, !item.hidden) }, if (item.hidden) "تم الإظهار" else "تم الإخفاء")
                        menuFor = null
                    }) { Text(if (item.hidden) "إظهار في المكتبة" else "إخفاء من المكتبة") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        pickAction = "REPLACE"; replaceId = item.id; menuFor = null
                        imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("استبدال الصورة بأخرى") }
                    if (item.builtin && item.imageUrl.isNotBlank()) {
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            runOp({ WesalFrameLibrary.restoreOriginalImage(item.id) }, "تمت استعادة الصورة الأصلية")
                            menuFor = null
                        }) { Text("استعادة الصورة الأصلية") }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            runOp(
                                { WesalFrameLibrary.setDeleted(item.id, !item.deleted) },
                                if (item.deleted) "تمت الاستعادة للمكتبة" else "تم الحذف من المكتبة (المستخدمون الحاليون يحتفظون به)"
                            )
                            menuFor = null
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.warning)
                    ) { Text(if (item.deleted) "استعادة للمكتبة" else "إزالة من المكتبة (مع بقائها لدى المستخدمين)") }
                    if (!item.builtin) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                runOp({ WesalFrameLibrary.permanentlyDelete(item.id) }, "تم الحذف النهائي من المكتبة")
                                menuFor = null
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.error)
                        ) { Text("حذف نهائي من قاعدة البيانات") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text("إغلاق") } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("الإطارات والأيقونات والنيون", color = palette.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        WesalAvatar(
                            imageUrl = user.photoUrl,
                            name = user.displayName,
                            size = 58.dp,
                            frameId = selectedFrameId,
                            frameExpiresAt = 0L,
                            isCutout = previewCutout,
                            iconUrl = selectedIconUrl,
                            neonColor = if (neonEnabled) selectedNeon.key else "",
                            neonGlowIntensity = neonGlow
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            WesalNameWithBadge(
                                name = user.displayName.ifBlank { "@${user.publicId}" },
                                isVerified = user.isVerified,
                                verificationType = user.verificationType,
                                verificationGlowColor = user.verificationGlowColor,
                                neonColor = selectedNeon.key,
                                neonGlowIntensity = neonGlow,
                                neonEnabled = neonEnabled,
                                fontWeight = FontWeight.Bold,
                                iconUrl = WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl)
                            )
                            FilterChip(
                                selected = previewCutout,
                                onClick = { previewCutout = !previewCutout },
                                label = { Text("معاينة تفريغ الخلفية (Cutout)", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    WesalRoyalNameplateBanner(
                        displayName = user.displayName.ifBlank { "@${user.publicId}" },
                        numericId = user.numericId,
                        isVerified = user.isVerified,
                        verificationType = user.verificationType,
                        verificationGlowColor = user.verificationGlowColor,
                        neonColor = selectedNeon.key,
                        neonGlowIntensity = neonGlow,
                        neonEnabled = neonEnabled
                    )
                }

                // Round 6 (item 7a): ScrollableTabRow's selectedTabIndex is a
                // POSITION in the row, but modeTab is a mode value and the row
                // order is 0,1,6,5,2,3,4 — passing modeTab directly pointed
                // the selection indicator at the wrong tab (e.g. Icons mode
                // highlighted the Animated tab). Map mode -> row position.
                val tabOrder = listOf(0, 1, 6, 5, 2, 3, 4)
                ScrollableTabRow(
                    selectedTabIndex = tabOrder.indexOf(modeTab).coerceAtLeast(0),
                    containerColor = palette.surfaceElevated,
                    edgePadding = 4.dp
                ) {
                    Tab(selected = modeTab == 0, onClick = { modeTab = 0 }, text = { Text("العملاء (${WesalFrameLibrary.pickerItems("CUSTOMER", false).size})", maxLines = 1) })
                    Tab(selected = modeTab == 1, onClick = { modeTab = 1 }, text = { Text("الإدارة (${WesalFrameLibrary.pickerItems("ADMIN", false).size})", maxLines = 1) })
                    Tab(selected = modeTab == 6, onClick = { modeTab = 6 }, text = { Text("إطارات متحركة (${WesalFrameLibrary.pickerItems("ANIMATED", false).size})", maxLines = 1) })
                    Tab(selected = modeTab == 5, onClick = { modeTab = 5 }, text = { Text("الرسمية (${WesalFrameLibrary.pickerItems("OFFICIAL", false).size})", maxLines = 1) })
                    Tab(selected = modeTab == 2, onClick = { modeTab = 2 }, text = { Text("الأيقونات (${WesalFrameLibrary.pickerItems("ICON", false).size})", maxLines = 1) })
                    Tab(selected = modeTab == 3, onClick = { modeTab = 3 }, text = { Text("النيون", maxLines = 1) })
                    Tab(selected = modeTab == 4, onClick = { modeTab = 4 }, text = { Text("لون الاسم", maxLines = 1) })
                }

                if (modeTab in 0..2 || modeTab == 5 || modeTab == 6) {
                    val categoryKey = when (modeTab) {
                        1 -> "ADMIN"
                        2 -> "ICON"
                        5 -> "OFFICIAL"
                        6 -> "ANIMATED"
                        else -> "CUSTOMER"
                    }
                    val libraryItems = WesalFrameLibrary.pickerItems(categoryKey, manageMode)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = manageMode,
                            onClick = { manageMode = !manageMode },
                            label = { Text(if (manageMode) "وضع الإدارة: مفعّل" else "إدارة المكتبة", maxLines = 1) }
                        )
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                pickAction = "NEW"
                                imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        ) { Text(if (modeTab == 2) "+ رفع أيقونة" else "+ رفع إطار", maxLines = 1) }
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    statusMsg?.let {
                        Text(it, color = if (it.startsWith("فشل")) palette.error else palette.emerald, style = MaterialTheme.typography.labelMedium)
                    }
                    if (libraryItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (modeTab == 2) "لا توجد أيقونات مرفوعة بعد — اضغط (+ رفع أيقونة)" else "لا توجد عناصر في هذه الفئة",
                                color = palette.textMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.height(210.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            gridItems(libraryItems, key = { it.id }) { item ->
                                val isSel = if (modeTab == 2) selectedIconId == item.id else selectedFrameId == item.id
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.surfaceElevated)
                                        .border(
                                            width = if (isSel) 2.dp else 1.dp,
                                            color = if (isSel) palette.emerald else palette.border,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            if (manageMode) {
                                                menuFor = item
                                            } else if (modeTab == 2) {
                                                selectedIconId = item.id
                                                selectedIconUrl = item.imageUrl
                                            } else {
                                                selectedFrameId = item.id
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val dim = if (item.hidden || item.deleted) 0.35f else 1f
                                    // Picker speed (owner P0): thumbnails are decoded at
                                    // 192px via Coil — never full-size (the royal artwork
                                    // is 1536x1536) and never via painterResource.
                                    val thumbRequest = remember(item.id, item.imageUrl, item.resId) {
                                        ImageRequest.Builder(ctx)
                                            .data(if (item.imageUrl.isNotBlank()) wesalImageModel(item.imageUrl) else item.resId)
                                            .size(192)
                                            .crossfade(false)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = thumbRequest,
                                        contentDescription = item.title,
                                        modifier = Modifier.fillMaxSize().padding(3.dp).alpha(dim),
                                        contentScale = ContentScale.Fit,
                                        // Animation OFF (per-frame or master) must show the
                                        // frame STILL — the still loader has no GIF decoder.
                                        imageLoader = if (FrameAnimationPolicy.effectiveAnimated(item.animated, WesalFrameLibrary.framesAnimationEnabled)) {
                                            LocalImageLoader.current
                                        } else {
                                            WesalStillImageLoader.get(ctx)
                                        }
                                    )
                                    if (item.deleted) {
                                        Text("محذوف", color = palette.error, style = MaterialTheme.typography.labelSmall)
                                    } else if (item.hidden) {
                                        Text("مخفي", color = palette.warning, style = MaterialTheme.typography.labelSmall)
                                    } else if (item.animated) {
                                        Surface(
                                            color = palette.emerald.copy(alpha = 0.85f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                                        ) {
                                            Text(
                                                "متحرك",
                                                color = Color.Black,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (modeTab == 0 || modeTab == 1 || modeTab == 5 || modeTab == 6) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            listOf(
                                VerificationDurationOption.PERMANENT,
                                VerificationDurationOption.DAY_1,
                                VerificationDurationOption.CUSTOM_DAYS,
                                VerificationDurationOption.MONTH_1
                            ).forEach { opt ->
                                FilterChip(
                                    selected = selectedDuration == opt,
                                    onClick = { selectedDuration = opt },
                                    label = { Text(opt.labelAr, maxLines = 1) }
                                )
                            }
                        }
                        if (selectedDuration == VerificationDurationOption.CUSTOM_DAYS) {
                            OutlinedTextField(
                                value = customCount,
                                onValueChange = { customCount = it.filter(Char::isDigit).take(3) },
                                label = { Text("عدد الأيام") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else if (modeTab == 4) {
                    // Nameplate colour grant (owner rule: admin panel only, 10001/10002 or a
                    // delegated frames/appearance admin). Regular users can no longer pick
                    // this themselves in ProfileScreen.
                    Text(
                        "اختر لون لوحة الاسم لهذا الحساب:",
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        WesalNameplateColorPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = selectedNameplateKey.equals(preset.key, ignoreCase = true),
                                onClick = { selectedNameplateKey = preset.key },
                                label = { Text(preset.labelAr, maxLines = 1) },
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(Color(preset.midHex))
                                    )
                                }
                            )
                        }
                    }
                    val previewPreset = WesalNameplateColorPreset.fromKey(selectedNameplateKey)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(previewPreset.edgeHex),
                                        Color(previewPreset.centerHex),
                                        Color(previewPreset.midHex)
                                    )
                                )
                            )
                            .border(1.dp, Color(previewPreset.borderAccentHex), RoundedCornerShape(14.dp))
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            user.displayName.ifBlank { user.publicId },
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Switch(
                            checked = neonEnabled,
                            onCheckedChange = { on ->
                                neonEnabled = on
                                if (on && selectedNeon == NeonColorPreset.NONE) {
                                    selectedNeon = lastNeon
                                }
                            }
                        )
                        Text(
                            if (neonEnabled) "الإضاءة النيون: مفعّلة" else "الإضاءة النيون: متوقفة",
                            color = palette.textPrimary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Text("اختر لون الإضاءة:", color = palette.textSecondary, style = MaterialTheme.typography.labelMedium)
                    Column(
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NeonColorPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = selectedNeon == preset,
                                onClick = {
                                    selectedNeon = preset
                                    if (preset == NeonColorPreset.NONE) {
                                        neonEnabled = false
                                    } else {
                                        lastNeon = preset
                                        neonEnabled = true
                                    }
                                },
                                label = { Text(preset.labelAr) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Text("شدة التوهج: ${(neonGlow * 100).toInt()}%", color = palette.textPrimary, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = neonGlow,
                        onValueChange = { neonGlow = it },
                        valueRange = 0.15f..1.0f
                    )
                }
            }
        },
        confirmButton = {
            when (modeTab) {
                3 -> Button(
                    onClick = {
                        val effectivePreset = if (neonEnabled) selectedNeon else NeonColorPreset.NONE
                        onApplyNeon(effectivePreset, neonGlow, neonEnabled)
                    },
                    enabled = "neon_${user.uid}" !in busyOps
                ) {
                    Text("حفظ النيون")
                }
                4 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { onApplyNameplateColor("") },
                        enabled = "nameplate_${user.uid}" !in busyOps
                    ) {
                        Text("إعادة تعيين")
                    }
                    Button(
                        onClick = { onApplyNameplateColor(selectedNameplateKey) },
                        enabled = selectedNameplateKey.isNotBlank() && "nameplate_${user.uid}" !in busyOps
                    ) {
                        Text("تطبيق اللون")
                    }
                }
                2 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { onApplyIcon("", "") },
                        enabled = "icon_${user.uid}" !in busyOps
                    ) {
                        Text("إزالة")
                    }
                    Button(
                        onClick = { onApplyIcon(selectedIconId, selectedIconUrl) },
                        enabled = selectedIconId.isNotBlank() && selectedIconUrl.isNotBlank() && "icon_${user.uid}" !in busyOps
                    ) {
                        Text("تطبيق الأيقونة")
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { onApplyFrame("", 0L, "إزالة") },
                        enabled = "frame_${user.uid}" !in busyOps
                    ) {
                        Text("إزالة")
                    }
                    Button(
                        onClick = {
                            val (exp, lbl) = calculateDurationExpiresAt(selectedDuration, customCount.toIntOrNull() ?: 7)
                            onApplyFrame(selectedFrameId, exp, lbl)
                        },
                        enabled = selectedFrameId.isNotBlank() && "frame_${user.uid}" !in busyOps
                    ) {
                        Text("تطبيق الإطار")
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

@Composable
private fun AdminBanDialog(
    user: WesalUser,
    busyOps: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onConfirmBan: (Boolean, Long, String, Boolean) -> Unit
) {
    val palette = WesalColors.current
    var permanent by remember { mutableStateOf(false) }
    var durationOpt by remember { mutableStateOf(VerificationDurationOption.CUSTOM_DAYS) }
    var customCount by remember { mutableStateOf("7") }
    var reason by remember { mutableStateOf("") }
    var blockIdentifiers by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("حظر الحساب", color = palette.error, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${user.displayName} (ID: ${user.numericId})", color = palette.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !permanent, onClick = { permanent = false }, label = { Text("حظر مؤقت") })
                    FilterChip(selected = permanent, onClick = { permanent = true }, label = { Text("حظر نهائي") })
                }
                if (!permanent) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = durationOpt == VerificationDurationOption.CUSTOM_HOURS, onClick = { durationOpt = VerificationDurationOption.CUSTOM_HOURS }, label = { Text("ساعات") })
                        FilterChip(selected = durationOpt == VerificationDurationOption.CUSTOM_DAYS, onClick = { durationOpt = VerificationDurationOption.CUSTOM_DAYS }, label = { Text("أيام") })
                        FilterChip(selected = durationOpt == VerificationDurationOption.MONTH_1, onClick = { durationOpt = VerificationDurationOption.MONTH_1 }, label = { Text("شهر") })
                    }
                    if (durationOpt != VerificationDurationOption.MONTH_1) {
                        OutlinedTextField(
                            value = customCount,
                            onValueChange = { customCount = it.filter(Char::isDigit).take(3) },
                            label = { Text("المدة") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("منع البريد والهاتف الموثق من إعادة التسجيل", color = palette.textPrimary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = blockIdentifiers, onCheckedChange = { blockIdentifiers = it })
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("سبب الحظر (إلزامي)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val (exp, _) = if (permanent) 0L to "" else calculateDurationExpiresAt(durationOpt, customCount.toIntOrNull() ?: 7)
                    onConfirmBan(permanent, exp, reason, blockIdentifiers)
                },
                // AUDIT-C: reason is labelled mandatory (إلزامي) and the VM
                // rejects a blank one, but the button stayed enabled — the
                // dialog closed with no ban applied (dead-button feel).
                enabled = reason.isNotBlank() && "ban_${user.uid}" !in busyOps,
                colors = ButtonDefaults.buttonColors(containerColor = palette.error)
            ) { Text("تأكيد الحظر") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun AdminReportReviewDialog(
    report: AdminReportRecord,
    busyOps: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSaveStatus: (String, String, String) -> Unit,
    onOpenReportedChat: () -> Unit,
    onInspectReportedUser: () -> Unit
) {
    val palette = WesalColors.current
    var status by remember(report.reportId) { mutableStateOf(report.status) }
    var note by remember(report.reportId) { mutableStateOf(report.adminNote) }
    var resolution by remember(report.reportId) { mutableStateOf(report.resolutionAction) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("مراجعة البلاغ #${report.reportId.take(8)}", color = palette.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("النوع: ${report.targetType}", color = palette.turquoise, style = MaterialTheme.typography.labelMedium)
                Text("السبب: ${report.reason}", color = palette.textPrimary, style = MaterialTheme.typography.bodySmall)
                if (report.targetContentSnapshot.isNotBlank()) {
                    Surface(color = palette.surfaceElevated, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(report.targetContentSnapshot, color = palette.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("IN_REVIEW", "RESOLVED", "REJECTED", "CLOSED").forEach { st ->
                        FilterChip(selected = status == st, onClick = { status = st }, label = { Text(st) })
                    }
                }
                OutlinedTextField(
                    value = resolution,
                    onValueChange = { resolution = it },
                    label = { Text("الإجراء المتخذ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("ملاحظة المشرف الأعلى") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (report.targetChatId.isNotBlank()) {
                        OutlinedButton(onClick = onOpenReportedChat, modifier = Modifier.weight(1f)) {
                            Text("فتح المحادثة", maxLines = 1)
                        }
                    }
                    if (report.reportedUid.isNotBlank()) {
                        OutlinedButton(onClick = onInspectReportedUser, modifier = Modifier.weight(1f)) {
                            Text("فحص الحساب", maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveStatus(status, note, resolution) },
                enabled = "report_${report.reportId}" !in busyOps
            ) {
                Text("حفظ")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

// ---------------------------------------------------------------------------
// Sections 20 - 22: Subscription Requests, Moderators & Global Appearance
// ---------------------------------------------------------------------------

@Composable
private fun AdminSubscriptionRequestsSection(
    busyOps: Set<String> = emptySet(),
    requests: List<SubscriptionRequest>,
    onReview: (String, Boolean, String) -> Unit,
    onInspectUser: (String) -> Unit
) {
    val palette = WesalColors.current
    var filter by remember { mutableStateOf("PENDING") }
    var search by remember { mutableStateOf("") }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    var reviewDialogTarget by remember { mutableStateOf<Pair<SubscriptionRequest, Boolean>?>(null) }

    val filtered = remember(requests, filter, search) {
        val q = search.trim()
        val lower = q.lowercase()
        val normDigits = WesalIdUtils.normalizeDigits(q)
        requests.filter { req ->
            val statusOk = filter == "ALL" || req.status == filter
            val searchOk = q.isEmpty() ||
                req.userName.lowercase().contains(lower) ||
                req.userNumericId.contains(normDigits) ||
                req.userPublicId.lowercase().contains(lower.removePrefix("@")) ||
                req.userUid.lowercase().contains(lower) ||
                req.desiredSeal.lowercase().contains(lower)
            statusOk && searchOk
        }
    }

    previewImageUrl?.let { imgUrl ->
        AlertDialog(
            onDismissRequest = { previewImageUrl = null },
            containerColor = palette.surface,
            title = { Text("معاينة الهوية المرفقة", color = palette.textPrimary) },
            text = {
                AsyncImage(
                    model = wesalImageModel(imgUrl),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
            },
            confirmButton = {
                TextButton(onClick = { previewImageUrl = null }) { Text("إغلاق") }
            }
        )
    }

    reviewDialogTarget?.let { (req, approve) ->
        var note by remember(req.requestId, approve) {
            mutableStateOf(if (approve) "تمت الموافقة وتوثيق حسابك بنجاح" else "")
        }
        AlertDialog(
            onDismissRequest = { reviewDialogTarget = null },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (approve) "الموافقة على التوثيق (${req.desiredSeal})" else "رفض طلب التوثيق",
                    color = if (approve) palette.emerald else palette.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${req.userName} (ID: ${req.userNumericId})", color = palette.textSecondary)
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("ملاحظة أو رد للمستخدم") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onReview(req.requestId, approve, note)
                        reviewDialogTarget = null
                    },
                    enabled = "sub_review_${req.requestId}" !in busyOps,
                    colors = ButtonDefaults.buttonColors(containerColor = if (approve) palette.emerald else palette.error)
                ) { Text(if (approve) "موافقة وتوثيق" else "تأكيد الرفض") }
            },
            dismissButton = {
                TextButton(onClick = { reviewDialogTarget = null }) { Text("إلغاء") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AdminInlineSearchBar(
            query = search,
            onQueryChange = { search = it },
            placeholder = "ابحث بالاسم أو ID أو @username أو نوع الشارة"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "PENDING" to "قيد المراجعة",
                "APPROVED" to "الموافق عليها",
                "REJECTED" to "المرفوضة",
                "ALL" to "الكل"
            ).forEach { (key, label) ->
                FilterChip(
                    selected = filter == key,
                    onClick = { filter = key },
                    label = { Text(label) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filtered.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("لا توجد طلبات مطابقة حاليًا", color = palette.textMuted)
                    }
                }
            }
            items(filtered, key = { it.requestId }) { req ->
                val statusColor = when (req.status) {
                    "APPROVED" -> palette.emerald
                    "REJECTED" -> palette.error
                    else -> palette.warning
                }
                Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(req.userName.ifBlank { req.userUid }, color = palette.textPrimary, fontWeight = FontWeight.Bold)
                                Text("ID: ${req.userNumericId.ifBlank { "—" }} • @${req.userPublicId.ifBlank { "—" }}", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                WesalVerifiedBadge(
                                    size = 18.dp,
                                    verificationType = req.desiredSeal,
                                    verificationGlowColor = req.desiredGlowColor
                                )
                                Text(req.status, color = statusColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (req.desiredFrameId.isNotBlank() || (req.desiredGlowColor.isNotBlank() && req.desiredGlowColor != "NONE")) {
                            val frameLabel = req.desiredFrameId.takeIf { it.isNotBlank() }?.let { fid ->
                                val titleAr = WesalFrameLibrary.docs[fid]?.titleAr?.takeIf { it.isNotBlank() }
                                    ?: WesalFrameCatalog.allFrames.firstOrNull { it.id == fid }?.titleAr
                                    ?: fid
                                "الإطار المطلوب: $titleAr"
                            }
                            val glowLabel = req.desiredGlowColor.takeIf { it.isNotBlank() && it != "NONE" }?.let { gKey ->
                                "لون التوهج المطلوب: ${NeonColorPreset.fromKey(gKey).labelAr}"
                            }
                            Text(
                                text = listOfNotNull(frameLabel, glowLabel).joinToString(" • "),
                                color = palette.turquoise,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (req.note.isNotBlank()) {
                            Text("ملاحظة المستخدم: ${req.note}", color = palette.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        if (req.idDocumentUrl.isNotBlank()) {
                            AsyncImage(
                                model = wesalImageModel(req.idDocumentUrl),
                                contentDescription = "صورة الهوية",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { previewImageUrl = req.idDocumentUrl },
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (req.adminResponse.isNotBlank()) {
                            Text("الرد الإداري: ${req.adminResponse}", color = palette.turquoise, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(formatAdminTime(req.createdAt), color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { onInspectUser(req.userUid) }) {
                                Text("الحساب", maxLines = 1)
                            }
                            if (req.status == "PENDING") {
                                OutlinedButton(
                                    onClick = { reviewDialogTarget = req to false },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.error),
                                    modifier = Modifier.weight(1f)
                                ) { Text("رفض", maxLines = 1) }
                                Button(
                                    onClick = { reviewDialogTarget = req to true },
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald),
                                    modifier = Modifier.weight(1f)
                                ) { Text("موافقة وتوثيق", maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminModeratorsSection(
    busyOps: Set<String> = emptySet(),
    users: List<WesalUser>,
    moderators: List<Pair<WesalUser, AdminPermissions>>,
    onSavePermissions: (String, AdminPermissions?) -> Unit
) {
    val palette = WesalColors.current
    var search by remember { mutableStateOf("") }
    var editingPair by remember { mutableStateOf<Pair<WesalUser, AdminPermissions>?>(null) }

    editingPair?.let { (modUser, initialPerms) ->
        var perms by remember(modUser.uid) { mutableStateOf(initialPerms) }
        AlertDialog(
            onDismissRequest = { editingPair = null },
            containerColor = palette.surface,
            title = { Text("صلاحيات المشرف: ${modUser.displayName.ifBlank { modUser.numericId }}", color = palette.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AdminPermRow("تفعيل الإشراف", perms.active) { perms = perms.copy(active = it) }
                    AdminPermRow("إدارة التوثيق والشارات", perms.canManageVerification) { perms = perms.copy(canManageVerification = it) }
                    AdminPermRow("الإطارات والنيون والأيقونات", perms.canManageFramesAndNeon) { perms = perms.copy(canManageFramesAndNeon = it) }
                    AdminPermRow("رفع وتعديل مكتبة الإطارات", perms.canManageLibrary) { perms = perms.copy(canManageLibrary = it) }
                    AdminPermRow("الحظر والإنذارات والإخفاء", perms.canManageUsersModeration) { perms = perms.copy(canManageUsersModeration = it) }
                    AdminPermRow("معاينة البيانات وسجلات التدقيق", perms.canInspectPrivateData) { perms = perms.copy(canInspectPrivateData = it) }
                    AdminPermRow("معالجة البلاغات والمحتوى", perms.canManageReports) { perms = perms.copy(canManageReports = it) }
                    AdminPermRow("الإعلانات ورسالة الترحيب وفريق وصال", perms.canBroadcastAnnouncements) { perms = perms.copy(canBroadcastAnnouncements = it) }
                    AdminPermRow("تعديل ألوان ومظهر التطبيق العام", perms.canCustomizeGlobalTheme) { perms = perms.copy(canCustomizeGlobalTheme = it) }
                    AdminPermRow("إدارة المشرفين المساعدين", perms.canManageAdmins) { perms = perms.copy(canManageAdmins = it) }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            onSavePermissions(modUser.uid, null)
                            editingPair = null
                        },
                        enabled = "moderator_${modUser.uid}" !in busyOps,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.error)
                    ) { Text("سحب الإشراف") }
                    Button(
                        onClick = {
                            onSavePermissions(modUser.uid, perms)
                            editingPair = null
                        },
                        enabled = "moderator_${modUser.uid}" !in busyOps,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.emerald)
                    ) { Text("حفظ") }
                }
            },
            dismissButton = { TextButton(onClick = { editingPair = null }) { Text("إلغاء") } }
        )
    }

    val eligibleCandidates = remember(users, search, moderators) {
        val modUids = moderators.map { it.first.uid }.toSet()
        users.filter { u ->
            !Constants.isProtectedAdminNumericId(u.numericId) &&
                !Constants.isSuperAdminEmail(u.email) &&
                u.uid !in modUids &&
                (search.isBlank() ||
                    u.displayName.contains(search, ignoreCase = true) ||
                    u.numericId.contains(search.trim()) ||
                    u.publicId.contains(search.trim(), ignoreCase = true))
        }.take(20)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("المشرفون المساعدون الحاليون (${moderators.size})", color = palette.textPrimary, fontWeight = FontWeight.Bold)
        }
        if (moderators.isEmpty()) {
            item {
                Text("لا يوجد مشرفون مساعدون حاليًا.", color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(moderators, key = { it.first.uid }) { (u, p) ->
                Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(14.dp)) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        WesalAvatar(imageUrl = u.photoUrl, name = u.displayName, size = 40.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(u.displayName.ifBlank { "@${u.publicId}" }, color = palette.textPrimary, fontWeight = FontWeight.Bold)
                            Text("ID: ${u.numericId} • ${if (p.active) "مفعّل" else "معطل"}", color = if (p.active) palette.emerald else palette.error, style = MaterialTheme.typography.labelSmall)
                        }
                        FilledTonalButton(onClick = { editingPair = u to p }) { Text("الصلاحيات") }
                    }
                }
            }
        }

        item {
            HorizontalDivider(color = palette.border)
            Spacer(Modifier.height(6.dp))
            Text("إضافة مشرف مساعد من المستخدمين", color = palette.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("ابحث بالاسم أو المعرف أو @username لإضافته كمشرف") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(eligibleCandidates, key = { it.uid }) { u ->
            Card(colors = CardDefaults.cardColors(containerColor = palette.surfaceElevated), shape = RoundedCornerShape(12.dp)) {
                Row(
                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WesalAvatar(imageUrl = u.photoUrl, name = u.displayName, size = 36.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(u.displayName.ifBlank { "WESAL User" }, color = palette.textPrimary, fontWeight = FontWeight.Medium)
                        Text("ID: ${u.numericId} • @${u.publicId}", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = { editingPair = u to AdminPermissions(canManageReports = true, active = true) }) {
                        Text("ترقية لمشرف")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminPermRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = WesalColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = palette.textPrimary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AdminGlobalAppearanceSection(
    busyOps: Set<String> = emptySet(),
    config: GlobalAppearanceConfig,
    onSave: (GlobalAppearanceConfig) -> Unit
) {
    val palette = WesalColors.current
    var draft by remember(config) { mutableStateOf(config) }

    val primaryChoices = listOf(
        "أخضر وصال" to 0xFF10B981L,
        "أزرق ملكي" to 0xFF2563EBL,
        "ذهبي فاخر" to 0xFFD97706L,
        "بنفسجي" to 0xFF7C3AEDL,
        "وردي" to 0xFFDB2777L,
        "أحمر قرمزي" to 0xFFDC2626L
    )
    val accentChoices = listOf(
        "تركواز وصال" to 0xFF00D9E8L,
        "أزرق سماوي" to 0xFF38BDF8L,
        "ذهبي مشرق" to 0xFFFACC15L,
        "أرجواني فاتح" to 0xFFC084FCL,
        "نعناعي" to 0xFF34D399L,
        "برتقالي" to 0xFFFB923CL
    )
    val bgChoices = listOf(
        "افتراضي" to 0L,
        "ليلي عميق" to 0xFF050A0EL,
        "كحلي داكن" to 0xFF0B1120L,
        "أرجواني داكن" to 0xFF120B1EL,
        "أبيض نقي" to 0xFFF8FAFCL
    )
    val surfaceChoices = listOf(
        "افتراضي" to 0L,
        "داكن" to 0xFF0F171EL,
        "رمادي مزرق" to 0xFF172033L,
        "أبيض ناعم" to 0xFFFFFFFFL
    )
    val textChoices = listOf(
        "افتراضي" to 0L,
        "أبيض ناصع" to 0xFFFFFFFFL,
        "أبيض ثلجي" to 0xFFE2E8F0L,
        "أسود داكن" to 0xFF0F172AL
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("تفعيل المظهر والألوان العامة لجميع المستخدمين", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                        Text("يُطبَّق فوريًا على ألوان وأيقونات التطبيق بالكامل", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
                }
            }
        }

        item {
            ColorSwatchCard("اللون الأساسي والأزرار (Primary / Emerald)", primaryChoices, draft.emeraldHex) {
                draft = draft.copy(emeraldHex = it)
            }
        }
        item {
            ColorSwatchCard("لون التمييز الثانوي (Turquoise Accent)", accentChoices, draft.turquoiseHex) {
                draft = draft.copy(turquoiseHex = it)
            }
        }
        item {
            ColorSwatchCard("لون الخلفية الرئيسية (Background)", bgChoices, draft.backgroundHex) {
                draft = draft.copy(backgroundHex = it)
            }
        }
        item {
            ColorSwatchCard("لون البطاقات والأسطح (Surface)", surfaceChoices, draft.surfaceHex) {
                draft = draft.copy(surfaceHex = it)
            }
        }
        item {
            ColorSwatchCard("لون النصوص الأساسي (Primary Text)", textChoices, draft.textPrimaryHex) {
                draft = draft.copy(textPrimaryHex = it)
            }
        }
        item {
            ColorSwatchCard("لون صبغة الأيقونات (Icon Tint)", listOf("افتراضي" to 0L) + accentChoices, draft.iconTintHex) {
                draft = draft.copy(iconTintHex = it)
            }
        }

        item {
            // Live preview of the DRAFT colours (spec §11: admin sees the result before
            // publishing). Hex 0 means "app default", so fall back to the live palette.
            val previewBg = if (draft.backgroundHex != 0L) Color(draft.backgroundHex) else palette.background
            val previewSurface = if (draft.surfaceHex != 0L) Color(draft.surfaceHex) else palette.surface
            val previewText = if (draft.textPrimaryHex != 0L) Color(draft.textPrimaryHex) else palette.textPrimary
            val previewPrimary = if (draft.emeraldHex != 0L) Color(draft.emeraldHex) else palette.emerald
            val previewAccent = if (draft.turquoiseHex != 0L) Color(draft.turquoiseHex) else palette.turquoise
            val previewIconTint = if (draft.iconTintHex != 0L) Color(draft.iconTintHex) else previewAccent
            Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "معاينة مباشرة (قبل الحفظ)",
                        color = palette.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(previewBg)
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(previewSurface)
                                    .padding(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(previewPrimary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("و", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "محادثة تجريبية",
                                        color = previewText,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "هكذا ستبدو الألوان على أجهزة الجميع",
                                        color = previewText.copy(alpha = 0.65f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Icon(
                                    Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = previewIconTint
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(previewPrimary)
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                ) {
                                    Text("زر أساسي", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(previewAccent)
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                ) {
                                    Text("تمييز", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    if (!draft.enabled) {
                        Text(
                            "تنبيه: المظهر العام غير مفعّل — فعّل المفتاح أعلاه ليصل هذا المظهر لكل الأجهزة.",
                            color = palette.warning,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        item {
            // Round 9 (item 6): both labels are ~17 chars — at half width on
            // narrower screens they clip; stack them full-width, one line each.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        val reset = GlobalAppearanceConfig(enabled = false)
                        draft = reset
                        onSave(reset)
                    },
                    enabled = "appearance_save" !in busyOps,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("استعادة الافتراضي", maxLines = 1, softWrap = false) }
                Button(
                    onClick = { onSave(draft) },
                    enabled = "appearance_save" !in busyOps,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("حفظ وتطبيق للجميع", maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
private fun ColorSwatchCard(
    title: String,
    options: List<Pair<String, Long>>,
    selectedHex: Long,
    onSelect: (Long) -> Unit
) {
    val palette = WesalColors.current
    Card(colors = CardDefaults.cardColors(containerColor = palette.surface), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = palette.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (label, hex) ->
                    val isSel = selectedHex == hex
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSel) palette.emerald.copy(alpha = 0.16f) else palette.surfaceElevated,
                        border = BorderStroke(1.dp, if (isSel) palette.emerald else palette.border),
                        modifier = Modifier.clickable { onSelect(hex) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (hex != 0L) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(hex))
                                        .border(1.dp, palette.border, CircleShape)
                                )
                            }
                            Text(label, color = palette.textPrimary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
