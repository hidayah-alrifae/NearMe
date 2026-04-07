package com.example.nearme.ui



import androidx.compose.runtime.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * Main navigation controller for the app.
 * Defines all screens and the flow between them.
 * Flow: Permission → Discovery → Chat
 */
@Composable
fun NearMeApp() {

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "permission"
    ) {
        // First screen: request permissions and enable Bluetooth
        composable("permission") {
            PermissionScreen(
                onPermissionsGranted = {
                    navController.navigate("discovery") {
                        // Remove permission screen from back stack
                        popUpTo("permission") { inclusive = true }
                    }
                }
            )
        }

        // Second screen: show nearby users (coming soon)
        composable("discovery") {
            // Placeholder — we'll build DiscoveryScreen next
            androidx.compose.material3.Text("Discovery Screen - Coming Soon")
        }
    }
}