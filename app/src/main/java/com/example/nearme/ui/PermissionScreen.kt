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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * First screen the user sees.
 * Step 1: Requests all required permissions.
 * Step 2: Asks the user to turn on Bluetooth.
 * Step 3: Asks the user to turn on Location.
 * Skips automatically if everything is already set.
 */
@Composable
fun PermissionScreen(onPermissionsGranted: () -> Unit) {

    val context = LocalContext.current

    // System service references
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Build the list of permissions based on Android version
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
                add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }

    // Check if permissions are already granted
    var permissionsGranted by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    // Check current Bluetooth state
    var bluetoothEnabled by remember {
        mutableStateOf(bluetoothAdapter?.isEnabled == true)
    }

    // Check current Location state
    var locationEnabled by remember {
        mutableStateOf(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
    }

    // If everything is already set, skip this screen
    LaunchedEffect(permissionsGranted, bluetoothEnabled, locationEnabled) {
        if (permissionsGranted && bluetoothEnabled && locationEnabled) {
            onPermissionsGranted()
        }
    }

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
    }

    // Bluetooth enable launcher
    val bluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        bluetoothEnabled = bluetoothAdapter?.isEnabled == true
    }

    // Location enable launcher (returns from Settings, re-check state)
    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        locationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    // UI Layout
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "NearMe",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!permissionsGranted) {
            // Step 1: Request permissions
            Text(
                text = "NearMe needs Bluetooth and Location permissions to discover nearby users and enable offline communication. No location data is collected or stored.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { permissionLauncher.launch(permissions) }
            ) {
                Text("Grant Permissions")
            }

        } else if (!bluetoothEnabled) {
            // Step 2: Turn on Bluetooth
            Text(
                text = "Bluetooth must be turned on so nearby users can find you. NearMe uses Bluetooth to discover people around you — it should stay on for the app to work properly.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    bluetoothLauncher.launch(enableBtIntent)
                }
            ) {
                Text("Turn On Bluetooth")
            }

        } else if (!locationEnabled) {
            // Step 3: Turn on Location
            Text(
                text = "Location must be turned on for Bluetooth scanning to work. This is an Android system requirement — NearMe does not track or store your location.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    val locationIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    locationLauncher.launch(locationIntent)
                }
            ) {
                Text("Turn On Location")
            }
        }
    }
}