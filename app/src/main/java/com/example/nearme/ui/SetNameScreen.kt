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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nearme.R
import com.example.nearme.util.LocalAuth

/**
 * Shown once on first launch, after permissions are granted and before
 * the user lands on Discovery. The name typed here becomes the user's
 * broadcast identity — visible to others immediately when discovery starts.
 *
 * The short ID is shown read-only as information so the user knows the
 * tag that disambiguates them from other people with the same name.
 */
@Composable
fun SetNameScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    val shortId = remember { LocalAuth.getShortId(context) }
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val canContinue = trimmed.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        // Logo — same gradient as the rest of the app
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF2E0A6E), Color(0xFF4C1D95), Color(0xFF0369A1))
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            RadarLogo(size = 40)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.set_name_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.set_name_subtitle),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Name input — caps at 20 chars to match the SettingsScreen edit dialog
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 20) name = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.set_name_label)) },
            placeholder = { Text(stringResource(R.string.set_name_placeholder)) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Short ID — informational card, not editable
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.set_name_id_label),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "#$shortId",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.set_name_id_explain),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(24.dp))

        // Continue button — gradient pill when enabled, flat when disabled
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    brush = if (canContinue) {
                        Brush.linearGradient(
                            listOf(Color(0xFF2E0A6E), Color(0xFF4C1D95), Color(0xFF0369A1))
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            TextButton(
                onClick = {
                    if (canContinue) {
                        LocalAuth.setDisplayName(context, trimmed)
                        onSaved()
                    }
                },
                enabled = canContinue,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = stringResource(R.string.set_name_continue),
                    color = if (canContinue) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
