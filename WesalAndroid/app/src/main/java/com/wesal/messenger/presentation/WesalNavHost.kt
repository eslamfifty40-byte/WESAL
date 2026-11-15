package com.wesal.messenger.presentation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wesal.messenger.admin.AdminVerificationScreen
import com.wesal.messenger.authentication.AuthViewModel
import com.wesal.messenger.authentication.OtpVerificationScreen
import com.wesal.messenger.authentication.PhoneLoginScreen
import com.wesal.messenger.authentication.ProfileSetupScreen
import com.wesal.messenger.channels.ChannelDetailScreen
import com.wesal.messenger.chat.ChatDetailScreen
import com.wesal.messenger.contacts.InviteLandingScreen
import com.wesal.messenger.contacts.NewChatContactsScreen
import com.wesal.messenger.groups.GroupInfoScreen
import com.wesal.messenger.media.CameraHubScreen
import com.wesal.messenger.profile.ProfileScreen
import com.wesal.messenger.profile.UserProfileDetailScreen

private object Routes {
    const val PHONE_LOGIN = "phone_login"
    const val ADD_ACCOUNT = "add_account?email={email}"
    const val OTP = "otp_verification"
    const val PROFILE_SETUP = "profile_setup"
    const val HOME = "home"
    const val CAMERA_HUB = "camera_hub"
    const val NEW_CHAT = "new_chat"
    const val CHAT_DETAIL = "chat/{chatId}"
    const val GROUP_INFO = "group/{groupId}"
    const val CHANNEL_DETAIL = "channel_detail/{channelId}"
    const val INVITE_LANDING = "invite_landing/{inviteId}"
    const val ADMIN_VERIFICATION = "admin_verification"
    const val PROFILE = "profile"
    const val USER_PROFILE = "user_profile/{uid}"
}

