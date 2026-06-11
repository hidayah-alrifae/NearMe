package com.example.nearme.ui

import android.annotation.SuppressLint
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.nearme.service.NearMeService
import com.example.nearme.util.AppPreferences
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.ComponentActivity

@SuppressLint("NewApi")
@Composable
fun NearMeApp() {
    val context = LocalContext.current
    val navController = rememberNavController()

    // Initialize prefs once, then observe language for RTL/LTR
    LaunchedEffect(Unit) { AppPreferences.init(context) }
    val language by AppPreferences.language.collectAsState()
    val direction = if (language == AppPreferences.Language.ARABIC)
        LayoutDirection.Rtl else LayoutDirection.Ltr



    val activity = context as androidx.activity.ComponentActivity

    CompositionLocalProvider(
        LocalLayoutDirection provides direction,
        androidx.activity.compose.LocalActivityResultRegistryOwner provides activity,
        androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides activity
    ) {
        NavHost(
            navController = navController,
            startDestination = "splash"
        ) {
            composable("splash") {
                SplashScreen(
                    onFinished = { needsOnboarding ->
                        val next = if (needsOnboarding) "onboarding" else "device_check"
                        navController.navigate(next) {
                            popUpTo("splash") { inclusive = true }
                        }
                    }
                )
            }

            composable("onboarding") {
                OnboardingScreen(
                    onComplete = {
                        navController.navigate("device_check") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                )
            }

            composable("device_check") {
                DeviceCheckScreen(
                    onAllReady = {
                        NearMeService.start(context)
                        navController.navigate("discovery") {
                            popUpTo("device_check") { inclusive = true }
                        }
                    }
                )
            }

            composable("discovery") {
                DiscoveryScreen(
                    onUserClick = { shortId, displayName ->
                        navController.navigate("chat/$shortId/$displayName")
                    },
                    onNavigateTab = { tab -> navController.goToTab(tab) }
                )
            }

            composable("chats") {
                ChatsListScreen(
                    onChatClick = { id, name ->
                        if (id.startsWith("grp_")) navController.navigate("group/$id/$name")
                        else navController.navigate("chat/$id/$name")
                    },
                    onNewGroup = { navController.navigate("create_group") },
                    onNavigateTab = { tab -> navController.goToTab(tab) }
                )
            }

            composable("settings") {
                SettingsScreen(onNavigateTab = { tab -> navController.goToTab(tab) })
            }

            composable("chat/{shortId}/{displayName}") { backStackEntry ->
                val shortId = backStackEntry.arguments?.getString("shortId") ?: ""
                val displayName = backStackEntry.arguments?.getString("displayName") ?: ""
                val chatViewModel: ChatViewModel = viewModel()
                LaunchedEffect(shortId) { chatViewModel.startChat(shortId) }
                ChatScreen(
                    viewModel = chatViewModel,
                    contactName = displayName,
                    contactShortId = shortId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("create_group") {
                CreateGroupScreen(
                    onCreated = { groupId, name ->
                        navController.navigate("group/$groupId/$name") {
                            popUpTo("create_group") { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable("group/{groupId}/{groupName}") { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
                val groupName = backStackEntry.arguments?.getString("groupName") ?: ""
                val groupViewModel: GroupChatViewModel = viewModel()
                LaunchedEffect(groupId) { groupViewModel.openGroup(groupId) }
                GroupChatScreen(
                    viewModel = groupViewModel,
                    groupId = groupId,
                    groupName = groupName,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

private fun NavController.goToTab(tab: NavTab) {
    val route = when (tab) {
        NavTab.DISCOVER -> "discovery"
        NavTab.CHATS    -> "chats"
        NavTab.SETTINGS -> "settings"
    }
    navigate(route) {
        popUpTo("discovery") { inclusive = false; saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}