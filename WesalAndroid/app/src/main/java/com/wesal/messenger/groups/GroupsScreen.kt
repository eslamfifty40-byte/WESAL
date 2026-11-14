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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.domain.model.GroupInfo
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.GroupRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val userRepository: UserRepository,
    authRepository: AuthRepository
) : ViewModel() {
    val myUid = authRepository.currentUserId.orEmpty()
    val groups: StateFlow<List<GroupInfo>> = groupRepository.observeUserGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchedUsers = MutableStateFlow<List<WesalUser>>(emptyList())
    val selectedMembers = MutableStateFlow<List<WesalUser>>(emptyList())
    val isCreating = MutableStateFlow(false)

    fun searchUsers(query: String) {
        viewModelScope.launch {
            searchedUsers.value = if (query.isBlank()) emptyList() else userRepository.searchUsers(query)
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
    viewModel: GroupsViewModel = hiltViewModel()
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val searched by viewModel.searchedUsers.collectAsStateWithLifecycle()
    val selectedMembers by viewModel.selectedMembers.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(WesalDeepBlack)) {
        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا توجد مجموعات حتى الآن — أنشئ مجموعة جديدة", color = WesalTextSecondary)
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
                            Text(group.name, style = MaterialTheme.typography.titleMedium, color = WesalTextPrimary)
                            Text(
                                text = "${group.memberIds.size} عضو • ${group.description.ifBlank { "مجموعة وصال" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WesalTextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                    HorizontalDivider(color = WesalSurfaceBorder, thickness = 0.5.dp)
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreateDialog = true },
            containerColor = WesalEmerald,
            contentColor = WesalDeepBlack,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.GroupAdd, contentDescription = "إنشاء مجموعة")
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
            containerColor = WesalSurfaceDark,
            title = { Text("إنشاء مجموعة جديدة", color = WesalTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(WesalSurfaceElevated)
                                .clickable {
                                    avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (groupAvatarUri != null) {
                                AsyncImage(model = groupAvatarUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = WesalTurquoise)
                            }
                        }
                        OutlinedTextField(
                            value = groupName,
                            onValueChange = { groupName = it },
                            label = { Text("اسم المجموعة") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = groupDesc,
                        onValueChange = { groupDesc = it },
                        label = { Text("وصف المجموعة (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = memberQuery,
                        onValueChange = {
                            memberQuery = it
                            viewModel.searchUsers(it)
                        },
                        label = { Text("إضافة أعضاء (ابحث بالاسم أو الهاتف)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (selectedMembers.isNotEmpty()) {
                        Text(
                            text = "الأعضاء المحددون: ${selectedMembers.joinToString("، ") { it.displayName.ifBlank { it.phoneNumber } }}",
                            style = MaterialTheme.typography.labelMedium,
                            color = WesalEmerald
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
                                Text(user.displayName.ifBlank { user.phoneNumber }, color = WesalTextPrimary)
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
                    colors = ButtonDefaults.buttonColors(containerColor = WesalEmerald, contentColor = WesalDeepBlack)
                ) {
                    Text(if (isCreating) "جاري الإنشاء..." else "إنشاء")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("إلغاء") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    groupId: String,
    onBack: () -> Unit,
    onLeftGroup: () -> Unit,
    viewModel: GroupsViewModel = hiltViewModel()
) {
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
        containerColor = WesalDeepBlack,
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: "معلومات المجموعة", color = WesalTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = WesalTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WesalSurfaceDark)
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
                        Text(g.name, style = MaterialTheme.typography.headlineSmall, color = WesalTextPrimary)
                        if (g.description.isNotBlank()) {
                            Text(g.description, style = MaterialTheme.typography.bodyMedium, color = WesalTextSecondary)
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
                            label = { Text("إضافة عضو جديد للمجموعة") },
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
                            Text(user.displayName.ifBlank { user.phoneNumber }, color = WesalTextPrimary)
                            Icon(Icons.Default.PersonAdd, contentDescription = "إضافة", tint = WesalEmerald)
                        }
                    }
                }

                item {
                    Text("الأعضاء (${members.size})", style = MaterialTheme.typography.titleMedium, color = WesalTurquoise)
                }

                items(members, key = { it.uid }) { member ->
                    val memberIsAdmin = g.adminIds.contains(member.uid)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        WesalAvatar(imageUrl = member.photoUrl, name = member.displayName, size = 44.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(member.displayName.ifBlank { member.phoneNumber }, color = WesalTextPrimary)
                            if (memberIsAdmin) {
                                Text("مشرف المجموعة", style = MaterialTheme.typography.labelSmall, color = WesalEmerald)
                            }
                        }
                        if (isAdmin && member.uid != viewModel.myUid) {
                            TextButton(onClick = { viewModel.toggleAdmin(g.groupId, member.uid, !memberIsAdmin) }) {
                                Text(if (memberIsAdmin) "إزالة إشراف" else "تعيين مشرف", color = WesalTurquoise)
                            }
                            IconButton(onClick = { viewModel.removeMember(g.groupId, member.uid) }) {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = "إزالة", tint = WesalError)
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { viewModel.leaveGroup(g.groupId, onLeftGroup) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WesalError)
                    ) {
                        Text("مغادرة المجموعة")
                    }
                }
            }
        }
    }
}
