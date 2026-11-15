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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.calls.IncomingCallActivity
import com.wesal.messenger.core.theme.WesalTheme
import com.wesal.messenger.presentation.MainViewModel
import com.wesal.messenger.presentation.StartDestination
import com.wesal.messenger.presentation.WesalNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val deepLinkChatIdState = mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition {
            mainViewModel.startDestination.value is StartDestination.Loading
        }
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        deepLinkChatIdState.value = intent?.getStringExtra("chatId")

        setContent {
            val settings by mainViewModel.appSettings.collectAsStateWithLifecycle()
            val startDest by mainViewModel.startDestination.collectAsStateWithLifecycle()
            val authUid by mainViewModel.authUid.collectAsStateWithLifecycle()
            val incomingCall by mainViewModel.incomingCall.collectAsStateWithLifecycle()
            val isOnline by mainViewModel.isOnline.collectAsStateWithLifecycle()
            val deepLinkChatId by deepLinkChatIdState
            var lastHandledCallId by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(incomingCall?.callId) {
                val call = incomingCall
                if (call == null) {
                    lastHandledCallId = null
                } else if (call.callId != lastHandledCallId) {
                    lastHandledCallId = call.callId
                    val intent = Intent(this@MainActivity, IncomingCallActivity::class.java).apply {
                        putExtra(IncomingCallActivity.EXTRA_CALL_ID, call.callId)
                        putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, call.callerName)
                        putExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR, call.callerAvatarUrl)
                        putExtra(IncomingCallActivity.EXTRA_IS_VIDEO, call.isVideo)
                    }
                    startActivity(intent)
                }
            }

            val layoutDirection = if (settings.languageTag == "en") {
                LayoutDirection.Ltr
            } else {
                LayoutDirection.Rtl
            }

            WesalTheme(
                darkTheme = settings.darkTheme,
                fontScale = settings.fontScale
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    if (startDest !is StartDestination.Loading) {
                        WesalNavHost(
                            startDestination = startDest,
                            authUid = authUid,
                            isOnline = isOnline,
                            initialChatId = deepLinkChatId,
                            onConsumedDeepLinkChat = { deepLinkChatIdState.value = null }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newChatId = intent.getStringExtra("chatId")
        if (!newChatId.isNullOrBlank()) {
            deepLinkChatIdState.value = newChatId
        }
    }
}
