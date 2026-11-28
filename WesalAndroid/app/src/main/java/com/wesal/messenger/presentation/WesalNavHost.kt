package com.wesal.messenger.presentation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wesal.messenger.admin.AdminVerificationScreen
import com.wesal.messenger.aiassistant.AssistantChatScreen
import com.wesal.messenger.authentication.AuthViewModel
import com.wesal.messenger.authentication.PhoneLoginScreen
import com.wesal.messenger.authentication.ProfileSetupScreen
import com.wesal.messenger.authentication.VerifyPhoneScreen
import com.wesal.messenger.authentication.VerifyPhoneViewModel
import com.wesal.messenger.channels.ChannelDetailScreen
import com.wesal.messenger.chat.ChatDetailScreen
import com.wesal.messenger.contacts.InviteLandingScreen
import com.wesal.messenger.contacts.NewChatContactsScreen
import com.wesal.messenger.groups.GroupInfoScreen
import com.wesal.messenger.media.CameraHubScreen
import com.wesal.messenger.profile.AccountShareScreen
import com.wesal.messenger.profile.ProfileScreen
import com.wesal.messenger.profile.UserProfileDetailScreen

private object Routes {
    const val PHONE_LOGIN = "phone_login"
    const val ADD_ACCOUNT = "add_account?email={email}"
    const val PROFILE_SETUP = "profile_setup"
    const val VERIFY_PHONE = "verify_phone"
    const val HOME = "home"
    const val CAMERA_HUB = "camera_hub"
    const val NEW_CHAT = "new_chat"
    const val CHAT_DETAIL = "chat/{chatId}"
    const val GROUP_INFO = "group/{groupId}"
    const val CHANNEL_DETAIL = "channel_detail/{channelId}"
    const val INVITE_LANDING = "invite_landing/{inviteId}"
    const val ADMIN_VERIFICATION = "admin_verification"
    const val AI_ASSISTANT = "ai_assistant"
    const val PROFILE = "profile"
    const val ACCOUNT_SHARE = "account_share"
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

    // The start route is fixed for the lifetime of this NavHost. Changing NavHost.startDestination later
    // rebuilds the whole graph, which destroys the open chat screen (and cancels anything it was doing).
    val startRoute = remember {
        when (startDestination) {
            StartDestination.Home -> Routes.HOME
            StartDestination.ProfileSetup -> Routes.PROFILE_SETUP
            // The gate is never a start route: Home opens first and the effect
            // below immediately swaps it for the gate when verification is due.
            StartDestination.VerifyPhone -> Routes.HOME
            else -> Routes.PHONE_LOGIN
        }
    }

