package com.wesal.messenger.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.wesal.messenger.calls.CallHistoryScreen
import com.wesal.messenger.chat.ChatsListScreen
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.groups.GroupsScreen
import com.wesal.messenger.presentation.components.OfflineBanner
import com.wesal.messenger.settings.SettingsScreen
import com.wesal.messenger.stories.StoriesScreen

private enum class HomeTab(
    val labelAr: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    CHATS("المحادثات", Icons.Default.Chat),
    STORIES("الحالات", Icons.Default.AutoAwesome),
    CALLS("المكالمات", Icons.Default.Call),
    GROUPS("المجموعات", Icons.Default.Groups),
    SETTINGS("الإعدادات", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isOnline: Boolean,
    onOpenChat: (String) -> Unit,
    onOpenNewChat: () -> Unit,
    onSignedOut: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(HomeTab.CHATS) }
    var menuOpen by remember { mutableStateOf(false) }
    val ctxHome = androidx.compose.ui.platform.LocalContext.current
    // SWITCHMENU
    com.wesal.messenger.settings.AccountSwitcherDialog(onSignOut = { com.google.firebase.auth.FirebaseAuth.getInstance().signOut(); onSignedOut() })

    Scaffold(
        containerColor = WesalDeepBlack,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "وصال",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = WesalTextPrimary
                        )
                    },
                    actions = {
                        IconButton(onClick = { selectedTab = HomeTab.STORIES }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "كاميرا", tint = WesalTextPrimary)
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "المزيد", tint = WesalTextPrimary)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("مجموعة جديدة") }, onClick = { menuOpen = false; selectedTab = HomeTab.GROUPS })
                                DropdownMenuItem(text = { Text("رسالة جديدة") }, onClick = { menuOpen = false; onOpenNewChat() })
                                DropdownMenuItem(text = { Text("دعوة الأصدقاء") }, onClick = {
                                    menuOpen = false
                                    ctxHome.startActivity(android.content.Intent(ctxHome, com.wesal.messenger.contacts.ContactsInviteActivity::class.java))
                                })
                                DropdownMenuItem(text = { Text("\u062a\u0628\u062f\u064a\u0644 \u0627\u0644\u062d\u0633\u0627\u0628") }, onClick = { menuOpen = false; com.wesal.messenger.settings.AccountSwitcherState.show = true })
                        if (selectedTab == HomeTab.CALLS) DropdownMenuItem(text = { Text("\u0645\u0633\u062d \u0627\u0644\u0645\u0643\u0627\u0644\u0645\u0627\u062a") }, onClick = { menuOpen = false; com.wesal.messenger.calls.CallsClearState.clear(ctxHome) }) // CLEARMENU
                        DropdownMenuItem(text = { Text("الإعدادات") }, onClick = { menuOpen = false; selectedTab = HomeTab.SETTINGS })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = WesalSurfaceDark)
                )
                OfflineBanner(isOnline = isOnline)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = WesalSurfaceDark) {
                HomeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.labelAr) },
                        label = { Text(tab.labelAr, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = WesalTurquoise,
                            selectedTextColor = WesalTurquoise,
                            indicatorColor = WesalEmerald.copy(alpha = 0.16f),
                            unselectedIconColor = WesalTextSecondary,
                            unselectedTextColor = WesalTextSecondary
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
                HomeTab.STORIES -> StoriesScreen()
                HomeTab.CALLS -> CallHistoryScreen()
                HomeTab.GROUPS -> GroupsScreen(onOpenChat = onOpenChat)
                HomeTab.SETTINGS -> SettingsScreen(onSignedOut = onSignedOut)
            }
        }
    }
}
