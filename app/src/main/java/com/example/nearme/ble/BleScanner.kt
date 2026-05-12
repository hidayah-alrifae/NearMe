package com.example.nearme.ble



import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.example.nearme.model.UserProfile

/**
 * BleScanner listens for nearby NearMe devices.
 * It filters BLE advertisements by NearMe's service UUID,
 * parses the data packet, and creates a UserProfile for each
 * discovered user.
 */
class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BLE_SCAN"
    }

    // Reference to the phone's BLE scanner hardware
    private var scanner: BluetoothLeScanner? = null

    // Tracks whether we are currently scanning
    private var isScanning = false

    // Callback to notify the app when a user is discovered
    private var onUserFound: ((UserProfile) -> Unit)? = null

    /**
     * Starts scanning for nearby NearMe devices.
     * @param onUserFound — called every time a nearby user is detected
     */
    @SuppressLint("MissingPermission")
    // scanMode: "LOW_POWER" for background (battery friendly), "LOW_LATENCY" for foreground (fast)
    fun startScanning(scanMode: String = "LOW_LATENCY", onUserFound: (UserProfile) -> Unit) {

        this.onUserFound = onUserFound

        // Get the Bluetooth adapter from the system
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not available")
            return
        }

        // Get the scanner hardware
        scanner = bluetoothAdapter.bluetoothLeScanner

        if (scanner == null) {
            Log.e(TAG, "BLE scanner not available")
            return
        }

        // If already scanning, stop first
        if (isScanning) {
            stopScanning()
        }

        // --- Build scan filter ---
        // Only detect devices advertising with NearMe's UUID
        // This ignores all other BLE devices (headphones, watches, etc.)
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleAdvertiser.NEARME_SERVICE_UUID))
            .build()


        // Pick the scan mode based on the parameter passed in
        val mode = if (scanMode == "LOW_POWER") {
            ScanSettings.SCAN_MODE_LOW_POWER   // Background: scan less often, save battery
        } else {
            ScanSettings.SCAN_MODE_LOW_LATENCY // Foreground: scan frequently, fast discovery
        }

        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        // --- Start scanning ---
        scanner?.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        Log.d(TAG, "Started BLE scanning")
    }

    /**
     * Stops scanning for nearby devices.
     */
    @SuppressLint("MissingPermission")
    fun stopScanning() {
        scanner?.stopScan(scanCallback)
        isScanning = false
        Log.d(TAG, "Stopped BLE scanning")
    }

    /**
     * Callback that Android calls every time a BLE advertisement is detected.
     */
    private val scanCallback = object : ScanCallback() {

        // Called when a single device is detected
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return // If result is null, ignore

            // Get the raw service data from the advertisement
            val serviceData = result.scanRecord
                ?.getServiceData(ParcelUuid(BleAdvertiser.NEARME_SERVICE_UUID))

            if (serviceData == null || serviceData.size < 5) {
                // Invalid packet — too small to contain our data
                return
            }

            // Parse the data packet
            val userProfile = parseServiceData(serviceData, result.rssi)

            if (userProfile != null) {
                Log.d(TAG, "Found user: ${userProfile.displayName}#${userProfile.shortId} " +
                        "status=${userProfile.status} rssi=${userProfile.rssi}")
                // Notify the app about the discovered user
                onUserFound?.invoke(userProfile)
            }
        }

        // Called when scanning fails
        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    /**
     * Parses the raw byte array from a BLE advertisement into a UserProfile.
     * Format: [status(1 byte)] + [shortId(4 bytes)] + [name(remaining bytes)]
     */
    private fun parseServiceData(data: ByteArray, rssi: Int): UserProfile? {
        return try {
            // Byte 0: status (0=Available, 1=Busy, 2=Emergency)
            val statusByte = data[0]
            val status = when (statusByte.toInt()) {
                0 -> "Available"
                1 -> "Calling"
                2 -> "Emergency"
                else -> "Available"
            }

            // Bytes 1-4: shortId
            val shortId = String(data, 1, 4)

            // Bytes 5+: display name (remaining bytes)
            val nameLength = data.size - 5
            val displayName = if (nameLength > 0) {
                String(data, 5, nameLength)
            } else {
                "User#$shortId"
            }

            // Create and return the UserProfile
            UserProfile(
                shortId = shortId,
                displayName = displayName,
                status = status,
                rssi = rssi,
                lastSeen = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse BLE data: ${e.message}")
            null
        }
    }
}