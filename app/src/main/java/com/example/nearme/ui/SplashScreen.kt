package com.example.nearme.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Splash screen — first thing the user sees.
 * Shows the NearMe logo next to the app name on a gradient background.
 * After 2.2 seconds, calls onFinished with:
 *   true  = user hasn't seen onboarding yet (first install)
 *   false = onboarding already done, skip to setup
 */
@Composable
fun SplashScreen(onFinished: (needsOnboarding: Boolean) -> Unit) {

    val context = LocalContext.current

    // Check whether onboarding was completed before
    val prefs = context.getSharedPreferences("nearme_prefs", Context.MODE_PRIVATE)
    val onboardingDone = prefs.getBoolean("onboarding_complete", false)

    // Fade-in animation: starts invisible, animates to fully visible
    var visible by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "splash_fade"
    )

    // Trigger fade-in immediately, then navigate after delay
    LaunchedEffect(Unit) {
        visible = true
        delay(2200)
        onFinished(!onboardingDone)
    }

    // ── UI ────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF2E0A6E),
                        Color(0xFF4C1D95),
                        Color(0xFF0369A1)
                    )

                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Logo + "NearMe" side by side, centered
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer(alpha = alpha)
        ) {
            // Radar logo (three rings + center dot)
            RadarLogo(size = 56)

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = "NearMe",
                fontSize = 38.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                letterSpacing = 1.5.sp
            )
        }

        // Version number at the bottom
        Text(
            text = "v 1.0",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.45f * alpha),
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
        )
    }
}

/**
 * Draws the NearMe radar logo — three concentric rings + center dot.
 * All white on transparent background, designed to sit on the gradient.
 * Reused anywhere the brand icon is needed inside the app.
 */
@Composable
fun RadarLogo(size: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size.dp)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2

        // Outer ring — faint
        drawCircle(
            color = Color.White.copy(alpha = 0.25f),
            radius = r * 0.88f,
            center = center,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
        // Middle ring — medium
        drawCircle(
            color = Color.White.copy(alpha = 0.5f),
            radius = r * 0.62f,
            center = center,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
        // Inner ring — bright
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = r * 0.38f,
            center = center,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        )
        // Center dot — solid white
        drawCircle(
            color = Color.White,
            radius = r * 0.16f,
            center = center
        )
    }
}