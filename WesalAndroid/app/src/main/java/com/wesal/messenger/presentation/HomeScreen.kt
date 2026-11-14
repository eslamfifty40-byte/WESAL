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
