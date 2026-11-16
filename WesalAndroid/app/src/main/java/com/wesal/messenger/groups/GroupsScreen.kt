package com.wesal.messenger.groups

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wesal.messenger.channels.ChannelsScreen
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.domain.model.GroupInfo
import com.wesal.messenger.domain.model.GroupJoinMode
import com.wesal.messenger.domain.model.GroupJoinRequest
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.GroupRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.WesalQrCodeBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository
) : ViewModel() {
    val myUid: String
        get() = authRepository.currentUserId.orEmpty()
    val groups: StateFlow<List<GroupInfo>> = groupRepository.observeUserGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchedUsers = MutableStateFlow<List<WesalUser>>(emptyList())
    val selectedMembers = MutableStateFlow<List<WesalUser>>(emptyList())
    val isCreating = MutableStateFlow(false)
    val statusMessage = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null
    private var dismissJob: Job? = null

    private fun postStatus(msg: String) {
        dismissJob?.cancel()
        statusMessage.value = msg
        dismissJob = viewModelScope.launch {
            delay(3600L)
            if (statusMessage.value == msg) statusMessage.value = null
        }
    }

    fun clearStatus() {
        dismissJob?.cancel()
        statusMessage.value = null
    }

    fun searchUsers(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchedUsers.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(250L)
            searchedUsers.value = userRepository.searchUsers(query)
        }
    }

    fun toggleMemberSelection(user: WesalUser) {
        selectedMembers.update { list ->
            if (list.any { it.uid == user.uid }) list.filterNot { it.uid == user.uid } else list + user
        }
    }

    fun createGroup(
        name: String,
        description: String,
        avatarUri: Uri?,
        joinMode: GroupJoinMode = GroupJoinMode.OPEN,
        onCreated: (String) -> Unit
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            isCreating.value = true
            groupRepository.createGroup(
                name = name,
                description = description,
                memberIds = selectedMembers.value.map { it.uid },
                avatarUri = avatarUri,
                joinMode = joinMode
            ).onSuccess { group ->
                selectedMembers.value = emptyList()
                onCreated(group.chatId)
            }.onFailure { err ->
                postStatus(err.message ?: "تعذر إنشاء المجموعة")
            }
            isCreating.value = false
        }
    }

    fun joinByInviteCode(code: String, onOpenedChat: (String) -> Unit) {
        viewModelScope.launch {
            val found = runCatching { groupRepository.findGroupByInviteCode(code) }.getOrNull()
            if (found == null) {
                postStatus("رمز الدعوة غير صالح أو تم إغلاق المجموعة")
                return@launch
            }
            if (found.memberIds.contains(myUid)) {
                onOpenedChat(found.chatId)
                return@launch
            }
            groupRepository.requestJoinGroup(found.groupId)
                .onSuccess {
                    if (found.joinMode == GroupJoinMode.APPROVAL_REQUIRED) {
                        postStatus("تم إرسال طلب الانضمام إلى مشرفي «${found.name}»")
                    } else {
                        onOpenedChat(found.chatId)
                    }
                }
                .onFailure { err ->
                    postStatus(err.message ?: "تعذر الانضمام إلى المجموعة")
                }
        }
    }

    fun observeGroup(groupId: String): Flow<GroupInfo?> = groupRepository.observeGroup(groupId)

    fun observeJoinRequests(groupId: String): Flow<List<GroupJoinRequest>> =
        groupRepository.observeGroupJoinRequests(groupId)

    suspend fun loadUsersByIds(uids: List<String>): List<WesalUser> =
        uids.mapNotNull { userRepository.getUserOnce(it) }

    fun updateGroupInfo(groupId: String, name: String, desc: String, avatarUri: Uri?) {
        viewModelScope.launch {
            groupRepository.updateGroupInfo(groupId, name, desc, avatarUri)
                .onSuccess { postStatus("تم تحديث بيانات المجموعة") }
                .onFailure { postStatus(it.message ?: "تعذر التحديث") }
        }
    }

    fun updateJoinMode(groupId: String, mode: GroupJoinMode) {
        viewModelScope.launch {
            groupRepository.updateGroupJoinMode(groupId, mode)
                .onSuccess { postStatus("تم تحديث وضع الانضمام") }
                .onFailure { postStatus(it.message ?: "تعذر تغيير وضع الانضمام") }
        }
    }

    fun regenerateInviteCode(groupId: String) {
        viewModelScope.launch {
            groupRepository.regenerateGroupInviteCode(groupId)
                .onSuccess { code -> postStatus("تم تجديد رمز الدعوة وإلغاء الرابط السابق: $code") }
                .onFailure { postStatus(it.message ?: "تعذر تجديد رابط الدعوة") }
        }
    }

    fun approveJoinRequest(groupId: String, req: GroupJoinRequest) {
        viewModelScope.launch {
            groupRepository.approveGroupJoinRequest(groupId, req.requestId, req.requesterUid)
                .onSuccess { postStatus("تمت الموافقة على انضمام ${req.requesterName}") }
                .onFailure { postStatus(it.message ?: "تعذر قبول الطلب") }
        }
    }

    fun rejectJoinRequest(groupId: String, req: GroupJoinRequest) {
        viewModelScope.launch {
            groupRepository.rejectGroupJoinRequest(groupId, req.requestId)
                .onFailure { postStatus(it.message ?: "تعذر رفض الطلب") }
        }
    }

    fun addMember(groupId: String, uid: String) {
        viewModelScope.launch { groupRepository.addMembers(groupId, listOf(uid)) }
    }

    fun removeMember(groupId: String, uid: String) {
        viewModelScope.launch { groupRepository.removeMember(groupId, uid) }
    }

    fun toggleAdmin(groupId: String, uid: String, makeAdmin: Boolean) {
        viewModelScope.launch { groupRepository.setAdminRole(groupId, uid, makeAdmin) }
    }

    fun leaveGroup(groupId: String, onLeft: () -> Unit) {
        viewModelScope.launch {
            groupRepository.leaveGroup(groupId).onSuccess { onLeft() }
        }
    }
}

