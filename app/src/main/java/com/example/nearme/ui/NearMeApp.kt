package com.example.nearme.ui

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.example.nearme.service.NearMeService
import android.annotation.SuppressLint

/**
 * Main navigation controller for the app.
 *
 * Flow:
 *   Splash → Onboarding (first time only) → DeviceCheck → Discovery → Chat
 *
 * The splash checks SharedPreferences. If onboarding was never completed,
 * it routes there first. Otherwise it jumps straight to the device check.
 */
@SuppressLint("NewApi")
@Composable
fun NearMeApp() {
    val context = LocalContext.current
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "splash"
    ) {
        // ── 1. Splash ─────────────────────────────────
        composable("splash") {
            SplashScreen(
                onFinished = { needsOnboarding ->
                    if (needsOnboarding) {
                        navController.navigate("onboarding") {
                            popUpTo("splash") { inclusive = true }
                        }
                    } else {
                        navController.navigate("device_check") {
                            popUpTo("splash") { inclusive = true }
                        }
                    }
                }
            )
        }

        // ── 2. Onboarding (first install only) ────────
        composable("onboarding") {
            OnboardingScreen(
                onComplete = {
                    navController.navigate("device_check") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        // ── 3. Device check + permissions ─────────────
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

        // ── 4. Discovery (main screen) ────────────────
        composable(route = "discovery") {
            DiscoveryScreen(
                onUserClick = { shortId, displayName ->
                    navController.navigate("chat/$shortId/$displayName")
                }
            )
        }

        // ── 5. Private chat ───────────────────────────
        composable(route = "chat/{shortId}/{displayName}") { backStackEntry ->
            val shortId = backStackEntry.arguments?.getString("shortId") ?: ""
            val displayName = backStackEntry.arguments?.getString("displayName") ?: ""
            val chatViewModel: ChatViewModel = viewModel()
            LaunchedEffect(shortId) {
                chatViewModel.startChat(shortId)
            }

            ChatScreen(
                viewModel = chatViewModel,
                contactName = displayName
            )
        }
    }
}