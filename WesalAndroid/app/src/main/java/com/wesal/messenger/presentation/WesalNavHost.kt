package com.wesal.messenger.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wesal.messenger.authentication.AuthViewModel
import com.wesal.messenger.authentication.OtpVerificationScreen
import com.wesal.messenger.authentication.PhoneLoginScreen
import com.wesal.messenger.authentication.ProfileSetupScreen
import com.wesal.messenger.chat.ChatDetailScreen
import com.wesal.messenger.contacts.NewChatContactsScreen
import com.wesal.messenger.groups.GroupInfoScreen
import com.wesal.messenger.profile.ProfileScreen

private object Routes {
    const val PHONE_LOGIN = "phone_login"
    const val OTP = "otp_verification"
    const val PROFILE_SETUP = "profile_setup"
    const val HOME = "home"
    const val NEW_CHAT = "new_chat"
    const val CHAT_DETAIL = "chat/{chatId}"
    const val GROUP_INFO = "group/{groupId}"
    const val PROFILE = "profile"
}

@Composable
fun WesalNavHost(
    startDestination: StartDestination,
    isOnline: Boolean,
    initialChatId: String? = null
) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()

    val startRoute = when (startDestination) {
        StartDestination.Home -> Routes.HOME
        StartDestination.OtpPending -> Routes.OTP
        StartDestination.ProfileSetup -> Routes.PROFILE_SETUP
        else -> Routes.PHONE_LOGIN
    }

    // AuthWatcher: عند الخروج ارجع لشاشة الدخول فوراً
    androidx.compose.runtime.DisposableEffect(Unit) {
        val l = com.google.firebase.auth.FirebaseAuth.AuthStateListener { a ->
            if (a.currentUser == null) {
                val cur = navController.currentDestination?.route
                if (cur != null && cur != Routes.PHONE_LOGIN && cur != Routes.OTP && cur != Routes.PROFILE_SETUP) {
                    navController.navigate(Routes.PHONE_LOGIN) { popUpTo(0) { inclusive = true } }
                }
            }
        }
        com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener(l)
        onDispose { com.google.firebase.auth.FirebaseAuth.getInstance().removeAuthStateListener(l) }
    }

    LaunchedEffect(initialChatId, startDestination) {
        if (!initialChatId.isNullOrBlank() && startDestination is StartDestination.Home) {
            navController.navigate("chat/$initialChatId")
        }
    }

    NavHost(
        navController = navController,
        startDestination = startRoute
    ) {
        composable(Routes.PHONE_LOGIN) {
            PhoneLoginScreen(
                viewModel = authViewModel,
                onNavigateToOtp = { navController.navigate(Routes.OTP) },
                onNavigateToProfileSetup = {
                    navController.navigate(Routes.PROFILE_SETUP) {
                        popUpTo(Routes.PHONE_LOGIN) { inclusive = true }
                    }
                },
                onNavigateToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PHONE_LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.OTP) {
            OtpVerificationScreen(
                viewModel = authViewModel,
                onBackToPhone = {
                    navController.navigate(Routes.PHONE_LOGIN) {
                        popUpTo(Routes.OTP) { inclusive = true }
                    }
                },
                onNavigateToProfileSetup = {
                    navController.navigate(Routes.PROFILE_SETUP) {
                        popUpTo(Routes.OTP) { inclusive = true }
                    }
                },
                onNavigateToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.OTP) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PROFILE_SETUP) {
            ProfileSetupScreen(
                viewModel = authViewModel,
                onNavigateToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PROFILE_SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                isOnline = isOnline,
                onOpenChat = { chatId -> navController.navigate("chat/$chatId") },
                onOpenNewChat = { navController.navigate(Routes.NEW_CHAT) },
                onSignedOut = {
                    navController.navigate(Routes.PHONE_LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.NEW_CHAT) {
            NewChatContactsScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { chatId ->
                    navController.popBackStack()
                    navController.navigate("chat/$chatId")
                }
            )
        }

        composable(
            route = Routes.CHAT_DETAIL,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) {
            ChatDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenGroupInfo = { groupId -> navController.navigate("group/$groupId") }
            )
        }

        composable(
            route = Routes.GROUP_INFO,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId").orEmpty()
            GroupInfoScreen(
                groupId = groupId,
                onBack = { navController.popBackStack() },
                onLeftGroup = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PROFILE) {
            ProfileScreen(onBack = { navController.popBackStack() })
        }
    }
}