private fun joinModeLabel(mode: GroupJoinMode, isEnglish: Boolean): String = when (mode) {
    GroupJoinMode.OPEN -> if (isEnglish) "Open" else "مفتوحة للانضمام"
    GroupJoinMode.APPROVAL_REQUIRED -> if (isEnglish) "Admin Approval" else "بموافقة المشرف"
    GroupJoinMode.INVITE_ONLY -> if (isEnglish) "Invite Link Only" else "بدعوة فقط"
    GroupJoinMode.CLOSED -> if (isEnglish) "Closed" else "مغلقة"
}

@Composable
fun GroupsScreen(
    onOpenChat: (String) -> Unit,
    onOpenChannel: (String) -> Unit = {},
    viewModel: GroupsViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val searched by viewModel.searchedUsers.collectAsStateWithLifecycle()
    val selectedMembers by viewModel.selectedMembers.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    var selectedSegment by rememberSaveable { mutableIntStateOf(0) } // 0 = Groups, 1 = Channels
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinByCodeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        TabRow(
            selectedTabIndex = selectedSegment,
            containerColor = palette.surface,
            contentColor = palette.emerald
        ) {
            Tab(
                selected = selectedSegment == 0,
                onClick = { selectedSegment = 0 },
                text = { Text(strings.tabGroups, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
            Tab(
                selected = selectedSegment == 1,
                onClick = { selectedSegment = 1 },
                text = { Text(strings.tabChannels, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }

        if (selectedSegment == 1) {
            ChannelsScreen(onOpenChannel = onOpenChannel)
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    statusMessage?.let { msg ->
                        Surface(
                            color = palette.emerald.copy(alpha = 0.14f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.clearStatus() }
                        ) {
                            Text(
                                text = msg,
                                color = palette.emerald,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (strings.isEnglish) "Your Groups (${groups.size})" else "مجموعاتك (${groups.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.textSecondary,
                            fontWeight = FontWeight.Bold
                        )
                        FilledTonalButton(
                            onClick = { showJoinByCodeDialog = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = palette.turquoise.copy(alpha = 0.14f),
                                contentColor = palette.turquoise
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (strings.isEnglish) "Join by Code" else "انضمام برمز",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (groups.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (strings.isEnglish) {
                                    "No groups yet — create a new group"
                                } else {
                                    "لا توجد مجموعات حتى الآن — أنشئ مجموعة جديدة"
                                },
                                color = palette.textSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp)
                        ) {
                            items(groups, key = { it.groupId }) { group ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenChat(group.chatId) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    WesalAvatar(imageUrl = group.avatarUrl, name = group.name, size = 54.dp)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = group.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = palette.textPrimary,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Surface(
                                                color = palette.surfaceElevated,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = joinModeLabel(group.joinMode, strings.isEnglish),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = palette.textMuted,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        val membersLabel = if (strings.isEnglish) "${group.memberIds.size} members" else "${group.memberIds.size} عضو"
                                        val fallbackDesc = if (strings.isEnglish) "WESAL Group" else "مجموعة WESAL"
                                        Text(
                                            text = "$membersLabel • ${group.description.ifBlank { fallbackDesc }}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = palette.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                            }
                        }
                    }
                }

                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = palette.emerald,
                    contentColor = palette.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.GroupAdd, contentDescription = strings.newGroup)
                }
            }
        }
    }

    if (showJoinByCodeDialog) {
        var codeInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJoinByCodeDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Join Group" else "الانضمام إلى مجموعة", color = palette.textPrimary) },
            text = {
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it },
                    label = { Text(if (strings.isEnglish) "Invite code or link" else "رمز الدعوة أو الرابط") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedBorderColor = palette.emerald,
                        unfocusedBorderColor = palette.border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.joinByInviteCode(codeInput) { chatId ->
                            showJoinByCodeDialog = false
                            onOpenChat(chatId)
                        }
                        showJoinByCodeDialog = false
                    },
                    enabled = codeInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                ) {
                    Text(strings.confirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinByCodeDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    if (showCreateDialog) {
        var groupName by remember { mutableStateOf("") }
        var groupDesc by remember { mutableStateOf("") }
        var memberQuery by remember { mutableStateOf("") }
        var groupAvatarUri by remember { mutableStateOf<Uri?>(null) }
        var joinMode by remember { mutableStateOf(GroupJoinMode.OPEN) }

        val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) groupAvatarUri = uri
        }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = palette.surface,
            title = { Text(strings.newGroup, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val dialogFieldColors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedLabelColor = palette.turquoise,
                        unfocusedLabelColor = palette.textSecondary,
                        focusedBorderColor = palette.turquoise,
                        unfocusedBorderColor = palette.border,
                        cursorColor = palette.turquoise
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceElevated)
                                .clickable {
                                    avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (groupAvatarUri != null) {
                                AsyncImage(model = groupAvatarUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = palette.turquoise)
                            }
                        }
                        OutlinedTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            label = { Text(if (strings.isEnglish) "Group Name" else "اسم المجموعة") },
                            colors = dialogFieldColors,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = groupDesc,
                        onValueChange = { groupDesc = it },
                        label = { Text(if (strings.isEnglish) "Description (Optional)" else "وصف المجموعة (اختياري)") },
                        colors = dialogFieldColors,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = if (strings.isEnglish) "Join Policy" else "نظام الانضمام للمجموعة",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GroupJoinMode.entries.forEach { mode ->
                            val selected = joinMode == mode
                            Surface(
                                color = if (selected) palette.emerald.copy(alpha = 0.2f) else palette.surfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { joinMode = mode }
                            ) {
                                Text(
                                    text = joinModeLabel(mode, strings.isEnglish),
                                    color = if (selected) palette.emerald else palette.textSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = memberQuery,
                        onValueChange = {
                            memberQuery = it
                            viewModel.searchUsers(it)
                        },
                        label = { Text(strings.searchContactsPlaceholder) },
                        colors = dialogFieldColors,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (selectedMembers.isNotEmpty()) {
                        Text(
                            text = selectedMembers.joinToString(", ") {
                                it.displayName.ifBlank { "@${it.publicId}" }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.emerald
                        )
                    }

                    LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                        items(searched, key = { it.uid }) { user ->
                            val isSelected = selectedMembers.any { it.uid == user.uid }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleMemberSelection(user) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                WesalNameWithBadge(
                                    name = user.displayName.ifBlank { "@${user.publicId}" },
                                    isVerified = user.isVerified,
                                    verificationType = user.verificationType,
                                    neonColor = user.neonColor,
                                    neonGlowIntensity = user.neonGlowIntensity,
                                    modifier = Modifier.weight(1f)
                                )
                                Checkbox(checked = isSelected, onCheckedChange = { viewModel.toggleMemberSelection(user) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createGroup(groupName, groupDesc, groupAvatarUri, joinMode) { chatId ->
                            showCreateDialog = false
                            onOpenChat(chatId)
                        }
                    },
                    enabled = !isCreating && groupName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                ) {
                    Text(
                        if (isCreating) strings.loading
                        else if (strings.isEnglish) "Create" else "إنشاء"
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(strings.cancel) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    groupId: String,
    onBack: () -> Unit,
    onOpenUserProfile: (String) -> Unit = {},
    onLeftGroup: () -> Unit,
    viewModel: GroupsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val group by viewModel.observeGroup(groupId).collectAsStateWithLifecycle(initialValue = null)
    val joinRequests by viewModel.observeJoinRequests(groupId).collectAsStateWithLifecycle(initialValue = emptyList())
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    var members by remember { mutableStateOf<List<WesalUser>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchedUsers.collectAsStateWithLifecycle()
    var showEditDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(group?.memberIds) {
        group?.memberIds?.let { ids ->
            members = viewModel.loadUsersByIds(ids)
        }
    }

    val isAdmin = group?.adminIds?.contains(viewModel.myUid) == true

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: (if (strings.isEnglish) "Group Info" else "معلومات المجموعة"), color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                actions = {
                    if (isAdmin && group != null) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = strings.edit, tint = palette.turquoise)
                        }
                    }
                    if (group != null) {
                        IconButton(onClick = { showInviteDialog = true }) {
                            Icon(Icons.Default.QrCode2, contentDescription = strings.share, tint = palette.emerald)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            statusMessage?.let { msg ->
                item {
                    Surface(
                        color = palette.emerald.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.clearStatus() }
                    ) {
                        Text(msg, color = palette.emerald, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            group?.let { g ->
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WesalAvatar(imageUrl = g.avatarUrl, name = g.name, size = 84.dp)
                        Text(g.name, style = MaterialTheme.typography.headlineSmall, color = palette.textPrimary)
                        if (g.description.isNotBlank()) {
                            Text(g.description, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
                        }
                        Surface(
                            color = palette.surfaceElevated,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "${if (strings.isEnglish) "Join Mode" else "وضع الانضمام"}: ${joinModeLabel(g.joinMode, strings.isEnglish)}",
                                color = palette.turquoise,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (isAdmin) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = palette.surface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (strings.isEnglish) "Group Join Control" else "إدارة الانضمام والرابط",
                                    color = palette.emerald,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                GroupJoinMode.entries.forEach { mode ->
                                    val selected = g.joinMode == mode
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (selected) palette.emerald.copy(alpha = 0.14f) else palette.surfaceElevated)
                                            .clickable { viewModel.updateJoinMode(g.groupId, mode) }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = joinModeLabel(mode, strings.isEnglish),
                                            color = if (selected) palette.emerald else palette.textPrimary,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        RadioButton(selected = selected, onClick = { viewModel.updateJoinMode(g.groupId, mode) })
                                    }
                                }

                                if (g.joinMode != GroupJoinMode.CLOSED) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.regenerateInviteCode(g.groupId) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (strings.isEnglish) "Regenerate Link" else "تجديد الرابط", maxLines = 1)
                                        }
                                        Button(
                                            onClick = { showInviteDialog = true },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                                        ) {
                                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (strings.isEnglish) "Invite Link" else "رابط الدعوة", maxLines = 1)
                                        }
                                    }
                                } else {
                                    Text(
                                        text = if (strings.isEnglish) "All invite links are revoked while group is closed." else "جميع روابط الدعوة معطّلة أثناء إغلاق المجموعة.",
                                        color = palette.error,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }

                    if (joinRequests.isNotEmpty()) {
                        item {
                            Text(
                                text = if (strings.isEnglish) "Pending Join Requests (${joinRequests.size})" else "طلبات الانضمام المعلقة (${joinRequests.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.emerald,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(joinRequests, key = { it.requestId }) { req ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.surface)
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                WesalAvatar(imageUrl = req.requesterAvatarUrl, name = req.requesterName, size = 40.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(req.requesterName, color = palette.textPrimary, fontWeight = FontWeight.SemiBold)
                                    if (req.requesterNumericId.isNotBlank()) {
                                        Text("ID: ${req.requesterNumericId}", color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                IconButton(onClick = { viewModel.approveJoinRequest(g.groupId, req) }) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = strings.acceptContactRequest, tint = palette.emerald)
                                }
                                IconButton(onClick = { viewModel.rejectJoinRequest(g.groupId, req) }) {
                                    Icon(Icons.Default.Cancel, contentDescription = strings.rejectContactRequest, tint = palette.error)
                                }
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                viewModel.searchUsers(it)
                            },
                            label = { Text(if (strings.isEnglish) "Add new member" else "إضافة عضو جديد للمجموعة") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = palette.textPrimary,
                                unfocusedTextColor = palette.textPrimary,
                                focusedLabelColor = palette.turquoise,
                                unfocusedLabelColor = palette.textSecondary,
                                focusedBorderColor = palette.turquoise,
                                unfocusedBorderColor = palette.border,
                                cursorColor = palette.turquoise
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    items(searchResults.filter { !g.memberIds.contains(it.uid) }, key = { it.uid }) { user ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.addMember(g.groupId, user.uid)
                                    searchQuery = ""
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WesalNameWithBadge(
                                name = user.displayName.ifBlank { "@${user.publicId}" },
                                isVerified = user.isVerified,
                                verificationType = user.verificationType,
                                neonColor = user.neonColor,
                                neonGlowIntensity = user.neonGlowIntensity,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = palette.emerald)
                        }
                    }
                }

                item {
                    Text(
                        text = if (strings.isEnglish) "Members (${members.size})" else "الأعضاء (${members.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.turquoise
                    )
                }

                items(members, key = { it.uid }) { member ->
                    val memberIsAdmin = g.adminIds.contains(member.uid)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = member.uid != viewModel.myUid) {
                                onOpenUserProfile(member.uid)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        WesalAvatar(
                            imageUrl = member.photoUrl,
                            name = member.displayName,
                            size = 44.dp,
                            frameId = member.frameId,
                            frameExpiresAt = member.frameExpiresAt
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            WesalNameWithBadge(
                                name = member.displayName.ifBlank { "@${member.publicId}" },
                                isVerified = member.isVerified,
                                verificationType = member.verificationType,
                                neonColor = member.neonColor,
                                neonGlowIntensity = member.neonGlowIntensity
                            )
                            if (memberIsAdmin) {
                                Text(
                                    text = if (strings.isEnglish) "Group Admin" else "مشرف المجموعة",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.emerald
                                )
                            }
                        }
                        if (isAdmin && member.uid != viewModel.myUid) {
                            TextButton(onClick = { viewModel.toggleAdmin(g.groupId, member.uid, !memberIsAdmin) }) {
                                Text(
                                    text = if (memberIsAdmin) {
                                        if (strings.isEnglish) "Demote" else "إزالة إشراف"
                                    } else {
                                        if (strings.isEnglish) "Make Admin" else "تعيين مشرف"
                                    },
                                    color = palette.turquoise
                                )
                            }
                            IconButton(onClick = { viewModel.removeMember(g.groupId, member.uid) }) {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = strings.delete, tint = palette.error)
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { viewModel.leaveGroup(g.groupId, onLeftGroup) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.error)
                    ) {
                        Text(if (strings.isEnglish) "Leave Group" else "مغادرة المجموعة")
                    }
                }
            }
        }
    }

    if (showInviteDialog && group != null) {
        val g = group!!
        val payload = "wesal://group?code=${g.inviteCode}"
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            containerColor = palette.surface,
            title = { Text(g.name, color = palette.textPrimary) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (g.joinMode == GroupJoinMode.CLOSED || g.inviteCode.isBlank()) {
                        Text(
                            text = if (strings.isEnglish) "Invites are disabled while group is closed." else "روابط الدعوة معطلة لأن المجموعة مغلقة.",
                            color = palette.error
                        )
                    } else {
                        WesalQrCodeBox(payload = payload, size = 160.dp)
                        Text("${if (strings.isEnglish) "Invite Code" else "رمز الدعوة"}: ${g.inviteCode}", color = palette.emerald, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(g.inviteCode)) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(strings.copy)
                            }
                            Button(
                                onClick = {
                                    WesalShareUtils.shareViaWhatsApp(
                                        context,
                                        "انضم إلى مجموعة «${g.name}» على WESAL Messenger:\n$payload"
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                            ) {
                                Text(strings.share)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInviteDialog = false }) { Text(strings.close) }
            }
        )
    }

    if (showEditDialog && group != null) {
        val g = group!!
        var editName by remember { mutableStateOf(g.name) }
        var editDesc by remember { mutableStateOf(g.description) }
        var newAvatarUri by remember { mutableStateOf<Uri?>(null) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) newAvatarUri = uri
        }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Edit Group Info" else "تعديل بيانات المجموعة", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(if (strings.isEnglish) "Group Name" else "اسم المجموعة") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        label = { Text(if (strings.isEnglish) "Description" else "الوصف") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(
                            text = if (newAvatarUri == null) (if (strings.isEnglish) "Change Group Photo" else "تغيير صورة المجموعة") else "تم اختيار صورة جديدة ✓",
                            color = palette.turquoise
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateGroupInfo(g.groupId, editName, editDesc, newAvatarUri)
                        showEditDialog = false
                    },
                    enabled = editName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                ) {
                    Text(strings.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text(strings.cancel) }
            }
        )
    }
}
