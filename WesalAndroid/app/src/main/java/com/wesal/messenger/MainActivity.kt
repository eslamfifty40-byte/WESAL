package com.wesal.messenger

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogProperties
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.calls.IncomingCallActivity
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.theme.WesalTheme
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.LocaleManager
import com.wesal.messenger.core.util.PerfTrace
import com.wesal.messenger.domain.model.AppUpdateConfig
import com.wesal.messenger.presentation.MainViewModel
import com.wesal.messenger.presentation.StartDestination
import com.wesal.messenger.presentation.WesalNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val deepLinkChatIdState = mutableStateOf<String?>(null)
    private val deepLinkChannelIdState = mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        PerfTrace.mark("main_oncreate")
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { false }
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)

        setContent {
            val settings by mainViewModel.appSettings.collectAsStateWithLifecycle()
            val globalAppearance by mainViewModel.globalAppearance.collectAsStateWithLifecycle()
            val appUpdateConfig by mainViewModel.appUpdateConfig.collectAsStateWithLifecycle()
            var softUpdateDismissed by remember { mutableStateOf(false) }
            // Round 12 (addendum 11): welcome-gift announcement — the event
            // fires at most once per account (the grant is stamped on the
            // user doc), so this dialog + notification can never repeat.
            var showWelcomeGift by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                mainViewModel.welcomeGiftEvent.collect {
                    showWelcomeGift = true
                    postWelcomeGiftNotification(
                        isArabic = LocaleManager.isRtlLanguage(settings.languageTag)
                    )
                }
            }
            val startDest by mainViewModel.startDestination.collectAsStateWithLifecycle()
            val authUid by mainViewModel.authUid.collectAsStateWithLifecycle()
            val incomingCall by mainViewModel.incomingCall.collectAsStateWithLifecycle()
            val isOnline by mainViewModel.isOnline.collectAsStateWithLifecycle()
            val pendingInviteId by mainViewModel.pendingInviteId.collectAsStateWithLifecycle()
            val resolvedChannelId by mainViewModel.resolvedChannelId.collectAsStateWithLifecycle()
            val deepLinkChatId by deepLinkChatIdState
            val deepLinkChannelId by deepLinkChannelIdState
            var lastHandledCallId by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(incomingCall?.callId) {
                val call = incomingCall
                if (call == null) {
                    lastHandledCallId = null
                } else if (call.callId != lastHandledCallId) {
                    lastHandledCallId = call.callId
                    val intent = Intent(this@MainActivity, IncomingCallActivity::class.java).apply {
                        putExtra(IncomingCallActivity.EXTRA_CALL_ID, call.callId)
                        putExtra(IncomingCallActivity.EXTRA_CALLER_UID, call.callerId)
                        putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, call.callerName)
                        putExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR, call.callerAvatarUrl)
                        putExtra(IncomingCallActivity.EXTRA_CALLER_VERIFIED, call.callerVerified)
                        putExtra(IncomingCallActivity.EXTRA_IS_VIDEO, call.isVideo)
                    }
                    startActivity(intent)
                }
            }

            val layoutDirection = if (LocaleManager.isRtlLanguage(settings.languageTag)) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }

            WesalTheme(
                darkTheme = settings.darkTheme,
                languageTag = settings.languageTag,
                fontScale = settings.fontScale,
                globalAppearance = globalAppearance,
                personalAccentPreset = settings.personalAccentPreset,
                personalSurfacePreset = settings.personalSurfacePreset
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    // No loading gate: startDest is resolved from local auth state at
                    // MainViewModel construction (Home for a signed-in user), so the
                    // NavHost composes immediately. The pre-first-frame moment is
                    // covered by the branded gradient window background
                    // (@drawable/wesal_window_background in Theme.Wesal), not by a
                    // Compose loading screen.
                    WesalNavHost(
                        startDestination = startDest,
                        authUid = authUid,
                        isOnline = isOnline,
                        initialChatId = deepLinkChatId,
                        initialChannelId = deepLinkChannelId ?: resolvedChannelId,
                        pendingInviteId = pendingInviteId,
                        onConsumedDeepLinkChat = { deepLinkChatIdState.value = null },
                        onConsumedDeepLinkChannel = {
                            deepLinkChannelIdState.value = null
                            mainViewModel.consumePendingChannelInvite()
                        },
                        onConsumePendingInvite = { mainViewModel.consumePendingInvite() }
                    )

                    // Forced/soft app update (system_config/app_update), rendered ABOVE
                    // the NavHost so it covers every destination. Fail-open: the config
                    // flow emits a disabled default until a real policy is loaded, and
                    // up-to-date devices (VERSION_CODE >= latestVersionCode) never see it.
                    val currentVersionCode = BuildConfig.VERSION_CODE
                    val updateBlocking = appUpdateConfig.enabled &&
                        currentVersionCode < appUpdateConfig.latestVersionCode &&
                        (appUpdateConfig.mandatory || currentVersionCode < appUpdateConfig.minSupportedVersionCode)
                    val updateSoft = appUpdateConfig.enabled && !updateBlocking &&
                        currentVersionCode < appUpdateConfig.latestVersionCode &&
                        currentVersionCode >= appUpdateConfig.minSupportedVersionCode
                    if (updateBlocking || (updateSoft && !softUpdateDismissed)) {
                        WesalUpdateDialog(
                            config = appUpdateConfig,
                            blocking = updateBlocking,
                            onUpdateClick = {
                                val url = appUpdateConfig.apkUrl.ifBlank { Constants.APK_DOWNLOAD_URL }
                                runCatching {
                                    startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                            },
                            onLaterClick = { softUpdateDismissed = true }
                        )
                    }

                    // Round 12 (addendum 11): the welcome-gift dialog,
                    // rendered above the NavHost like the update dialog so
                    // the new registrant cannot miss it on first entry.
                    if (showWelcomeGift) {
                        WesalWelcomeGiftDialog(
                            onDismiss = { showWelcomeGift = false }
                        )
                    }
                }
            }
        }
    }

    /**
     * Round 12 (addendum 11): posts the persistent shade copy of the
     * welcome-gift announcement on the system channel (created at app
     * startup). Best-effort — the in-app dialog is the primary surface.
     */
    private fun postWelcomeGiftNotification(isArabic: Boolean) {
        runCatching {
            val text = com.wesal.messenger.core.util.WelcomeGiftLogic.announcement(isArabic)
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                WELCOME_GIFT_NOTIFICATION_ID,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = androidx.core.app.NotificationCompat.Builder(this, Constants.CHANNEL_SYSTEM)
                .setSmallIcon(R.drawable.ic_wesal_notification)
                .setContentTitle(if (isArabic) "هدية ترحيب 🎁" else "Welcome gift 🎁")
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(WELCOME_GIFT_NOTIFICATION_ID, notification)
        }
    }

    // §13.2: one-shot first-frame timing (DEBUG-only via PerfTrace): on the first
    // resume, once the decor view has posted (i.e. after the first draw pass is
    // queued), log how long cold start took from Application.onCreate entry.
    private var firstFrameLogged = false

    override fun onResume() {
        super.onResume()
        if (!firstFrameLogged) {
            firstFrameLogged = true
            window.decorView.post {
                PerfTrace.logElapsed("first_frame", "app_oncreate_start")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mainViewModel.setAppForeground(true)
    }

    override fun onStop() {
        super.onStop()
        mainViewModel.setAppForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val newChatId = intent.getStringExtra("chatId")
        if (!newChatId.isNullOrBlank()) {
            deepLinkChatIdState.value = newChatId
        }
        val newChannelId = intent.getStringExtra("channelId")
        if (!newChannelId.isNullOrBlank()) {
            deepLinkChannelIdState.value = newChannelId
        }
        mainViewModel.handleIncomingUri(intent.data)
    }
}

/**
 * App-update prompt driven by `system_config/app_update`. In blocking mode
 * (mandatory policy, or the installed build is below the minimum supported
 * version) the dialog cannot be dismissed — no close button, no back press, no
 * outside tap. In soft mode the user may postpone it once per session.
 */
private const val WELCOME_GIFT_NOTIFICATION_ID = 12011

/**
 * Round 12 (addendum 11): the welcome-gift announcement dialog. The text
 * is the owner's wording (WelcomeGiftLogic.announcement); the frame name
 * line names the actual granted frame so the user can recognise it.
 */
@Composable
private fun WesalWelcomeGiftDialog(onDismiss: () -> Unit) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val isEn = strings.isEnglish
    val frameName = com.wesal.messenger.core.util.WesalFrameLibrary
        .docs[com.wesal.messenger.core.util.WelcomeGiftLogic.WELCOME_FRAME_ID]
        ?.titleAr
        ?: com.wesal.messenger.core.util.WesalFrameCatalog.allFrames
            .firstOrNull { it.id == com.wesal.messenger.core.util.WelcomeGiftLogic.WELCOME_FRAME_ID }
            ?.titleAr
            .orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = if (isEn) "Welcome gift 🎁" else "هدية ترحيب 🎁",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = com.wesal.messenger.core.util.WelcomeGiftLogic.announcement(!isEn) +
                    if (frameName.isNotBlank()) {
                        if (isEn) "\n\nYour gift frame: $frameName" else "\n\nإطار الهدية: $frameName"
                    } else {
                        ""
                    },
                color = palette.textSecondary
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(if (isEn) "Great" else "تمام")
            }
        }
    )
}

