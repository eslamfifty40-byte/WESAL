package com.wesal.messenger.contacts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.theme.WesalTheme
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ContactsInviteActivity : ComponentActivity() {

    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var prefs: UserPreferencesDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by prefs.settingsFlow.collectAsStateWithLifecycle(initialValue = AppUserSettings())
            val me by userRepository.observeCurrentUser().collectAsStateWithLifecycle(initialValue = null)
            WesalTheme(
                darkTheme = settings.darkTheme,
                languageTag = settings.languageTag,
                fontScale = settings.fontScale
            ) {
                ContactsInviteScreen(
                    userRepository = userRepository,
                    myPublicId = me?.publicId?.takeIf { it.isNotBlank() } ?: me?.numericId,
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
private fun ContactsInviteScreen(
    userRepository: UserRepository,
    myPublicId: String?,
    onClose: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.READ_CONTACTS) }

    var contacts by remember { mutableStateOf(emptyList<DeviceContact>()) }
    var matchedUsers by remember { mutableStateOf<Map<String, WesalUser>>(emptyMap()) }
    var invitedPhones by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(granted) {
        if (granted) {
            val list = withContext(Dispatchers.IO) { PhoneContactsHelper.loadDeviceContacts(ctx) }
            contacts = list
            val hashes = list.mapNotNull { it.phoneHash }.distinct()
            matchedUsers = runCatching { userRepository.findUsersByPhoneHashes(hashes) }
                .getOrDefault(emptyMap())
        }
    }

    var q by remember { mutableStateOf("") }
    val shown = remember(contacts, q) {
        contacts.filter {
            q.isBlank() || it.displayName.contains(q, true) || it.rawPhone.contains(q)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.inviteFriends, style = MaterialTheme.typography.titleLarge, color = palette.textPrimary)
                if (invitedPhones.isNotEmpty()) {
                    Text(
                        text = if (strings.isEnglish) "Invited in session: ${invitedPhones.size}" else "عدد الدعوات المرسلة: ${invitedPhones.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.emerald
                    )
                }
            }
            TextButton(onClick = onClose) { Text(strings.back) }
        }
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            label = { Text(strings.search) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                cursorColor = palette.emerald,
                focusedBorderColor = palette.emerald,
                unfocusedBorderColor = palette.border,
                focusedLabelColor = palette.emerald,
                unfocusedLabelColor = palette.textSecondary,
                focusedContainerColor = palette.surface,
                unfocusedContainerColor = palette.surface
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        if (!granted) {
            Text(
                text = if (strings.isEnglish) {
                    "Allow contacts access to invite friends who aren't on WESAL yet."
                } else {
                    "اسمح بالوصول إلى جهات الاتصال لدعوة أصدقائك إلى WESAL."
                },
                color = palette.textPrimary
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) },
                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
            ) { Text(if (strings.isEnglish) "Allow" else "السماح") }
        }
        LazyColumn {
            items(shown, key = { "${it.contactId}_${it.rawPhone}" }) { c ->
                val registeredUser = c.phoneHash?.let { matchedUsers[it] }
                val phoneKey = c.e164Phone ?: c.rawPhone
                val isInvited = invitedPhones.contains(phoneKey)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.displayName, color = palette.textPrimary)
                        Text(phoneKey, color = palette.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (registeredUser != null) {
                        Text(if (strings.isEnglish) "On WESAL" else "مسجّل", color = palette.emerald, fontWeight = FontWeight.Bold)
                    } else {
                        Button(
                            onClick = {
                                invitedPhones = invitedPhones + phoneKey
                                WesalShareUtils.shareViaWhatsApp(
                                    context = ctx,
                                    message = WesalShareUtils.buildInviteMessage(
                                        myWesalId = myPublicId,
                                        targetContactName = c.displayName,
                                        isEnglish = strings.isEnglish
                                    ),
                                    targetPhoneE164 = c.e164Phone
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isInvited) palette.surfaceElevated else palette.emerald,
                                contentColor = if (isInvited) palette.emerald else palette.onPrimary
                            )
                        ) {
                            Text(if (isInvited) (if (strings.isEnglish) "Invited ✓" else "تمت الدعوة ✓") else strings.inviteViaWhatsApp)
                        }
                    }
                }
                HorizontalDivider(color = palette.border, thickness = 0.5.dp)
            }
        }
    }
}
