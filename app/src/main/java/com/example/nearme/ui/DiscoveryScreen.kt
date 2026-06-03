package com.example.nearme.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nearme.model.UserProfile
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DiscoveryScreen(
    viewModel: DiscoveryViewModel = viewModel(),
    onUserClick: (String, String) -> Unit = { _, _ -> },
    onNavigateTab: (NavTab) -> Unit = {}
) {
    val nearbyUsers by viewModel.nearbyUsers.collectAsState()
    val isSearching by viewModel.isExtendedSearching.collectAsState()
    val wifiAwareAvailable = viewModel.wifiAwareAvailable

    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Header ───────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NearMe",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            StatusPill(active = true, label = "Discovering")
        }

        // ── Radar ────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            contentAlignment = Alignment.Center
        ) {
            RadarView(users = nearbyUsers)
        }

        // ── User count + Extended Search ─────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = if (nearbyUsers.isEmpty()) "No one nearby yet"
                else "${nearbyUsers.size} ${if (nearbyUsers.size == 1) "person" else "people"} nearby",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (wifiAwareAvailable) {
                Spacer(modifier = Modifier.height(12.dp))
                ExtendedSearchButton(
                    isSearching = isSearching,
                    onClick = { viewModel.startExtendedSearch() }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── User list ────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            if (nearbyUsers.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Scanning…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(nearbyUsers) { user ->
                        DiscoveredUserCard(user, onUserClick)
                    }
                }
            }
        }

        // ── Bottom nav ───────────────────────────────
        NearMeBottomNav(selected = NavTab.DISCOVER, onTabSelected = onNavigateTab)
    }
}

// ── Status pill (green dot + label) ─────────────────
@Composable
private fun StatusPill(active: Boolean, label: String) {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (active) Color(0xFF1D9E75) else Color(0xFFE24B4A),
                    CircleShape
                )
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

// ── Radar (animated rings + user dots) ──────────────
@Composable
private fun RadarView(users: List<UserProfile>) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )

    val primary = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.size(260.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val maxR = size.minDimension / 2

        // Three static rings
        drawCircle(primary.copy(alpha = 0.12f), maxR * 0.95f, c, style = Stroke(1.dp.toPx()))
        drawCircle(primary.copy(alpha = 0.18f), maxR * 0.65f, c, style = Stroke(1.dp.toPx()))
        drawCircle(primary.copy(alpha = 0.28f), maxR * 0.35f, c, style = Stroke(1.dp.toPx()))

        // Pulsing outer ring
        drawCircle(
            primary.copy(alpha = (1f - pulse) * 0.4f),
            maxR * pulse,
            c,
            style = Stroke(2.dp.toPx())
        )

        // Center dot (you)
        drawCircle(
            brush = Brush.linearGradient(
                listOf(Color(0xFF2E0A6E), Color(0xFF0369A1)),
                start = Offset(c.x - 16f, c.y - 16f),
                end = Offset(c.x + 16f, c.y + 16f)
            ),
            radius = 16f,
            center = c
        )

        // User dots positioned around the radar by signal strength
        users.forEachIndexed { index, user ->
            // Stronger RSSI (closer to 0) = nearer to center.
            // RSSI range typically -30 (very close) to -90 (far)
            val normalized = ((user.rssi + 90).coerceIn(0, 60)) / 60f  // 0..1
            val distance = maxR * (0.85f - normalized * 0.50f)         // farther for weaker
            val angle = (index * 47f + 30f) * (Math.PI / 180f)         // spread around
            val pos = Offset(
                c.x + (distance * cos(angle)).toFloat(),
                c.y + (distance * sin(angle)).toFloat()
            )
            val color = when (user.status) {
                "Busy"      -> Color(0xFFEF9F27)
                "Emergency" -> Color(0xFFE24B4A)
                else        -> Color(0xFF1D9E75)
            }
            drawCircle(color.copy(alpha = 0.25f), 18f, pos)
            drawCircle(color, 9f, pos)
        }
    }
}

// ── Extended search button ──────────────────────────
@Composable
private fun ExtendedSearchButton(isSearching: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF2E0A6E), Color(0xFF4C1D95), Color(0xFF0369A1))
                ),
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(enabled = !isSearching, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = if (isSearching) "Searching further…" else "Extended search",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── User card in the list ───────────────────────────
@Composable
private fun DiscoveredUserCard(user: UserProfile, onClick: (String, String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(user.shortId, user.displayName) },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gradient avatar with initials
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFF4C1D95), Color(0xFF0369A1))
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.displayName.take(2).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "#${user.shortId}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                when (user.status) {
                                    "Busy"      -> Color(0xFFEF9F27)
                                    "Emergency" -> Color(0xFFE24B4A)
                                    else        -> Color(0xFF1D9E75)
                                },
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = user.status,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Right side: WiFi Aware tag or RSSI
            if (user.discoverySource == "WIFI_AWARE") {
                Text(
                    text = "Extended",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            } else {
                Text(
                    text = "${user.rssi} dBm",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}