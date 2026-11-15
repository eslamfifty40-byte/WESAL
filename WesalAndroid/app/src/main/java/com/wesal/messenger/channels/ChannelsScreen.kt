package com.wesal.messenger.channels

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.domain.model.ChannelRole
import com.wesal.messenger.domain.model.WesalChannel
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge

@Composable
fun ChannelsScreen(
    onOpenChannel: (String) -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val myChannels by viewModel.myChannels.collectAsStateWithLifecycle()
    val publicChannels by viewModel.publicChannels.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showInviteCodeDialog by remember { mutableStateOf(false) }

    val filteredMy = remember(myChannels, searchQuery) {
        if (searchQuery.isBlank()) myChannels
        else myChannels.filter {
            it.title.contains(searchQuery, true) || it.description.contains(searchQuery, true)
        }
    }
    val myIds = remember(myChannels) { myChannels.map { it.channelId }.toSet() }
    val filteredPublic = remember(publicChannels, searchQuery, myIds) {
        publicChannels
            .filterNot { it.channelId in myIds }
            .filter {
                searchQuery.isBlank() ||
                    it.title.contains(searchQuery, true) ||
                    it.description.contains(searchQuery, true)
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 88.dp, start = 12.dp, end = 12.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::updateSearch,
                        placeholder = { Text(if (strings.isEnglish) "Search channels..." else "ابحث في القنوات...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(onClick = { showInviteCodeDialog = true }) {
                        Icon(Icons.Default.QrCode2, contentDescription = strings.requestToJoin)
                    }
                }
            }

            message?.let { msg ->
                item {
                    Surface(
                        color = palette.emerald.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.clearMessage() }
                    ) {
                        Text(
                            text = msg,
                            color = palette.emerald,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            if (filteredMy.isNotEmpty()) {
                item {
                    Text(
                        text = if (strings.isEnglish) "My Channels" else "قنواتي المشترك بها",
                        color = palette.emerald,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                items(filteredMy, key = { "my_${it.channelId}" }) { channel ->
                    ChannelDirectoryCard(
                        channel = channel,
                        myUid = viewModel.myUid,
                        onOpen = { onOpenChannel(channel.channelId) },
                        onSubscribe = { viewModel.subscribe(channel.channelId) }
                    )
                }
            }

            item {
                Text(
                    text = if (strings.isEnglish) "Discover Public Channels" else "استكشاف القنوات العامة",
                    color = palette.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            if (filteredPublic.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Campaign, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(36.dp))
                            Text(
                                text = if (strings.isEnglish) "No additional public channels yet" else "لا توجد قنوات عامة أخرى حالياً",
                                color = palette.textSecondary
                            )
                        }
                    }
                }
            } else {
                items(filteredPublic, key = { "pub_${it.channelId}" }) { channel ->
                    ChannelDirectoryCard(
                        channel = channel,
                        myUid = viewModel.myUid,
                        onOpen = { onOpenChannel(channel.channelId) },
                        onSubscribe = { viewModel.subscribe(channel.channelId) }
                    )
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
            Icon(Icons.Default.Add, contentDescription = strings.createChannel)
        }
    }

    if (showCreateDialog) {
        CreateChannelDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, desc, isPublic, avatarUri ->
                viewModel.createChannel(title, desc, isPublic, avatarUri) { newChannelId ->
                    showCreateDialog = false
                    onOpenChannel(newChannelId)
                }
            }
        )
    }

    if (showInviteCodeDialog) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showInviteCodeDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Join Channel by Code" else "الانضمام برمز قناة",
                    color = palette.textPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(if (strings.isEnglish) "Invite Code" else "رمز الدعوة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.joinByInviteCode(code) { chId ->
                            showInviteCodeDialog = false
                            onOpenChannel(chId)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald)
                ) {
                    Text(strings.confirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showInviteCodeDialog = false }) { Text(strings.cancel) }
            }
        )
    }
}

@Composable
private fun ChannelDirectoryCard(
    channel: WesalChannel,
    myUid: String,
    onOpen: () -> Unit,
    onSubscribe: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val role = channel.roleFor(myUid)

    Card(
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WesalAvatar(
                imageUrl = channel.avatarUrl,
                name = channel.title,
                size = 50.dp
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WesalNameWithBadge(
                        name = channel.title,
                        isVerified = channel.isVerified,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        imageVector = if (channel.isPublic) Icons.Default.Public else Icons.Default.Lock,
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (channel.description.isNotBlank()) {
                    Text(
                        text = channel.description,
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (strings.isEnglish) {
                        "${channel.subscribersCount} subscribers"
                    } else {
                        "${channel.subscribersCount} مشترك"
                    },
                    color = palette.textMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (role == ChannelRole.NONE && channel.isPublic) {
                FilledTonalButton(
                    onClick = onSubscribe,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = palette.emerald.copy(alpha = 0.16f),
                        contentColor = palette.emerald
                    )
                ) {
                    Text(strings.subscribeChannel, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CreateChannelDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, description: String, isPublic: Boolean, avatarUri: Uri?) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(true) }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) avatarUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text(strings.createChannel, color = palette.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (strings.isEnglish) "Channel Title" else "اسم القناة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(if (strings.isEnglish) "Description" else "وصف القناة") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (strings.isEnglish) {
                            if (isPublic) "Public Channel" else "Private Channel (Approval Required)"
                        } else {
                            if (isPublic) "قناة عامة" else "قناة خاصة (بموافقة المشرفين)"
                        },
                        color = palette.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
                TextButton(onClick = { picker.launch("image/*") }) {
                    Text(
                        text = if (avatarUri == null) {
                            if (strings.isEnglish) "Choose Channel Photo (Optional)" else "اختيار صورة للقناة (اختياري)"
                        } else {
                            if (strings.isEnglish) "Photo selected ✓" else "تم اختيار الصورة ✓"
                        },
                        color = palette.turquoise
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title, description, isPublic, avatarUri) },
                enabled = title.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald)
            ) {
                Text(strings.createChannel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}
