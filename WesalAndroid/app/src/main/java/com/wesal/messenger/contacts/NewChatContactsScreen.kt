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
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.domain.model.ContactRequest
import com.wesal.messenger.domain.model.ContactRequestStatus
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun NewChatContactsScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenUserProfile: (String) -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val requests by viewModel.contactRequests.collectAsStateWithLifecycle()
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val contactsPermission = rememberPermissionState(Manifest.permission.READ_CONTACTS)

    LaunchedEffect(contactsPermission.status.isGranted) {
        if (contactsPermission.status.isGranted) {
            viewModel.syncDeviceContacts()
        }
    }

    val myUid = viewModel.myUid
    val requestByPeer = remember(requests, myUid) {
        requests.associateBy { req ->
            if (req.senderUid == myUid) req.receiverUid else req.senderUid
        }
    }
    val incomingPending = remember(requests, myUid) {
        requests.filter { it.receiverUid == myUid && it.status == ContactRequestStatus.PENDING }
    }
    val outgoingPending = remember(requests, myUid) {
        requests.filter { it.senderUid == myUid && it.status == ContactRequestStatus.PENDING }
    }

    val trimmedQuery = state.query.trim()
    val cleanQueryHandle = trimmedQuery.removePrefix("@")
    val filteredWesalContacts = remember(state.wesalContacts, trimmedQuery) {
        if (trimmedQuery.isBlank()) state.wesalContacts
        else state.wesalContacts.filter {
            it.deviceName.contains(trimmedQuery, ignoreCase = true) ||
                it.user.displayName.contains(trimmedQuery, ignoreCase = true) ||
                it.user.publicId.equals(cleanQueryHandle, ignoreCase = true) ||
                it.user.numericId == trimmedQuery
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

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = { Text(strings.contactsScreenTitle, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (contactsPermission.status.isGranted) {
                            viewModel.syncDeviceContacts()
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = strings.refresh, tint = palette.turquoise)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
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
                placeholder = { Text(strings.searchContactsPlaceholder) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.turquoise) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.search("") }) {
                            Icon(Icons.Default.Close, contentDescription = strings.clear, tint = palette.textSecondary)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                    focusedPlaceholderColor = palette.textMuted,
                    unfocusedPlaceholderColor = palette.textMuted,
                    focusedBorderColor = palette.turquoise,
                    unfocusedBorderColor = palette.border,
                    cursorColor = palette.turquoise
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            if (state.isLoadingContacts || state.isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.emerald)
            }

            state.error?.let { err ->
                Text(err, color = palette.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.feedback?.let { msg ->
                Text(msg, color = palette.emerald, style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                currentUser?.takeIf { it.numericId.isNotBlank() || it.publicId.isNotBlank() }?.let { me ->
                    item(key = "my_wesal_id_card") {
                        Surface(
                            color = palette.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(strings.wesalNumericIdLabel, style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
                                        if (me.numericId.isNotBlank()) {
                                            Text("ID: ${me.numericId}", style = MaterialTheme.typography.titleMedium, color = palette.turquoise, fontWeight = FontWeight.Bold)
                                        }
                                        if (me.publicId.isNotBlank()) {
                                            Text("@${me.publicId}", style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val idToCopy = me.numericId.ifBlank { "@${me.publicId}" }
                                            clipboard.setText(AnnotatedString(idToCopy))
                                            Toast.makeText(context, idToCopy, Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = strings.copy, tint = palette.turquoise)
                                    }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            WesalShareUtils.shareViaWhatsApp(
                                                context,
                                                WesalShareUtils.buildInviteMessage(myWesalId = me.publicId, isEnglish = strings.isEnglish)
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.inviteViaWhatsApp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            WesalShareUtils.shareGeneral(
                                                context,
                                                WesalShareUtils.buildInviteMessage(myWesalId = me.publicId, isEnglish = strings.isEnglish)
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.IosShare, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.share, color = palette.turquoise, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                // Direct card to contact WESAL Administration & Support (10001)
                if (currentUser?.numericId != Constants.OWNER_ADMIN_NUMERIC_ID) {
                    item(key = "support_10001_card") {
                        Surface(
                            color = palette.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, palette.emerald.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openSupportChat10001(onOpenChat) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.SupportAgent, contentDescription = null, tint = palette.emerald, modifier = Modifier.size(30.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(strings.contactWesalAdmin, color = palette.textPrimary, fontWeight = FontWeight.Bold)
                                    Text(strings.contactWesalAdminSubtitle, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                                }
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = palette.emerald)
                            }
                        }
                    }
                }

                // Incoming Contact Requests
                if (incomingPending.isNotEmpty()) {
                    item(key = "header_incoming_requests") {
                        Text(
                            text = "${strings.incomingRequestsTitle} (${incomingPending.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.emerald,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(incomingPending, key = { "in_${it.requestId}" }) { req ->
                        Surface(
                            color = palette.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, palette.emerald.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenUserProfile(req.senderUid) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                WesalAvatar(imageUrl = req.senderAvatarUrl, name = req.senderName, size = 44.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    WesalNameWithBadge(
                                        name = req.senderName.ifBlank { "@${req.senderPublicId}" },
                                        isVerified = req.senderVerified,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (req.senderPublicId.isNotBlank()) {
                                        Text("@${req.senderPublicId}", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Button(
                                    onClick = { viewModel.acceptContactRequest(req.requestId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text(strings.acceptContactRequest, maxLines = 1) }
                                OutlinedButton(
                                    onClick = { viewModel.rejectContactRequest(req.requestId) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text(strings.rejectContactRequest, color = palette.error, maxLines = 1) }
                            }
                        }
                    }
                }

                // Outgoing Sent Requests
                if (outgoingPending.isNotEmpty()) {
                    item(key = "header_sent_requests") {
                        Text(
                            text = "${strings.sentRequestsTitle} (${outgoingPending.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.textSecondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(outgoingPending, key = { "out_${it.requestId}" }) { req ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenUserProfile(req.receiverUid) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            WesalAvatar(imageUrl = req.receiverAvatarUrl, name = req.receiverName, size = 40.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                WesalNameWithBadge(
                                    name = req.receiverName.ifBlank { "@${req.receiverPublicId}" },
                                    isVerified = req.receiverVerified
                                )
                                Text(strings.pendingRequestSent, color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { viewModel.cancelContactRequest(req.requestId) }) {
                                Text(strings.cancelRequest, color = palette.error)
                            }
                        }
                        HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                    }
                }

                // Optional SHA-256 Device Contacts Consent Card
                if (!contactsPermission.status.isGranted) {
                    item(key = "contacts_consent_card") {
                        Surface(
                            color = palette.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, palette.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Default.Contacts, contentDescription = null, tint = palette.turquoise)
                                    Text(
                                        text = if (strings.isEnglish) "Secure Contact Matching (Optional)" else "مطابقة جهات الاتصال الآمنة (اختياري)",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = if (strings.isEnglish) {
                                        "With your permission, WESAL matches phone hashes locally via SHA-256 without uploading your address book."
                                    } else {
                                        "بعد موافقتك، يطابق WESAL أرقام هاتفك محلياً عبر SHA-256 دون رفع دفتر الهاتف."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.textSecondary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { contactsPermission.launchPermissionRequest() },
                                        colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = if (strings.isEnglish) "Allow Contact Sync" else "السماح بمطابقة جهات الاتصال",
                                            fontWeight = FontWeight.Bold
                                        )
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
                                        ) { Text(strings.settings, color = palette.turquoise) }
                                    }
                                }
                            }
                        }
                    }
                }

                // Matched Device Contacts
                if (filteredWesalContacts.isNotEmpty()) {
                    item(key = "header_verified_contacts") {
                        Text(
                            text = if (strings.isEnglish) "Contacts on WESAL (${filteredWesalContacts.size})" else "جهات اتصالك على WESAL (${filteredWesalContacts.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.turquoise,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }
                    items(filteredWesalContacts, key = { "wesal_${it.user.uid}" }) { matched ->
                        WesalContactDirectoryRow(
                            user = matched.user,
                            displayTitle = matched.deviceName,
                            request = requestByPeer[matched.user.uid],
                            myUid = myUid,
                            isSuperAdmin = isSuperAdmin,
                            onOpenProfile = { onOpenUserProfile(matched.user.uid) },
                            onStartChat = { viewModel.startChatWith(matched.user.uid, onOpenChat) },
                            onSendRequest = { viewModel.sendContactRequest(matched.user.uid) },
                            onAcceptRequest = { reqId -> viewModel.acceptContactRequest(reqId) },
                            onCall = { isVideo -> context.startActivity(CallActivity.createOutgoingIntent(context, matched.user, isVideo)) }
                        )
                        HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                    }
                }

                // Exact Search Results ONLY by 5-digit WESAL ID or @username
                if (trimmedQuery.isNotBlank()) {
                    item(key = "header_global_search") {
                        Text(
                            text = if (strings.isEnglish) "Exact ID / @username Search" else "نتيجة البحث المباشر بالمعرف",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.emerald,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    if (extraGlobalResults.isEmpty() && !state.isSearching) {
                        item(key = "no_exact_match") {
                            Text(
                                text = if (strings.isEnglish) {
                                    "Enter an exact 5-digit WESAL ID (e.g. 10001) or exact @username to locate an account."
                                } else {
                                    "أدخل معرف WESAL الخماسي (5 أرقام) أو @username بالكامل للوصول للحساب المطلوب فقط."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else {
                        items(extraGlobalResults, key = { "search_${it.uid}" }) { user ->
                            WesalContactDirectoryRow(
                                user = user,
                                displayTitle = user.displayName.ifBlank { "@${user.publicId}" },
                                request = requestByPeer[user.uid],
                                myUid = myUid,
                                isSuperAdmin = isSuperAdmin,
                                onOpenProfile = { onOpenUserProfile(user.uid) },
                                onStartChat = { viewModel.startChatWith(user.uid, onOpenChat) },
                                onSendRequest = { viewModel.sendContactRequest(user.uid) },
                                onAcceptRequest = { reqId -> viewModel.acceptContactRequest(reqId) },
                                onCall = { isVideo -> context.startActivity(CallActivity.createOutgoingIntent(context, user, isVideo)) }
                            )
                            HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                        }
                    }
                }

                // Non-matched Contacts -> Official Invite via WhatsApp (Sec 37)
                if (filteredNonWesalContacts.isNotEmpty()) {
                    item(key = "header_invite_contacts") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${strings.inviteFriends} (${filteredNonWesalContacts.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.textSecondary,
                                fontWeight = FontWeight.Bold
                            )
                            if (state.invitedPhonesCount > 0) {
                                Text(
                                    text = if (strings.isEnglish) "Sent: ${state.invitedPhonesCount}" else "دعوات مرسلة: ${state.invitedPhonesCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.emerald
                                )
                            }
                        }
                    }
                    items(filteredNonWesalContacts, key = { "invite_${it.contactId}_${it.rawPhone}" }) { contact ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            WesalAvatar(imageUrl = "", name = contact.displayName, size = 44.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(contact.displayName, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(contact.e164Phone ?: contact.rawPhone, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                            }
                            FilledTonalButton(
                                onClick = {
                                    viewModel.recordInviteDispatched()
                                    WesalShareUtils.shareViaWhatsApp(
                                        context = context,
                                        message = WesalShareUtils.buildInviteMessage(
                                            myWesalId = currentUser?.publicId,
                                            targetContactName = contact.displayName,
                                            isEnglish = strings.isEnglish
                                        ),
                                        targetPhoneE164 = contact.e164Phone
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.emerald.copy(alpha = 0.16f), contentColor = palette.emerald),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("WhatsApp", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(
                                onClick = {
                                    viewModel.recordInviteDispatched()
                                    WesalShareUtils.shareGeneral(
                                        context = context,
                                        message = WesalShareUtils.buildInviteMessage(
                                            myWesalId = currentUser?.publicId,
                                            targetContactName = contact.displayName,
                                            isEnglish = strings.isEnglish
                                        )
                                    )
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = strings.share, tint = palette.turquoise)
                            }
                        }
                        HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WesalContactDirectoryRow(
    user: WesalUser,
    displayTitle: String,
    request: ContactRequest?,
    myUid: String,
    isSuperAdmin: Boolean,
    onOpenProfile: () -> Unit,
    onStartChat: () -> Unit,
    onSendRequest: () -> Unit,
    onAcceptRequest: (String) -> Unit,
    onCall: (isVideo: Boolean) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val canDirectMessage = request?.status == ContactRequestStatus.ACCEPTED ||
        user.numericId == Constants.OWNER_ADMIN_NUMERIC_ID ||
        isSuperAdmin
    val isIncomingPending = request?.status == ContactRequestStatus.PENDING && request.receiverUid == myUid
    val isOutgoingPending = request?.status == ContactRequestStatus.PENDING && request.senderUid == myUid

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (canDirectMessage) onStartChat() else onOpenProfile() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.clickable(onClick = onOpenProfile)) {
            WesalAvatar(
                imageUrl = user.photoUrl,
                name = displayTitle,
                isOnline = user.isOnline,
                frameId = user.frameId,
                frameExpiresAt = user.frameExpiresAt
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WesalNameWithBadge(
                name = displayTitle,
                isVerified = user.isVerified,
                fontWeight = FontWeight.SemiBold,
                verificationType = user.verificationType,
                neonColor = user.neonColor,
                neonGlowIntensity = user.neonGlowIntensity
            )
            val subtitle = buildList {
                if (user.numericId.isNotBlank()) add("ID: ${user.numericId}")
                if (user.publicId.isNotBlank()) add("@${user.publicId}")
                if (user.bio.isNotBlank()) add(user.bio)
            }.joinToString(" • ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        when {
            canDirectMessage -> {
                IconButton(onClick = onStartChat) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = strings.tabChats, tint = palette.emerald)
                }
                IconButton(onClick = { onCall(false) }) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = palette.turquoise)
                }
                IconButton(onClick = { onCall(true) }) {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = palette.turquoise)
                }
            }

            isIncomingPending && request != null -> {
                Button(
                    onClick = { onAcceptRequest(request.requestId) },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text(strings.acceptContactRequest, maxLines = 1) }
            }

            isOutgoingPending -> {
                OutlinedButton(
                    onClick = onOpenProfile,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text(strings.pendingRequestSent, color = palette.textSecondary, maxLines = 1) }
            }

            else -> {
                FilledTonalButton(
                    onClick = onSendRequest,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.emerald.copy(alpha = 0.16f), contentColor = palette.emerald),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.sendContactRequest, maxLines = 1)
                }
            }
        }
    }
}
