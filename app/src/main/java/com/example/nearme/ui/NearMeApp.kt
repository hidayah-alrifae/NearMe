package com.example.nearme.ui
import androidx.lifecycle.viewmodel.compose.viewModel



import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.example.nearme.service.NearMeService

/**
 * Main navigation controller for the app.
 * Defines all screens and the flow between them.
 * Flow: Permission → Discovery → Chat
 */
@Composable
fun NearMeApp() {
    val context = LocalContext.current

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "permission"
    ) {
        // First screen: request permissions and enable Bluetooth
        composable("permission") {
            PermissionScreen(
                onPermissionsGranted = {
                    NearMeService.start(context)
                    navController.navigate("discovery") {
                        popUpTo("permission") { inclusive = true }
                    }
                }
            )
        }

        // Second screen: show nearby users
        composable(route = "discovery") {
            DiscoveryScreen(
                onUserClick = { shortId, displayName ->
                    navController.navigate("chat/$shortId/$displayName")
                }
            )
        }

        // Third screen: chat with a user
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
}}