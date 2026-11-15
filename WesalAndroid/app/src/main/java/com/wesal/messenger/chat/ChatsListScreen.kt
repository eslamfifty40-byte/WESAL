package com.wesal.messenger.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.presentation.components.MessageStatusTick
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge

@Composable
fun ChatsListScreen(
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    viewModel: ChatsListViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val showArchived by viewModel.showArchived.collectAsStateWithLifecycle()
    val archivedCount by viewModel.archivedCount.collectAsStateWithLifecycle()
    var selectedChatForMenu by remember { mutableStateOf<ChatPreview?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                placeholder = {
                    Text(if (strings.isEnglish) "Search chats..." else "ابحث في المحادثات...")
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.textSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = strings.clear)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.turquoise,
                    unfocusedBorderColor = palette.border,
                    focusedContainerColor = palette.surface,
                    unfocusedContainerColor = palette.surface
                )
            )

            if (archivedCount > 0 || showArchived) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleShowArchived() }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, tint = palette.emerald)
                        Text(
                            text = if (showArchived) {
                                if (strings.isEnglish) "Back to Active Chats" else "العودة للمحادثات النشطة"
                            } else {
                                if (strings.isEnglish) "Archived" else "المؤرشفة"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary
                        )
                    }
                    if (!showArchived) {
                        Text(text = archivedCount.toString(), color = palette.emerald, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(color = palette.border, thickness = 0.5.dp)
            }

            if (chats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            tint = palette.turquoise.copy(alpha = 0.6f),
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = if (showArchived) {
                                if (strings.isEnglish) "No archived chats" else "لا توجد محادثات مؤرشفة"
                            } else {
                                if (strings.isEnglish) "No chats yet" else "لا توجد محادثات حتى الآن"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary
                        )
                        Text(
                            text = if (strings.isEnglish) {
                                "Tap + to start a chat via @Wesal ID or approved contacts"
                            } else {
                                "اضغط على زر + لبدء محادثة جديدة عبر معرف وصال (@Wesal ID) أو جهات الاتصال"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(chats, key = { it.chatId }) { chat ->
                        ChatRowItem(
                            chat = chat,
                            myUid = viewModel.currentUid,
                            onClick = { onOpenChat(chat.chatId) },
                            onLongClick = { selectedChatForMenu = chat }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onNewChat,
            containerColor = palette.emerald,
            contentColor = palette.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.AddComment, contentDescription = strings.newMessage)
        }
    }

    selectedChatForMenu?.let { chat ->
        AlertDialog(
            onDismissRequest = { selectedChatForMenu = null },
            containerColor = palette.surface,
            title = { WesalNameWithBadge(name = chat.title, isVerified = chat.peerVerified) },
            text = {
                Column {
                    ListItem(
                        headlineContent = {
                            Text(
                                if (chat.isPinned) {
                                    if (strings.isEnglish) "Unpin" else "إلغاء التثبيت"
                                } else {
                                    if (strings.isEnglish) "Pin Chat" else "تثبيت المحادثة"
                                }
                            )
                        },
                        leadingContent = { Icon(Icons.Default.PushPin, contentDescription = null, tint = palette.turquoise) },
                        modifier = Modifier.clickable {
                            viewModel.togglePin(chat)
                            selectedChatForMenu = null
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                if (chat.isArchived) {
                                    if (strings.isEnglish) "Unarchive" else "إلغاء الأرشفة"
                                } else {
                                    if (strings.isEnglish) "Archive Chat" else "أرشفة المحادثة"
                                }
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Archive, contentDescription = null, tint = palette.emerald) },
                        modifier = Modifier.clickable {
                            viewModel.toggleArchive(chat)
                            selectedChatForMenu = null
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                if (chat.isMuted) {
                                    if (strings.isEnglish) "Unmute" else "إلغاء الكتم"
                                } else {
                                    if (strings.isEnglish) "Mute Notifications" else "كتم الإشعارات"
                                }
                            )
                        },
                        leadingContent = { Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = palette.textSecondary) },
                        modifier = Modifier.clickable {
                            viewModel.toggleMute(chat)
                            selectedChatForMenu = null
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(if (strings.isEnglish) "Delete Chat" else "حذف المحادثة", color = palette.error)
                        },
                        leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = palette.error) },
                        modifier = Modifier.clickable {
                            viewModel.deleteChat(chat)
                            selectedChatForMenu = null
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedChatForMenu = null }) { Text(strings.close) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRowItem(
    chat: ChatPreview,
    myUid: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        WesalAvatar(
            imageUrl = chat.avatarUrl,
            name = chat.title,
            size = 54.dp,
            isOnline = !chat.isGroup && chat.peerOnline
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    WesalNameWithBadge(
                        name = chat.title,
                        isVerified = !chat.isGroup && chat.peerVerified,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (chat.isPinned) {
                        Icon(Icons.Default.PushPin, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(14.dp))
                    }
                    if (chat.isMuted) {
                        Icon(Icons.Default.VolumeOff, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(14.dp))
                    }
                }
                Text(
                    text = DateTimeUtils.formatChatListTime(chat.lastMessageTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chat.unreadCount > 0) palette.emerald else palette.textSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (chat.typingUsers.isNotEmpty()) {
                        Text(
                            text = strings.typingNow,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.emerald,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        if (chat.lastMessageSenderId == myUid && chat.lastMessagePreview.isNotBlank()) {
                            MessageStatusTick(status = chat.lastMessageStatus)
                        }
                        Text(
                            text = chat.lastMessagePreview.ifBlank {
                                if (strings.isEnglish) "Tap to start chatting" else "اضغط لبدء المحادثة"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (chat.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                            .clip(CircleShape)
                            .background(palette.emerald)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = chat.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
