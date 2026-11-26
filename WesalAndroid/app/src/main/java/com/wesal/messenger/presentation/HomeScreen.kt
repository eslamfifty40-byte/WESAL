package com.wesal.messenger.presentation

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.calls.CallHistoryScreen
import com.wesal.messenger.chat.ChatsListScreen
import com.wesal.messenger.contacts.ContactsInviteActivity
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.groups.GroupsScreen
import com.wesal.messenger.presentation.components.OfflineBanner
import com.wesal.messenger.settings.SettingsScreen
import com.wesal.messenger.settings.WesalSupportTicketsDialog
import com.wesal.messenger.stories.StoriesScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    userRepository: UserRepository,
    prefs: UserPreferencesDataStore
) : ViewModel() {
    val isSuperAdmin: StateFlow<Boolean> = userRepository.observeIsSuperAdmin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasAdminAccess: StateFlow<Boolean> = combine(
        userRepository.observeIsSuperAdmin(),
        userRepository.observeMyAdminPermissions()
    ) { isSuper, perms ->
        isSuper || perms.hasAnyAccess()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val openHiddenChatsTrigger: StateFlow<Boolean> = prefs.openHiddenChatsTrigger
}

private enum class HomeTab(
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    CHATS(Icons.Default.Chat),
    STORIES(Icons.Default.AutoAwesome),
    CALLS(Icons.Default.Call),
    GROUPS(Icons.Default.Groups),
    SETTINGS(Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    isOnline: Boolean,
    onOpenChat: (String) -> Unit,
    onOpenNewChat: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAccountShare: () -> Unit = {},
    onOpenCameraHub: () -> Unit = {},
    onOpenChannel: (String) -> Unit = {},
    onOpenAdminVerification: () -> Unit = {},
    onOpenAddAccount: (String?) -> Unit = {},
    onAccountSwitched: (Boolean) -> Unit = {},
    onSignedOut: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val hasAdminAccess by homeViewModel.hasAdminAccess.collectAsStateWithLifecycle()
    val openHiddenChatsTrigger by homeViewModel.openHiddenChatsTrigger.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.CHATS) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }
    val ctxHome = LocalContext.current

    LaunchedEffect(openHiddenChatsTrigger) {
        if (openHiddenChatsTrigger) {
            selectedTab = HomeTab.CHATS
        }
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = strings.appShortName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary
                        )
                    },
                    actions = {
                        if (hasAdminAccess) {
                            IconButton(onClick = onOpenAdminVerification) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = strings.adminVerificationTitle,
                                    tint = palette.verifiedBlue
                                )
                            }
                        }
                        IconButton(onClick = onOpenCameraHub) {
                            Icon(Icons.Default.CameraAlt, contentDescription = strings.camera, tint = palette.iconTint)
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = strings.more, tint = palette.iconTint)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(strings.newGroup) },
                                    onClick = { menuOpen = false; selectedTab = HomeTab.GROUPS }
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.tabChannels) },
                                    onClick = { menuOpen = false; selectedTab = HomeTab.STORIES }
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.newMessage) },
                                    onClick = { menuOpen = false; onOpenNewChat() }
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.inviteFriends) },
                                    onClick = {
                                        menuOpen = false
                                        ctxHome.startActivity(Intent(ctxHome, ContactsInviteActivity::class.java))
                                    }
                                )
                                if (hasAdminAccess) {
                                    DropdownMenuItem(
                                        text = { Text(strings.adminVerificationTitle, color = palette.verifiedBlue) },
                                        onClick = { menuOpen = false; onOpenAdminVerification() }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(strings.supportHelpTitle) },
                                    onClick = { menuOpen = false; showSupportDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.settings) },
                                    onClick = { menuOpen = false; selectedTab = HomeTab.SETTINGS }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
                )
                OfflineBanner(isOnline = isOnline)
                val diagRing by com.wesal.messenger.chat.WesalDiag.ring.collectAsStateWithLifecycle()
                if (diagRing.isNotBlank()) {
                    val ringCtx = androidx.compose.ui.platform.LocalContext.current
                    Text(
                        text = diagRing + "  (اضغط للمشاركة)",
                        color = Color(0xFFBBBBBB),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF222222))
                            .combinedClickable(
                                onClick = {
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, "v${com.wesal.messenger.BuildConfig.BUILD_STAMP}\n" + diagRing)
                                    }
                                    runCatching {
                                        ringCtx.startActivity(
                                            android.content.Intent.createChooser(send, "مشاركة التشخيص")
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                                onLongClick = { com.wesal.messenger.chat.WesalDiag.ring.value = "" }
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = palette.surface) {
                HomeTab.entries.forEach { tab ->
                    val label = when (tab) {
                        HomeTab.CHATS -> strings.tabChats
                        HomeTab.STORIES -> strings.tabStories
                        HomeTab.CALLS -> strings.tabCalls
                        HomeTab.GROUPS -> strings.tabGroups
                        HomeTab.SETTINGS -> strings.tabSettings
                    }
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = label) },
                        label = { Text(label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.iconTint,
                            selectedTextColor = palette.iconTint,
                            indicatorColor = palette.emerald.copy(alpha = 0.16f),
                            unselectedIconColor = palette.textSecondary,
                            unselectedTextColor = palette.textSecondary
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                HomeTab.CHATS -> ChatsListScreen(
                    onOpenChat = onOpenChat,
                    onNewChat = onOpenNewChat
                )
                HomeTab.STORIES -> StoriesScreen(
                    onOpenChannel = onOpenChannel,
                    onOpenChat = onOpenChat
                )
                HomeTab.CALLS -> CallHistoryScreen()
                HomeTab.GROUPS -> GroupsScreen(
                    onOpenChat = onOpenChat,
                    onOpenChannel = onOpenChannel
                )
                HomeTab.SETTINGS -> SettingsScreen(
                    onOpenProfile = onOpenProfile,
                    onOpenAccountShare = onOpenAccountShare,
                    onOpenAdminPanel = onOpenAdminVerification,
                    onOpenChat = onOpenChat,
                    onOpenHiddenChatsTab = { selectedTab = HomeTab.CHATS },
                    onSignedOut = onSignedOut,
                    onOpenAddAccount = onOpenAddAccount,
                    onAccountSwitched = onAccountSwitched
                )
            }
        }
    }

    if (showSupportDialog) {
        WesalSupportTicketsDialog(
            onDismiss = { showSupportDialog = false }
        )
    }
}
