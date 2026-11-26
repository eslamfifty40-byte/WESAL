package com.wesal.messenger

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.calls.IncomingCallActivity
import com.wesal.messenger.core.theme.WesalTheme
import com.wesal.messenger.core.util.LocaleManager
import com.wesal.messenger.core.util.wesalStringsForLanguage
import com.wesal.messenger.update.AppUpdateChecker
import com.wesal.messenger.update.WesalUpdateInfo
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
            val startDest by mainViewModel.startDestination.collectAsStateWithLifecycle()
            val authUid by mainViewModel.authUid.collectAsStateWithLifecycle()
            val incomingCall by mainViewModel.incomingCall.collectAsStateWithLifecycle()
            val isOnline by mainViewModel.isOnline.collectAsStateWithLifecycle()
            val pendingInviteId by mainViewModel.pendingInviteId.collectAsStateWithLifecycle()
            val resolvedChannelId by mainViewModel.resolvedChannelId.collectAsStateWithLifecycle()
            val deepLinkChatId by deepLinkChatIdState
            val deepLinkChannelId by deepLinkChannelIdState
            var lastHandledCallId by remember { mutableStateOf<String?>(null) }
            var pendingUpdate by remember { mutableStateOf<WesalUpdateInfo?>(null) }
            val activityContext = LocalContext.current

            // One update check per cold start; silent on any failure.
            LaunchedEffect(Unit) {
                pendingUpdate = AppUpdateChecker.fetchLatestUpdate(activityContext)
            }

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
                    if (startDest !is StartDestination.Loading) {
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
                    }

                    pendingUpdate?.let { update ->
                        val isEn = wesalStringsForLanguage(settings.languageTag).isEnglish
                        AlertDialog(
                            onDismissRequest = { pendingUpdate = null },
                            title = { Text(if (isEn) "Update available" else "تحديث جديد متاح") },
                            text = {
                                Text(
                                    if (isEn) {
                                        "A new WESAL version is ready. Tap Update to download and install it."
                                    } else {
                                        "يوجد إصدار جديد من WESAL. اضغط تحديث للتنزيل والتثبيت."
                                    }
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        pendingUpdate = null
                                        AppUpdateChecker.downloadAndInstall(activityContext, update)
                                    }
                                ) { Text(if (isEn) "Update" else "تحديث") }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingUpdate = null }) {
                                    Text(if (isEn) "Later" else "لاحقاً")
                                }
                            }
                        )
                    }
                }
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
