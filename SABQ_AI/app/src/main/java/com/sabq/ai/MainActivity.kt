package com.sabq.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.sabq.ai.ui.navigation.SabqNavHost
import com.sabq.ai.ui.theme.SabqTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SabqApplication

        setContent {
            val settings by app.preferences.settingsFlow.collectAsState(
                initial = com.sabq.ai.data.preferences.SabqSettingsState()
            )
            SabqTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SabqNavHost(app = app, settings = settings)
                }
            }
        }
    }
}
