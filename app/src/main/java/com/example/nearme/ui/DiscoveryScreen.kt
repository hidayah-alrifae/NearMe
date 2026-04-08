package com.example.nearme.ui



import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nearme.model.UserProfile

/**
 * Discovery screen — shows a list of nearby users
 * detected through BLE scanning. Updates in real time
 * as users appear and disappear.
 */
@Composable
fun DiscoveryScreen(viewModel: DiscoveryViewModel = viewModel()) {

    // Collect the list of nearby users from the ViewModel
    val nearbyUsers by viewModel.nearbyUsers.collectAsState()

    // Start BLE advertising and scanning when this screen appears
    // Stop when it disappears
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose {
            viewModel.stopDiscovery()
        }
    }

    // UI Layout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Screen title
        Text(
            text = "Nearby Users",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Show user count
        Text(
            text = "${nearbyUsers.size} user(s) found",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (nearbyUsers.isEmpty()) {
            // No users found — show message
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Scanning for nearby users...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Show list of discovered users
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(nearbyUsers) { user ->
                    UserCard(user)
                }
            }
        }
    }
}

/**
 * A card showing one discovered user's information.
 * Displays their name, short ID, status, and signal strength.
 */
@Composable
fun UserCard(user: UserProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: name and status
            Column {
                // Display name with short ID
                Text(
                    text = "${user.displayName}#${user.shortId}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Status indicator
                Text(
                    text = user.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (user.status) {
                        "Available" -> MaterialTheme.colorScheme.primary
                        "Busy" -> MaterialTheme.colorScheme.error
                        "Emergency" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Right side: signal strength
            Text(
                text = "${user.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}