@Composable
private fun WesalUpdateDialog(
    config: AppUpdateConfig,
    blocking: Boolean,
    onUpdateClick: () -> Unit,
    onLaterClick: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val isEn = strings.isEnglish
    val message = (
        if (isEn) config.messageEn.ifBlank { config.messageAr }
        else config.messageAr.ifBlank { config.messageEn }
        ).ifBlank {
        if (isEn) {
            "A new version of WESAL is available. Please update to get the latest fixes and features."
        } else {
            "يتوفر إصدار جديد من WESAL. يرجى التحديث للحصول على أحدث الإصلاحات والمميزات."
        }
    }
    AlertDialog(
        onDismissRequest = { if (!blocking) onLaterClick() },
        properties = DialogProperties(
            dismissOnBackPress = !blocking,
            dismissOnClickOutside = !blocking
        ),
        containerColor = palette.surface,
        title = {
            Text(
                text = if (isEn) "Update Available" else "يتوفر تحديث جديد",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(text = message, color = palette.textSecondary)
        },
        confirmButton = {
            Button(onClick = onUpdateClick) {
                Text(if (isEn) "Update Now" else "تحديث الآن")
            }
        },
        dismissButton = if (blocking) {
            null
        } else {
            {
                TextButton(onClick = onLaterClick) {
                    Text(if (isEn) "Later" else "لاحقاً", color = palette.textSecondary)
                }
            }
        }
    )
}
