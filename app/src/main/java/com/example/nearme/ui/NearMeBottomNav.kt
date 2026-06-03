package com.example.nearme.ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class NavTab { DISCOVER, CHATS, SETTINGS }

@Composable
fun NearMeBottomNav(
    selected: NavTab,
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
            NavItem("📡", "Discover", selected == NavTab.DISCOVER) { onTabSelected(NavTab.DISCOVER) }
            NavItem("💬", "Chats",    selected == NavTab.CHATS)    { onTabSelected(NavTab.CHATS) }
            NavItem("⚙",  "Settings", selected == NavTab.SETTINGS) { onTabSelected(NavTab.SETTINGS) }
        }
    }
}

@Composable
private fun NavItem(
    icon: String,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isActive) {
            // Active: icon sits inside a small gradient pill
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
            ) {
                Text(icon, fontSize = 16.sp, color = Color.White)
            }
        } else {
            Text(icon, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
