package com.example.nearme.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Device check + permissions — replaces the old PermissionScreen.
 *
 * Top section:  shows what hardware features the device supports.
 * Bottom section: steps through granting permissions → enabling Bluetooth → enabling Location.
 * Auto-skips if everything is already granted.
 */
@Composable
fun DeviceCheckScreen(onAllReady: () -> Unit) {

    val context = LocalContext.current
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // ── Feature detection (read-only) ────────────────
    val hasBluetooth = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    val hasWifiAware = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)

    // ── Permission list (depends on Android version) ─
    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
            add(Manifest.permission.CHANGE_NETWORK_STATE)
            add(Manifest.permission.ACCESS_NETWORK_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }

    // ── Mutable state for each step ──────────────────
    var permissionsGranted by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var bluetoothEnabled by remember {
        mutableStateOf(bluetoothAdapter?.isEnabled == true)
    }
    var locationEnabled by remember {
        mutableStateOf(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
    }

    // Auto-skip if everything is already ready
    LaunchedEffect(permissionsGranted, bluetoothEnabled, locationEnabled) {
        if (permissionsGranted && bluetoothEnabled && locationEnabled) {
            onAllReady()
        }
    }

    // ── Launchers ────────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        bluetoothEnabled = bluetoothAdapter?.isEnabled == true
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    // ── UI ───────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Logo
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

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Device check",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Checking what your phone supports",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Feature cards (read-only) ────────────────
        FeatureCard(
            label = "Bluetooth",
            supported = hasBluetooth
        )
        Spacer(modifier = Modifier.height(8.dp))
        FeatureCard(
            label = "Extended discovery",
            supported = hasWifiAware
        )
        Spacer(modifier = Modifier.height(8.dp))
        FeatureCard(
            label = "File transfer",
            supported = hasWifiAware
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── Summary banner ───────────────────────────
        if (hasBluetooth) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xFF1D9E75).copy(alpha = 0.10f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (hasWifiAware) "Your device supports all features"
                    else "Your device supports basic features",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF0F6E56)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── Action area (step-by-step) ───────────────
        when {
            !permissionsGranted -> {
                Text(
                    text = "NearMe needs a few permissions to find people around you. We never collect or store your data.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                GradientButton(
                    text = "Grant permissions",
                    onClick = { permissionLauncher.launch(permissions) }
                )
            }

            !bluetoothEnabled -> {
                Text(
                    text = "Please turn on Bluetooth so NearMe can find nearby phones.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                GradientButton(
                    text = "Turn on Bluetooth",
                    onClick = {
                        bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                )
            }

            !locationEnabled -> {
                Text(
                    text = "Location needs to be on for Bluetooth discovery to work. NearMe does not track your location.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                GradientButton(
                    text = "Open Location Settings",
                    onClick = {
                        locationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Helper composables ──────────────────────────────

/** A single feature compatibility card */
@Composable
private fun FeatureCard(label: String, supported: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.outline,
                    MaterialTheme.colorScheme.outline
                )
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = if (supported) "Supported" else "Not available",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (supported) Color(0xFF1D9E75) else Color(0xFFE24B4A)
            )
        }
    }
}

/** Gradient-filled button matching the brand identity */
@Composable
fun GradientButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF2E0A6E), Color(0xFF4C1D95), Color(0xFF0369A1))
                    ),
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp
            )
        }
    }
}

