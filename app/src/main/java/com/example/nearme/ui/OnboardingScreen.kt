package com.example.nearme.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {

    val context = LocalContext.current
    var currentPage by remember { mutableIntStateOf(0) }

    val titles = listOf(
        "Discover people around you",
        "Chat without internet",
        "Private and secure",
        "Share files directly"
    )

    val descriptions = listOf(
        "NearMe finds anyone nearby who has the app. No phone number or account needed.",
        "Messages go straight from your phone to theirs. No Wi-Fi router, no mobile data, no cloud servers involved.",
        "Your conversations stay on your phone. Nothing is uploaded anywhere. No account, no tracking, no data collection.",
        "Send photos, videos, and documents to people nearby. Files go directly from your phone to theirs with no upload needed."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // ── Illustration + text (animated crossfade) ─
        AnimatedContent(
            targetState = currentPage,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                fadeIn() + slideInHorizontally { it / 3 } togetherWith
                        fadeOut() + slideOutHorizontally { -it / 3 }
            },
            label = "page"
        ) { page ->
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when (page) {
                        0 -> DiscoverIllustration()
                        1 -> OfflineIllustration()
                        2 -> SecureIllustration()
                        3 -> FilesIllustration()
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = titles[page],
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = descriptions[page],
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // ── Dot indicators ───────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(4) { index ->
                val isActive = currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .then(
                            if (isActive) {
                                Modifier
                                    .width(24.dp)
                                    .height(6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(3.dp)
                                    )
                            } else {
                                Modifier
                                    .size(6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.outline,
                                        CircleShape
                                    )
                            }
                        )
                )
            }
        }

        // ── Bottom buttons ───────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentPage < 3) {
                TextButton(onClick = { finishOnboarding(context, onComplete) }) {
                    Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            Button(
                onClick = {
                    if (currentPage < 3) {
                        currentPage++
                    } else {
                        finishOnboarding(context, onComplete)
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(),
                modifier = Modifier.height(44.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            brush = Brush.linearGradient(
                                listOf(
                                    Color(0xFF2E0A6E),
                                    Color(0xFF4C1D95),
                                    Color(0xFF0369A1)
                                )
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (currentPage < 3) "Next" else "Get started",
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun finishOnboarding(context: Context, onComplete: () -> Unit) {
    context.getSharedPreferences("nearme_prefs", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("onboarding_complete", true)
        .apply()
    onComplete()
}

// ═══════════════════════════════════════════════════════
//  ILLUSTRATIONS — drawn with Canvas, no image files
// ═══════════════════════════════════════════════════════

@Composable
private fun DiscoverIllustration() {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(200.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2

        drawCircle(primary.copy(alpha = 0.12f), r * 0.85f, c, style = Stroke(1.dp.toPx()))
        drawCircle(primary.copy(alpha = 0.20f), r * 0.60f, c, style = Stroke(1.dp.toPx()))
        drawCircle(primary.copy(alpha = 0.35f), r * 0.35f, c, style = Stroke(1.dp.toPx()))

        drawCircle(
            brush = Brush.linearGradient(
                listOf(Color(0xFF2E0A6E), Color(0xFF0369A1)),
                start = Offset(c.x - 18f, c.y - 18f),
                end = Offset(c.x + 18f, c.y + 18f)
            ),
            radius = 18f, center = c
        )

        val users = listOf(
            Offset(c.x - r * 0.55f, c.y - r * 0.45f) to Color(0xFF0369A1),
            Offset(c.x + r * 0.55f, c.y - r * 0.35f) to Color(0xFF4C1D95),
            Offset(c.x - r * 0.50f, c.y + r * 0.50f) to Color(0xFF7F77DD),
            Offset(c.x + r * 0.48f, c.y + r * 0.50f) to Color(0xFF38BDF8)
        )
        users.forEach { (pos, color) ->
            drawCircle(color, 16f, pos)
        }
    }
}

@Composable
private fun OfflineIllustration() {
    Canvas(modifier = Modifier.size(200.dp)) {
        val w = size.width
        val h = size.height

        drawRoundRect(Color(0xFF4C1D95), Offset(w * 0.08f, h * 0.25f), Size(w * 0.30f, h * 0.50f), CornerRadius(14f))
        drawRoundRect(Color.White.copy(0.5f), Offset(w * 0.13f, h * 0.35f), Size(w * 0.20f, 6f), CornerRadius(3f))
        drawRoundRect(Color.White.copy(0.35f), Offset(w * 0.13f, h * 0.42f), Size(w * 0.15f, 6f), CornerRadius(3f))

        drawRoundRect(Color(0xFF0369A1), Offset(w * 0.62f, h * 0.25f), Size(w * 0.30f, h * 0.50f), CornerRadius(14f))
        drawRoundRect(Color.White.copy(0.5f), Offset(w * 0.67f, h * 0.35f), Size(w * 0.20f, 6f), CornerRadius(3f))
        drawRoundRect(Color.White.copy(0.35f), Offset(w * 0.67f, h * 0.42f), Size(w * 0.16f, 6f), CornerRadius(3f))

        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))

        val wave1 = Path().apply {
            moveTo(w * 0.40f, h * 0.44f)
            quadraticBezierTo(w * 0.50f, h * 0.36f, w * 0.60f, h * 0.44f)
        }
        drawPath(wave1, Color(0xFFC084FC), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))

        val wave2 = Path().apply {
            moveTo(w * 0.40f, h * 0.54f)
            quadraticBezierTo(w * 0.50f, h * 0.62f, w * 0.60f, h * 0.54f)
        }
        drawPath(wave2, Color(0xFF38BDF8), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
    }
}

@Composable
private fun SecureIllustration() {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(200.dp)) {
        val w = size.width
        val h = size.height
        val c = Offset(w / 2, h / 2)

        drawCircle(primary.copy(alpha = 0.08f), w * 0.40f, c)

        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(Color(0xFF2E0A6E), Color(0xFF4C1D95), Color(0xFF0369A1)),
                start = Offset(w * 0.30f, h * 0.22f),
                end = Offset(w * 0.70f, h * 0.72f)
            ),
            topLeft = Offset(w * 0.28f, h * 0.22f),
            size = Size(w * 0.44f, h * 0.56f),
            cornerRadius = CornerRadius(20f)
        )

        val check = Path().apply {
            moveTo(w * 0.39f, h * 0.50f)
            lineTo(w * 0.47f, h * 0.58f)
            lineTo(w * 0.61f, h * 0.40f)
        }
        drawPath(check, Color.White, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        drawCircle(Color(0xFF1D9E75), 8f, Offset(w * 0.50f, h * 0.16f))
        drawCircle(Color(0xFF38BDF8), 6f, Offset(w * 0.22f, h * 0.35f))
        drawCircle(Color(0xFFC084FC), 6f, Offset(w * 0.78f, h * 0.35f))
    }
}

@Composable
private fun FilesIllustration() {
    Canvas(modifier = Modifier.size(200.dp)) {
        val w = size.width
        val h = size.height

        drawRoundRect(Color(0xFF4C1D95), Offset(w * 0.10f, h * 0.25f), Size(w * 0.30f, h * 0.50f), CornerRadius(12f))
        drawRoundRect(Color.White.copy(0.5f), Offset(w * 0.15f, h * 0.35f), Size(w * 0.20f, 5f), CornerRadius(3f))
        drawRoundRect(Color.White.copy(0.4f), Offset(w * 0.15f, h * 0.41f), Size(w * 0.15f, 5f), CornerRadius(3f))
        drawRoundRect(Color.White.copy(0.4f), Offset(w * 0.15f, h * 0.47f), Size(w * 0.18f, 5f), CornerRadius(3f))

        drawRoundRect(Color(0xFF0369A1), Offset(w * 0.60f, h * 0.25f), Size(w * 0.30f, h * 0.50f), CornerRadius(12f))
        drawRoundRect(Color.White.copy(0.5f), Offset(w * 0.65f, h * 0.35f), Size(w * 0.20f, 5f), CornerRadius(3f))
        drawRoundRect(Color.White.copy(0.4f), Offset(w * 0.65f, h * 0.41f), Size(w * 0.15f, 5f), CornerRadius(3f))
        drawRoundRect(Color.White.copy(0.4f), Offset(w * 0.65f, h * 0.47f), Size(w * 0.18f, 5f), CornerRadius(3f))

        val lineY = h * 0.50f
        drawLine(
            brush = Brush.linearGradient(listOf(Color(0xFFC084FC), Color(0xFF38BDF8))),
            start = Offset(w * 0.42f, lineY),
            end = Offset(w * 0.58f, lineY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        val arrow = Path().apply {
            moveTo(w * 0.58f, lineY)
            lineTo(w * 0.53f, lineY - 8f)
            lineTo(w * 0.53f, lineY + 8f)
            close()
        }
        drawPath(arrow, Color(0xFF38BDF8))
    }
}
