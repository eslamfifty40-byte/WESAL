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

        val initialChatId = intent?.getStringExtra("chatId")

        setContent {
            val settings by mainViewModel.appSettings.collectAsStateWithLifecycle()
            val startDest by mainViewModel.startDestination.collectAsStateWithLifecycle()
            val incomingCall by mainViewModel.incomingCall.collectAsStateWithLifecycle()
            val isOnline by mainViewModel.isOnline.collectAsStateWithLifecycle()

            LaunchedEffect(incomingCall?.callId) {
                incomingCall?.let { call ->
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
                            isOnline = isOnline,
                            initialChatId = initialChatId
                        )
                    }
                }
            }
        }
    }
}
