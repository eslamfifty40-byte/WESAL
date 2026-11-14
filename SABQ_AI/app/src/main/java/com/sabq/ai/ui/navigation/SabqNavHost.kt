package com.sabq.ai.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sabq.ai.SabqApplication
import com.sabq.ai.data.preferences.SabqSettingsState
import com.sabq.ai.ui.screens.*
import com.sabq.ai.ui.theme.SabqColors

sealed class SabqRoute(val route: String, val labelAr: String) {
    data object Home : SabqRoute("home", "الرئيسية")
    data object Scan : SabqRoute("scan", "الفحوص")
    data object Assessment : SabqRoute("assessment", "التقييم")
    data object Assistant : SabqRoute("assistant", "المساعد الذكي")
    data object Reports : SabqRoute("reports", "التقارير")
    data object Memory : SabqRoute("memory", "الذاكرة")
    data object Labs : SabqRoute("labs", "التحاليل")
    data object Settings : SabqRoute("settings", "الإعدادات")
}

@Composable
fun SabqNavHost(app: SabqApplication, settings: SabqSettingsState) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: SabqRoute.Home.route

    Scaffold(
        containerColor = SabqColors.Black,
        bottomBar = {
            NavigationBar(containerColor = SabqColors.Surface) {
                listOf(
                    SabqRoute.Home to Icons.Default.Home,
                    SabqRoute.Scan to Icons.Default.QrCodeScanner,
                    SabqRoute.Assistant to Icons.Default.Psychology,
                    SabqRoute.Reports to Icons.Default.Description,
                    SabqRoute.Settings to Icons.Default.Settings
                ).forEach { (dest, icon) ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = dest.labelAr) },
                        label = { Text(dest.labelAr) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SabqColors.Cyan,
                            selectedTextColor = SabqColors.Cyan,
                            unselectedIconColor = SabqColors.TextMuted,
                            unselectedTextColor = SabqColors.TextMuted,
                            indicatorColor = SabqColors.Purple.copy(alpha = 0.2f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SabqColors.Black)
                .padding(padding)
        ) {
            NavHost(navController = navController, startDestination = SabqRoute.Home.route) {
                composable(SabqRoute.Home.route) {
                    HomeScreen(
                        onStartScan = { navController.navigate(SabqRoute.Scan.route) },
                        onOpenAssessment = { navController.navigate(SabqRoute.Assessment.route) },
                        onOpenAssistant = { navController.navigate(SabqRoute.Assistant.route) },
                        onOpenReports = { navController.navigate(SabqRoute.Reports.route) },
                        onOpenMemory = { navController.navigate(SabqRoute.Memory.route) },
                        onOpenLabs = { navController.navigate(SabqRoute.Labs.route) },
                        onOpenSettings = { navController.navigate(SabqRoute.Settings.route) }
                    )
                }
                composable(SabqRoute.Scan.route) {
                    HealthScanScreen(app = app, userId = settings.activeUserId)
                }
                composable(SabqRoute.Assessment.route) {
                    SmartHealthAssessmentScreen(app = app, userId = settings.activeUserId)
                }
                composable(SabqRoute.Assistant.route) {
                    SecondBrainScreen(app = app, settings = settings)
                }
                composable(SabqRoute.Reports.route) {
                    ReportsScreen(app = app, userId = settings.activeUserId)
                }
                composable(SabqRoute.Memory.route) {
                    SmartMemoryScreen(app = app, userId = settings.activeUserId)
                }
                composable(SabqRoute.Labs.route) {
                    ClinicalLabsScreen(app = app, userId = settings.activeUserId)
                }
                composable(SabqRoute.Settings.route) {
                    SettingsPrivacyScreen(app = app, settings = settings)
                }
            }
        }
    }
}