@Composable
fun WesalNavHost(
    startDestination: StartDestination,
    authUid: String?,
    isOnline: Boolean,
    initialChatId: String? = null,
    initialChannelId: String? = null,
    pendingInviteId: String? = null,
    onConsumedDeepLinkChat: () -> Unit = {},
    onConsumedDeepLinkChannel: () -> Unit = {},
    onConsumePendingInvite: () -> Unit = {}
) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    val startRoute = when (startDestination) {
        StartDestination.Home -> Routes.HOME
        StartDestination.OtpPending -> Routes.OTP
        StartDestination.ProfileSetup -> Routes.PROFILE_SETUP
        else -> Routes.PHONE_LOGIN
    }

    LaunchedEffect(initialChatId, authUid) {
        if (!initialChatId.isNullOrBlank() && authUid != null) {
            navController.navigate("chat/$initialChatId") {
                launchSingleTop = true
            }
            onConsumedDeepLinkChat()
        }
    }

    LaunchedEffect(initialChannelId, authUid, currentRoute) {
        if (!initialChannelId.isNullOrBlank() && authUid != null && currentRoute == Routes.HOME) {
            navController.navigate("channel_detail/$initialChannelId") {
                launchSingleTop = true
            }
            onConsumedDeepLinkChannel()
        }
    }

    LaunchedEffect(pendingInviteId, authUid, currentRoute) {
        if (!pendingInviteId.isNullOrBlank() && authUid != null && currentRoute == Routes.HOME) {
            navController.navigate("invite_landing/${Uri.encode(pendingInviteId)}") {
                launchSingleTop = true
            }
            onConsumePendingInvite()
        }
    }

    // Automatically force back-stack reset to login if authUid becomes null while inside authenticated routes
    LaunchedEffect(authUid, currentRoute) {
        val protectedRoutes = setOf(
            Routes.HOME,
            Routes.CAMERA_HUB,
            Routes.NEW_CHAT,
            Routes.CHAT_DETAIL,
            Routes.GROUP_INFO,
            Routes.CHANNEL_DETAIL,
            Routes.INVITE_LANDING,
            Routes.ADMIN_VERIFICATION,
            Routes.PROFILE,
            Routes.USER_PROFILE,
            Routes.PROFILE_SETUP
        )
        if (authUid == null && currentRoute in protectedRoutes) {
            authViewModel.resetForNewLogin()
            navController.navigate(Routes.PHONE_LOGIN) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
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

        composable(
            route = Routes.ADD_ACCOUNT,
            arguments = listOf(navArgument("email") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            val prefillEmail = Uri.decode(backStackEntry.arguments?.getString("email").orEmpty())
            LaunchedEffect(Unit) {
                authViewModel.resetForNewLogin()
            }
            PhoneLoginScreen(
                viewModel = authViewModel,
                isAddAccountMode = true,
                prefillEmail = prefillEmail,
                onCancelAddAccount = { navController.popBackStack() },
                onNavigateToOtp = { navController.navigate(Routes.OTP) },
                onNavigateToProfileSetup = {
                    navController.navigate(Routes.PROFILE_SETUP) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                onNavigateToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(navController.graph.id) { inclusive = true }
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
                onOpenProfile = { navController.navigate(Routes.PROFILE) },
                onOpenCameraHub = { navController.navigate(Routes.CAMERA_HUB) },
                onOpenChannel = { channelId -> navController.navigate("channel_detail/$channelId") },
                onOpenAdminVerification = { navController.navigate(Routes.ADMIN_VERIFICATION) },
                onOpenAddAccount = { email ->
                    val encoded = Uri.encode(email.orEmpty())
                    navController.navigate("add_account?email=$encoded")
                },
                onAccountSwitched = { isNewProfile ->
                    val target = if (isNewProfile) Routes.PROFILE_SETUP else Routes.HOME
                    navController.navigate(target) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onSignedOut = {
                    authViewModel.resetForNewLogin()
                    navController.navigate(Routes.PHONE_LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.CAMERA_HUB) {
            CameraHubScreen(
                onClose = { navController.popBackStack() },
                onStoryPublished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenChat = { chatId ->
                    navController.popBackStack()
                    navController.navigate("chat/$chatId") {
                        launchSingleTop = true
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
                },
                onOpenUserProfile = { uid ->
                    navController.navigate("user_profile/$uid")
                }
            )
        }

        composable(
            route = Routes.CHAT_DETAIL,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType })
        ) {
            ChatDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenGroupInfo = { groupId -> navController.navigate("group/$groupId") },
                onOpenUserProfile = { peerUid -> navController.navigate("user_profile/$peerUid") }
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
                onOpenUserProfile = { uid -> navController.navigate("user_profile/$uid") },
                onLeftGroup = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.CHANNEL_DETAIL,
            arguments = listOf(navArgument("channelId") { type = NavType.StringType })
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString("channelId").orEmpty()
            ChannelDetailScreen(
                channelId = channelId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.INVITE_LANDING,
            arguments = listOf(navArgument("inviteId") { type = NavType.StringType })
        ) { backStackEntry ->
            val inviteId = Uri.decode(backStackEntry.arguments?.getString("inviteId").orEmpty())
            InviteLandingScreen(
                inviteId = inviteId,
                onBack = { navController.popBackStack() },
                onOpenChat = { chatId ->
                    navController.popBackStack()
                    navController.navigate("chat/$chatId") {
                        launchSingleTop = true
                    }
                },
                onOpenUserProfile = { uid ->
                    navController.navigate("user_profile/$uid")
                }
            )
        }

        composable(Routes.ADMIN_VERIFICATION) {
            AdminVerificationScreen(
                onBack = { navController.popBackStack() },
                onOpenUserProfile = { uid -> navController.navigate("user_profile/$uid") }
            )
        }

        composable(Routes.PROFILE) {
            ProfileScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.USER_PROFILE,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) {
            UserProfileDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { chatId ->
                    navController.popBackStack()
                    navController.navigate("chat/$chatId") {
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
