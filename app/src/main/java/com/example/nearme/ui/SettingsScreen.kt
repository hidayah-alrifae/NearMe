package com.example.nearme.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.example.nearme.util.AppPreferences
import com.example.nearme.util.LocalAuth
import com.example.nearme.util.rememberTotalUnreadCount
import androidx.compose.ui.res.stringResource
import com.example.nearme.R

@Composable
fun SettingsScreen(onNavigateTab: (NavTab) -> Unit) {
    val context = LocalContext.current

    var displayName by remember { mutableStateOf(LocalAuth.getDisplayName(context)) }
    val shortId = remember { LocalAuth.getShortId(context) }

    var editing by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf(displayName) }

    val themeMode by AppPreferences.theme.collectAsState()
    val language  by AppPreferences.language.collectAsState()
    val unread by rememberTotalUnreadCount()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {

        // ── Header ───────────────────────────────────
        Text(
            text = stringResource(R.string.settings_title),
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
                    Text(displayName, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("#$shortId", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Account ──────────────────────────────
            SectionTitle("Account")
            SettingRow(label = "Display name", value = displayName) {
                draftName = displayName; editing = true
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Appearance (Theme) ───────────────────
            SectionTitle("Appearance")
            SegmentedRow(
                label = stringResource(R.string.settings_theme),
                options = listOf(stringResource(R.string.settings_theme_system), stringResource(R.string.settings_theme_light), stringResource(R.string.settings_theme_dark)),
                selectedIndex = when (themeMode) {
                    AppPreferences.ThemeMode.SYSTEM -> 0
                    AppPreferences.ThemeMode.LIGHT  -> 1
                    AppPreferences.ThemeMode.DARK   -> 2
                },
                onSelect = { idx ->
                    val mode = when (idx) {
                        1 -> AppPreferences.ThemeMode.LIGHT
                        2 -> AppPreferences.ThemeMode.DARK
                        else -> AppPreferences.ThemeMode.SYSTEM
                    }
                    AppPreferences.setTheme(context, mode)
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Language ─────────────────────────────
            SectionTitle("Language")
            SegmentedRow(
                label = stringResource(R.string.settings_language),
                options = listOf("English", "العربية"),
                selectedIndex = if (language == AppPreferences.Language.ARABIC) 1 else 0,
                onSelect = { idx ->
                    val lang = if (idx == 1) AppPreferences.Language.ARABIC
                    else AppPreferences.Language.ENGLISH
                    AppPreferences.setLanguage(context, lang)
                }
            )
            Text(
                text = "Switching to العربية mirrors the layout right-to-left. Full text translation will follow.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── About ────────────────────────────────
            SectionTitle("About")
            SettingRow(label = "Version", value = "1.0") {}

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "NearMe works fully offline. Your messages and identity never leave your phone.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        NearMeBottomNav(
            selected = NavTab.SETTINGS,
            chatsUnreadCount = unread,
            onTabSelected = onNavigateTab
        )
    }

    // ── Edit-name dialog ────────────────────────────
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.settings_display_name)) },
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
            Text(label, fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            Text(value, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row of segmented pills (for Theme / Language). */
@Composable
private fun SegmentedRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(4.dp)
            ) {
                options.forEachIndexed { idx, opt ->
                    val active = idx == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clickable { onSelect(idx) }
                            .then(
                                if (active) Modifier.background(
                                    brush = Brush.linearGradient(
                                        listOf(Color(0xFF2E0A6E), Color(0xFF4C1D95), Color(0xFF0369A1))
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = opt,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            color = if (active) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}