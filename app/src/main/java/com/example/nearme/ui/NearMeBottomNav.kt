package com.example.nearme.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.nearme.R

enum class NavTab { DISCOVER, CHATS, SETTINGS }

@Composable
fun NearMeBottomNav(
    selected: NavTab,
    chatsUnreadCount: Int = 0,
    onTabSelected: (NavTab) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(
                isActive = selected == NavTab.DISCOVER,
                label = stringResource(R.string.nav_discover),
                onClick = { onTabSelected(NavTab.DISCOVER) }
            ) { active ->
                RadarIcon(size = 18.dp, color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            NavItem(
                isActive = selected == NavTab.CHATS,
                label = stringResource(R.string.nav_chats),
                badge = chatsUnreadCount,
                onClick = { onTabSelected(NavTab.CHATS) }
            ) { active ->
                Text("💬", fontSize = if (active) 16.sp else 18.sp,
                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            NavItem(
                isActive = selected == NavTab.SETTINGS,
                label = stringResource(R.string.nav_settings),
                onClick = { onTabSelected(NavTab.SETTINGS) }
            ) { active ->
                Text("⚙", fontSize = if (active) 16.sp else 18.sp,
                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One tab. Active state wraps the icon in a brand-gradient pill. Badge is optional. */
@Composable
private fun NavItem(
    isActive: Boolean,
    label: String,
    badge: Int = 0,
    onClick: () -> Unit,
    icon: @Composable (active: Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 28.dp)
                        .background(
                            brush = Brush.linearGradient(
                                listOf(Color(0xFF2E0A6E), Color(0xFF4C1D95), Color(0xFF0369A1))
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) { icon(true) }
            } else {
                Box(
                    modifier = Modifier.size(width = 56.dp, height = 28.dp),
                    contentAlignment = Alignment.Center
                ) { icon(false) }
            }

            // Red unread badge — top-right of the icon pill
            if (badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = (-2).dp)
                        .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                        .background(Color(0xFFE24B4A), RoundedCornerShape(9.dp))
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (badge > 99) "99+" else badge.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            color = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The NearMe radar mark — three rings + center dot. Tintable for nav use. */
@Composable
private fun RadarIcon(size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        drawCircle(color.copy(alpha = 0.45f), r * 0.95f, c, style = Stroke(1.2.dp.toPx()))
        drawCircle(color.copy(alpha = 0.70f), r * 0.65f, c, style = Stroke(1.2.dp.toPx()))
        drawCircle(color, r * 0.18f, c)
    }
}