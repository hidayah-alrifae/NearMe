package com.example.nearme.ui

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.*
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.example.nearme.service.NearMeService
import android.annotation.SuppressLint

@SuppressLint("NewApi")
@Composable
fun NearMeApp() {
    val context = LocalContext.current
    val navController = rememberNavController()

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
                onChatClick = { shortId, displayName ->
                    navController.navigate("chat/$shortId/$displayName")
                },
                onNavigateTab = { tab -> navController.goToTab(tab) }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateTab = { tab -> navController.goToTab(tab) }
            )
        }

        composable("chat/{shortId}/{displayName}") { backStackEntry ->
            val shortId = backStackEntry.arguments?.getString("shortId") ?: ""
            val displayName = backStackEntry.arguments?.getString("displayName") ?: ""
            val chatViewModel: ChatViewModel = viewModel()
            LaunchedEffect(shortId) {
                chatViewModel.startChat(shortId)
            }
            ChatScreen(viewModel = chatViewModel, contactName = displayName)
        }
    }
}

/** Maps a bottom-nav tap to a navigation action, keeping the back stack flat. */
private fun NavController.goToTab(tab: NavTab) {
    val route = when (tab) {
        NavTab.DISCOVER -> "discovery"
        NavTab.CHATS    -> "chats"
        NavTab.SETTINGS -> "settings"
    }
    navigate(route) {
        // Don't stack discovery → chats → discovery → chats…
        popUpTo("discovery") { inclusive = false; saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}