    // Late startup verification can still say "profile incomplete": move there only from Home,
    // never while the user is inside a chat.
    LaunchedEffect(startDestination, currentRoute) {
        if (startDestination == StartDestination.ProfileSetup && currentRoute == Routes.HOME) {
            navController.navigate(Routes.PROFILE_SETUP) {
                popUpTo(Routes.HOME) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Mandatory phone-verification gate (phone phase): a signed-in, profile-
    // complete but unverified account is swapped from Home to the gate (Home
    // is popped, so back cannot return to it), and the moment the user doc
    // shows verified, the gate is swapped back out for Home. Like the profile
    // setup correction above, this only ever fires FROM Home / the gate
    // itself — never from inside a chat or another screen.
    LaunchedEffect(startDestination, currentRoute) {
        if (startDestination == StartDestination.VerifyPhone && currentRoute == Routes.HOME) {
            navController.navigate(Routes.VERIFY_PHONE) {
                popUpTo(Routes.HOME) { inclusive = true }
                launchSingleTop = true
            }
        }
        if (startDestination == StartDestination.Home && currentRoute == Routes.VERIFY_PHONE) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.VERIFY_PHONE) { inclusive = true }
                launchSingleTop = true
            }
        }
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

    // uid → uid account switch while inside the app: reset the back stack to Home so no
    // screen (or nav-scoped ViewModel) keeps showing the previous account. Previously only
    // the uid → null case below was handled, so after a switch the UI stayed on the old
    // account's screens until the app was restarted (audit §3.4). The phone-login route is
    // excluded — that flow navigates to Home itself. The add-account route is NOT excluded
    // (round 4): when its sign-in succeeded at Firebase Auth but the repository call then
    // timed out, the form's own success navigation never fired and the user was stranded
    // on the form while actually signed in — this rescue is what lands them inside.
    var lastKnownAuthUid by remember { mutableStateOf(authUid) }
    LaunchedEffect(authUid, currentRoute) {
        val uid = authUid
        if (uid != null) {
            val previous = lastKnownAuthUid
            if (previous != null && previous != uid &&
                currentRoute != null &&
                currentRoute != Routes.PHONE_LOGIN
            ) {
                navController.navigate(Routes.HOME) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
            lastKnownAuthUid = uid
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
            Routes.AI_ASSISTANT,
            Routes.PROFILE,
            Routes.ACCOUNT_SHARE,
            Routes.USER_PROFILE,
            Routes.PROFILE_SETUP,
            Routes.VERIFY_PHONE
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
                onNavigateToProfileSetup = {
                    navController.navigate(Routes.PROFILE_SETUP) {
                        popUpTo(Routes.PHONE_LOGIN) { inclusive = true }
                    }
                },
                onNavigateToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PHONE_LOGIN) { inclusive = true }
                    }
                },
                onNavigateToSupportChat = { supportChatId ->
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PHONE_LOGIN) { inclusive = true }
                    }
                    navController.navigate("chat/$supportChatId") {
                        launchSingleTop = true
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

        composable(Routes.VERIFY_PHONE) {
            val verifyPhoneViewModel: VerifyPhoneViewModel = hiltViewModel()
            VerifyPhoneScreen(viewModel = verifyPhoneViewModel)
        }

        composable(Routes.HOME) {
            HomeScreen(
                isOnline = isOnline,
                onOpenChat = { chatId -> navController.navigate("chat/$chatId") },
                onOpenNewChat = { navController.navigate(Routes.NEW_CHAT) },
                onOpenProfile = { navController.navigate(Routes.PROFILE) },
                onOpenAccountShare = { navController.navigate(Routes.ACCOUNT_SHARE) },
                onOpenCameraHub = { navController.navigate(Routes.CAMERA_HUB) },
                onOpenChannel = { channelId -> navController.navigate("channel_detail/$channelId") },
                onOpenAdminVerification = { navController.navigate(Routes.ADMIN_VERIFICATION) },
                onOpenAssistant = { navController.navigate(Routes.AI_ASSISTANT) },
                onOpenAddAccount = { email ->
                    val encoded = Uri.encode(email.orEmpty())
                    navController.navigate("add_account?email=$encoded")
                },
                onAccountSwitched = { isNewProfile ->
                    authViewModel.resetForNewLogin()
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
                onOpenUserProfile = { peerUid -> navController.navigate("user_profile/$peerUid") },
                isOnline = isOnline
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
                onOpenChat = { chatId ->
                    navController.navigate("chat/$chatId") { launchSingleTop = true }
                },
                onOpenUserProfile = { uid -> navController.navigate("user_profile/$uid") },
                onOpenChannel = { channelId ->
                    navController.navigate("channel_detail/$channelId") { launchSingleTop = true }
                },
                // Round 18: «نشر حالة رسمية» hands off to the Home
                // Status tab (OfficialStoryRequestBus carries the
                // request; HomeScreen consumes it on arrival).
                onNavigateHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.AI_ASSISTANT) {
            AssistantChatScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PROFILE) {
            ProfileScreen(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.HOME) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenAccountShare = {
                    navController.navigate(Routes.ACCOUNT_SHARE)
                },
                onOpenUserProfile = { uid ->
                    navController.navigate("user_profile/$uid")
                }
            )
        }

        composable(Routes.ACCOUNT_SHARE) {
            AccountShareScreen(
                onBack = { navController.popBackStack() },
                onOpenInviteId = { id ->
                    navController.navigate("invite_landing/${Uri.encode(id)}")
                }
            )
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

