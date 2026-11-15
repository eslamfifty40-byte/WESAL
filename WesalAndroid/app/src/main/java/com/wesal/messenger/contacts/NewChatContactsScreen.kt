package com.wesal.messenger.contacts

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.wesal.messenger.calls.CallActivity
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.presentation.components.WesalAvatar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun NewChatContactsScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenUserProfile: (String) -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val contactsPermission = rememberPermissionState(Manifest.permission.READ_CONTACTS)

    LaunchedEffect(contactsPermission.status.isGranted) {
        if (contactsPermission.status.isGranted) {
            viewModel.syncDeviceContacts()
        }
    }

    val trimmedQuery = state.query.trim()
    val cleanQueryHandle = trimmedQuery.removePrefix("@")
    val filteredWesalContacts = remember(state.wesalContacts, trimmedQuery) {
        if (trimmedQuery.isBlank()) state.wesalContacts
        else state.wesalContacts.filter {
            it.deviceName.contains(trimmedQuery, ignoreCase = true) ||
                it.user.displayName.contains(trimmedQuery, ignoreCase = true) ||
                it.user.phoneNumber.contains(trimmedQuery) ||
                it.user.publicId.contains(cleanQueryHandle, ignoreCase = true)
        }
    }
    val filteredNonWesalContacts = remember(state.nonWesalContacts, trimmedQuery) {
        if (trimmedQuery.isBlank()) state.nonWesalContacts
        else state.nonWesalContacts.filter {
            it.displayName.contains(trimmedQuery, ignoreCase = true) ||
                it.rawPhone.contains(trimmedQuery)
        }
    }
    val deviceMatchedUids = remember(state.wesalContacts) {
        state.wesalContacts.map { it.user.uid }.toSet()
    }
    val extraGlobalResults = remember(state.searchResults, deviceMatchedUids) {
        state.searchResults.filterNot { it.uid in deviceMatchedUids }
    }
    val discoverableUsersList = remember(state.discoverableUsers, deviceMatchedUids) {
        state.discoverableUsers.filterNot { it.uid in deviceMatchedUids }
    }

    Scaffold(
        containerColor = WesalDeepBlack,
        topBar = {
            TopAppBar(
                title = { Text("الأصدقاء وجهات الاتصال", color = WesalTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = WesalTextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.refreshDiscoverableUsers()
                        if (contactsPermission.status.isGranted) {
                            viewModel.syncDeviceContacts()
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث جهات الاتصال", tint = WesalTurquoise)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WesalSurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::search,
                placeholder = { Text("ابحث بمعرف وصال (@Wesal ID) أو الاسم...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = WesalTurquoise) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.search("") }) {
                            Icon(Icons.Default.Close, contentDescription = "مسح")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            if (state.isLoadingContacts || state.isSearching || state.isLoadingDiscoverable) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = WesalEmerald)
            }

            state.error?.let { err ->
                Text(err, color = WesalError, style = MaterialTheme.typography.bodyMedium)
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // My Wesal ID & Quick WhatsApp Invite Card
                currentUser?.takeIf { it.publicId.isNotBlank() }?.let { me ->
                    item(key = "my_wesal_id_card") {
                        Surface(
                            color = WesalSurfaceDark,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, WesalTurquoise.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "معرف وصال الخاص بك (Wesal ID)",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = WesalTextSecondary
                                        )
                                        Text(
                                            text = "@${me.publicId}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = WesalTurquoise,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString("@${me.publicId}"))
                                            Toast.makeText(context, "تم نسخ @${me.publicId}", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "نسخ معرف وصال", tint = WesalTurquoise)
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            WesalShareUtils.shareViaWhatsApp(
                                                context,
                                                WesalShareUtils.buildInviteMessage(me.publicId)
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = WesalEmerald,
                                            contentColor = WesalDeepBlack
                                        )
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("دعوة عبر واتساب", fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            WesalShareUtils.shareGeneral(
                                                context,
                                                WesalShareUtils.buildInviteMessage(me.publicId)
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.IosShare, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("مشاركة الرابط", color = WesalTurquoise)
                                    }
                                }
                            }
                        }
                    }
                }

                // Contacts Permission Consent Card (strictly optional & privacy-preserving SHA-256)
                if (!contactsPermission.status.isGranted) {
                    item(key = "contacts_consent_card") {
                        Surface(
                            color = WesalSurfaceDark,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, WesalSurfaceBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Default.Contacts, contentDescription = null, tint = WesalTurquoise)
                                    Text(
                                        text = "مطابقة جهات الاتصال الآمنة (اختياري)",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = WesalTextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = "بعد موافقتك، يطابق وصال أرقام هاتفك محلياً عبر SHA-256 دون رفع دفتر الهاتف، ولا يعرض علامة «يستخدم وصال» إلا للحسابات التي تم التأكد من ملكيتها للرقم.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WesalTextSecondary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { contactsPermission.launchPermissionRequest() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = WesalEmerald,
                                            contentColor = WesalDeepBlack
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("السماح بمطابقة جهات الاتصال", fontWeight = FontWeight.Bold)
                                    }
                                    if (!contactsPermission.status.shouldShowRationale) {
                                        OutlinedButton(
                                            onClick = {
                                                val intent = Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.fromParts("package", context.packageName, null)
                                                )
                                                runCatching { context.startActivity(intent) }
                                            },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("إعدادات الصلاحيات", color = WesalTurquoise)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Matched Device Contacts (ONLY verified phone owners appear here)
                if (filteredWesalContacts.isNotEmpty()) {
                    item(key = "header_verified_contacts") {
                        Text(
                            text = "جهات اتصالك الموثقة على وصال (${filteredWesalContacts.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = WesalTurquoise,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }
                    items(filteredWesalContacts, key = { "wesal_${it.user.uid}" }) { matched ->
                        val user = matched.user
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.startChatWith(user.uid, onOpenChat) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(modifier = Modifier.clickable { onOpenUserProfile(user.uid) }) {
                                WesalAvatar(imageUrl = user.photoUrl, name = matched.deviceName, isOnline = user.isOnline)
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = matched.deviceName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = WesalTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    // Strict rule: Show "يستخدم وصال" badge ONLY for verified phone ownership
                                    if (user.isPhoneVerified) {
                                        Surface(
                                            color = WesalEmerald.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, WesalEmerald.copy(alpha = 0.45f))
                                        ) {
                                            Text(
                                                text = "يستخدم وصال ✓",
                                                color = WesalEmerald,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "${user.displayName.ifBlank { user.phoneNumber }} • @${user.publicId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WesalTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { viewModel.startChatWith(user.uid, onOpenChat) }) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "بدء محادثة", tint = WesalEmerald)
                            }
                            IconButton(onClick = {
                                context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo = false))
                            }) {
                                Icon(Icons.Default.Call, contentDescription = "مكالمة صوتية", tint = WesalTurquoise)
                            }
                            IconButton(onClick = {
                                context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo = true))
                            }) {
                                Icon(Icons.Default.Videocam, contentDescription = "مكالمة فيديو", tint = WesalTurquoise)
                            }
                        }
                        HorizontalDivider(color = WesalSurfaceBorder, thickness = 0.5.dp)
                    }
                }

                // Search Results by Wesal ID / Display Name
                if (extraGlobalResults.isNotEmpty()) {
                    item(key = "header_global_search") {
                        Text(
                            text = "نتائج البحث بمعرف وصال والاسم",
                            style = MaterialTheme.typography.titleSmall,
                            color = WesalEmerald,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(extraGlobalResults, key = { "search_${it.uid}" }) { user ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.startChatWith(user.uid, onOpenChat) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(modifier = Modifier.clickable { onOpenUserProfile(user.uid) }) {
                                WesalAvatar(imageUrl = user.photoUrl, name = user.displayName, isOnline = user.isOnline)
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = user.displayName.ifBlank { "@${user.publicId}" },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = WesalTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (user.isPhoneVerified) {
                                        Surface(
                                            color = WesalEmerald.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "رقم موثق ✓",
                                                color = WesalEmerald,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "@${user.publicId} • ${user.bio}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WesalTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { viewModel.startChatWith(user.uid, onOpenChat) }) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "بدء محادثة", tint = WesalEmerald)
                            }
                            IconButton(onClick = {
                                context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo = false))
                            }) {
                                Icon(Icons.Default.Call, contentDescription = "مكالمة صوتية", tint = WesalTurquoise)
                            }
                            IconButton(onClick = {
                                context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo = true))
                            }) {
                                Icon(Icons.Default.Videocam, contentDescription = "مكالمة فيديو", tint = WesalTurquoise)
                            }
                        }
                        HorizontalDivider(color = WesalSurfaceBorder, thickness = 0.5.dp)
                    }
                }

                // Discoverable Wesal Users (@Wesal ID) visible even before searching
                if (trimmedQuery.isBlank() && discoverableUsersList.isNotEmpty()) {
                    item(key = "header_discoverable_users") {
                        Text(
                            text = "مستخدمو وصال (@Wesal ID)",
                            style = MaterialTheme.typography.titleSmall,
                            color = WesalTurquoise,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(discoverableUsersList, key = { "discover_${it.uid}" }) { user ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.startChatWith(user.uid, onOpenChat) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(modifier = Modifier.clickable { onOpenUserProfile(user.uid) }) {
                                WesalAvatar(imageUrl = user.photoUrl, name = user.displayName, isOnline = user.isOnline)
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = user.displayName.ifBlank { "@${user.publicId}" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = WesalTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = listOfNotNull(
                                        user.publicId.takeIf { it.isNotBlank() }?.let { "@$it" },
                                        user.email.takeIf { it.isNotBlank() },
                                        user.bio.takeIf { it.isNotBlank() }
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WesalTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { viewModel.startChatWith(user.uid, onOpenChat) }) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "بدء محادثة", tint = WesalEmerald)
                            }
                            IconButton(onClick = {
                                context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo = false))
                            }) {
                                Icon(Icons.Default.Call, contentDescription = "مكالمة صوتية", tint = WesalTurquoise)
                            }
                            IconButton(onClick = {
                                context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo = true))
                            }) {
                                Icon(Icons.Default.Videocam, contentDescription = "مكالمة فيديو", tint = WesalTurquoise)
                            }
                        }
                        HorizontalDivider(color = WesalSurfaceBorder, thickness = 0.5.dp)
                    }
                }

                // Non-matched Contacts -> Invite via WhatsApp or Other Apps
                if (filteredNonWesalContacts.isNotEmpty()) {
                    item(key = "header_invite_contacts") {
                        Text(
                            text = "دعوة إلى وصال (${filteredNonWesalContacts.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = WesalTextSecondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    items(filteredNonWesalContacts, key = { "invite_${it.contactId}_${it.rawPhone}" }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            WesalAvatar(imageUrl = "", name = contact.displayName, size = 44.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = WesalTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = contact.e164Phone ?: contact.rawPhone,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WesalTextSecondary
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    WesalShareUtils.shareViaWhatsApp(
                                        context = context,
                                        message = WesalShareUtils.buildInviteMessage(
                                            myWesalId = currentUser?.publicId,
                                            targetContactName = contact.displayName
                                        ),
                                        targetPhoneE164 = contact.e164Phone
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = WesalEmerald.copy(alpha = 0.16f),
                                    contentColor = WesalEmerald
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("واتساب", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(
                                onClick = {
                                    WesalShareUtils.shareGeneral(
                                        context = context,
                                        message = WesalShareUtils.buildInviteMessage(
                                            myWesalId = currentUser?.publicId,
                                            targetContactName = contact.displayName
                                        )
                                    )
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "مشاركة عبر تطبيق آخر", tint = WesalTurquoise)
                            }
                        }
                        HorizontalDivider(color = WesalSurfaceBorder, thickness = 0.5.dp)
                    }
                }

                if (!state.isLoadingContacts &&
                    !state.isSearching &&
                    !state.isLoadingDiscoverable &&
                    filteredWesalContacts.isEmpty() &&
                    extraGlobalResults.isEmpty() &&
                    discoverableUsersList.isEmpty() &&
                    filteredNonWesalContacts.isEmpty()
                ) {
                    item(key = "empty_search_hint") {
                        Text(
                            text = if (trimmedQuery.isBlank()) {
                                "ابحث بمعرف وصال الفريد (@Wesal ID) أو اسم الصديق لبدء محادثة أو مكالمة عبر الإنترنت دون الحاجة لرقم الهاتف."
                            } else {
                                "لا توجد نتائج مطابقة لبحثك"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = WesalTextMuted,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
