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
import com.wesal.messenger.domain.model.GroupInfo
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.GroupRepository
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
    private var searchJob: Job? = null

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

    fun createGroup(name: String, description: String, avatarUri: Uri?, onCreated: (String) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            isCreating.value = true
            groupRepository.createGroup(
                name = name,
                description = description,
                memberIds = selectedMembers.value.map { it.uid },
                avatarUri = avatarUri
            ).onSuccess { group ->
                selectedMembers.value = emptyList()
                onCreated(group.chatId)
            }
            isCreating.value = false
        }
    }

    fun observeGroup(groupId: String): Flow<GroupInfo?> = groupRepository.observeGroup(groupId)

    suspend fun loadUsersByIds(uids: List<String>): List<WesalUser> =
        uids.mapNotNull { userRepository.getUserOnce(it) }

    fun updateGroupInfo(groupId: String, name: String, desc: String, avatarUri: Uri?) {
        viewModelScope.launch { groupRepository.updateGroupInfo(groupId, name, desc, avatarUri) }
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

    var selectedSegment by rememberSaveable { mutableIntStateOf(0) } // 0 = Groups, 1 = Channels
    var showCreateDialog by remember { mutableStateOf(false) }

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
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
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
                                    Text(group.name, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary, fontWeight = FontWeight.SemiBold)
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

    if (showCreateDialog) {
        var groupName by remember { mutableStateOf("") }
        var groupDesc by remember { mutableStateOf("") }
        var memberQuery by remember { mutableStateOf("") }
        var groupAvatarUri by remember { mutableStateOf<Uri?>(null) }

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

                    LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
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
                        viewModel.createGroup(groupName, groupDesc, groupAvatarUri) { chatId ->
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
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val group by viewModel.observeGroup(groupId).collectAsStateWithLifecycle(initialValue = null)
    var members by remember { mutableStateOf<List<WesalUser>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchedUsers.collectAsStateWithLifecycle()

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
                    }
                }

                if (isAdmin) {
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
                        WesalAvatar(imageUrl = member.photoUrl, name = member.displayName, size = 44.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            WesalNameWithBadge(
                                name = member.displayName.ifBlank { "@${member.publicId}" },
                                isVerified = member.isVerified
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
}
