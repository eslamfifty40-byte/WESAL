package com.wesal.messenger.profile

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.WesalQrCodeBox
import com.wesal.messenger.presentation.components.wesalNeonTextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountShareScreen(
    onBack: () -> Unit,
    onOpenInviteId: (String) -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val user by viewModel.currentUser.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    var showEnterInviteDialog by remember { mutableStateOf(false) }

    val numericId = user?.numericId.orEmpty()
    val publicId = user?.publicId.orEmpty()
    val primaryId = numericId.ifBlank { publicId }
    val deepLink = if (primaryId.isNotBlank()) "wesal://join?id=$primaryId" else ""
    val shareMessage = WesalShareUtils.buildInviteMessage(
        recipientName = "",
        senderId = primaryId,
        senderName = user?.displayName.orEmpty(),
        isEnglish = strings.isEnglish
    )

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = { Text(strings.shareAccountQr, color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WesalAvatar(
                        imageUrl = user?.photoUrl.orEmpty(),
                        name = user?.displayName?.ifBlank { "@$publicId" } ?: "WESAL",
                        size = 86.dp,
                        frameId = user?.frameId.orEmpty(),
                        frameExpiresAt = user?.frameExpiresAt ?: 0L
                    )

                    WesalNameWithBadge(
                        name = user?.displayName?.ifBlank { "@$publicId" } ?: strings.loading,
                        isVerified = user?.isVerified == true,
                        verificationType = user?.verificationType ?: "BLUE",
                        neonColor = user?.neonColor.orEmpty(),
                        neonGlowIntensity = user?.neonGlowIntensity ?: 0.7f,
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold
                    )

                    if (numericId.isNotBlank()) {
                        Surface(
                            color = palette.surfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "${strings.wesalNumericIdLabel}: $numericId",
                                style = wesalNeonTextStyle(
                                    baseStyle = MaterialTheme.typography.titleMedium,
                                    neonColorKey = user?.neonColor.orEmpty(),
                                    glowIntensity = user?.neonGlowIntensity ?: 0.7f,
                                    fallbackColor = palette.turquoise
                                ),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }

                    if (publicId.isNotBlank()) {
                        Text(
                            text = "@$publicId",
                            style = wesalNeonTextStyle(
                                baseStyle = MaterialTheme.typography.bodyLarge,
                                neonColorKey = user?.neonColor.orEmpty(),
                                glowIntensity = user?.neonGlowIntensity ?: 0.7f,
                                fallbackColor = palette.textSecondary
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (deepLink.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        WesalQrCodeBox(
                            payload = deepLink,
                            size = 216.dp,
                            borderColor = palette.border
                        )
                        Text(
                            text = if (strings.isEnglish) {
                                "Others can scan this QR code or enter ID $primaryId to message you on WESAL."
                            } else {
                                "يمكن للآخرين مسح رمز QR أو استخدام المعرّف $primaryId للتواصل معك مباشرة على WESAL (دون مشاركة البريد أو الهاتف المخفي أو تاريخ الميلاد)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        CircularProgressIndicator(color = palette.turquoise)
                    }
                }
            }

            if (primaryId.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(primaryId))
                            Toast.makeText(context, "${strings.wesalNumericIdCopied} $primaryId", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isEnglish) "Copy ID" else "نسخ المعرّف", color = palette.turquoise, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(WesalShareUtils.APK_INVITE_URL))
                            Toast.makeText(
                                context,
                                if (strings.isEnglish) "Official link copied" else "تم نسخ رابط الدعوة",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = palette.emerald, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isEnglish) "Copy Link" else "نسخ الرابط", color = palette.emerald, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = { WesalShareUtils.shareViaWhatsApp(context, shareMessage) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.inviteViaWhatsApp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { WesalShareUtils.shareGeneral(context, shareMessage) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (strings.isEnglish) "Share via Other Apps" else "مشاركة عبر تطبيقات أخرى", color = palette.turquoise)
                }
            }

            OutlinedButton(
                onClick = { showEnterInviteDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (strings.isEnglish) "Open Account ID or Link" else "فتح معرّف أو رابط حساب صديق",
                    color = palette.textPrimary
                )
            }
        }
    }

    if (showEnterInviteDialog) {
        var rawTarget by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEnterInviteDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Open WESAL Account" else "فتح حساب عبر المعرّف أو الرابط",
                    color = palette.textPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = rawTarget,
                    onValueChange = { rawTarget = it },
                    label = { Text(if (strings.isEnglish) "5-digit ID, @username or wesal:// link" else "أدخل المعرّف الرقمي أو @username أو الرابط") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedLabelColor = palette.turquoise,
                        unfocusedLabelColor = palette.textSecondary,
                        focusedBorderColor = palette.turquoise,
                        unfocusedBorderColor = palette.border,
                        cursorColor = palette.turquoise
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = rawTarget.trim().isNotBlank(),
                    onClick = {
                        val cleaned = rawTarget.trim()
                            .substringAfter("id=", rawTarget.trim())
                            .substringBefore("&")
                            .removePrefix("@")
                            .trim()
                        showEnterInviteDialog = false
                        if (cleaned.isNotBlank()) onOpenInviteId(cleaned)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent)
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showEnterInviteDialog = false }) { Text(strings.cancel) }
            }
        )
    }
}
