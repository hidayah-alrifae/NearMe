package com.example.nearme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nearme.util.LocalAuth

@Composable
fun SettingsScreen(onNavigateTab: (NavTab) -> Unit) {
    val context = LocalContext.current

    var displayName by remember { mutableStateOf(LocalAuth.getDisplayName(context)) }
    val shortId = remember { LocalAuth.getShortId(context) }

    var editing by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf(displayName) }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Header ───────────────────────────────────
        Text(
            text = "Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        // ── Scrollable body ──────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {

            // Profile card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Gradient avatar
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xFF2E0A6E), Color(0xFF4C1D95), Color(0xFF0369A1))
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayName.take(2).uppercase(),
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = displayName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "#$shortId",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Account section ──────────────────────
            SectionTitle("Account")
            SettingRow(
                label = "Display name",
                value = displayName,
                onClick = {
                    draftName = displayName
                    editing = true
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── About section ────────────────────────
            SectionTitle("About")
            SettingRow(label = "Version", value = "1.0", onClick = {})
            SettingRow(label = "Made by", value = "Hidaya Al-Rifae", onClick = {})
            SettingRow(label = "University", value = "University of Tripoli", onClick = {})

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "NearMe works fully offline. Your messages and identity never leave your phone.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Bottom nav ───────────────────────────────
        NearMeBottomNav(selected = NavTab.SETTINGS, onTabSelected = onNavigateTab)
    }

    // ── Edit-name dialog ────────────────────────────
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit display name") },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { if (it.length <= 20) draftName = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = draftName.trim()
                    if (trimmed.isNotEmpty()) {
                        LocalAuth.setDisplayName(context, trimmed)
                        displayName = trimmed
                    }
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
