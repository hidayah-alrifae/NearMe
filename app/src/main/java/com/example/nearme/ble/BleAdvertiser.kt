package com.example.nearme.ble



import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import android.annotation.SuppressLint

/**
 * BleAdvertiser broadcasts this device's presence to nearby phones.
 * It sends a small data packet containing the user's short ID,
 * display name, and status using BLE advertising.
 *
 * Other devices running NearMe can detect this broadcast
 * using BleScanner and add this user to their discovery list.
 */
class BleAdvertiser(private val context: Context) {

    // Tag used for Logcat messages — filter by "BLE_ADV" to see logs
    companion object {
        private const val TAG = "BLE_ADV"

        // Unique identifier for NearMe's BLE service
        // All NearMe devices advertise and scan using this same UUID
        // so they can recognize each other among other BLE devices
        val NEARME_SERVICE_UUID: UUID =
            UUID.fromString("0000ABCD-0000-1000-8000-00805F9B34FB")
    }

    // Reference to the phone's BLE advertiser hardware
    private var advertiser: BluetoothLeAdvertiser? = null

    // Tracks whether we are currently advertising
    private var isAdvertising = false

    /**
     * Starts broadcasting this device's presence.
     * @param shortId — last 4 chars of UUID (e.g., "d479")
     * @param displayName — user's chosen name (e.g., "Ahmed")
     * @param status — current status: 0=Available, 1=Busy, 2=Emergency
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(shortId: String, displayName: String, status: Byte = 0) {

        // Get the Bluetooth adapter from the system
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        // Check if this phone supports BLE advertising
        if (bluetoothAdapter == null || !bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.e(TAG, "BLE advertising not supported on this device")
            return
        }

        // Get the advertiser hardware
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        if (advertiser == null) {
            Log.e(TAG, "Failed to get BLE advertiser")
            return
        }

        // If already advertising, stop first to restart with new data
        if (isAdvertising) {
            stopAdvertising()
        }

        // --- Build the advertisement settings ---
        val settings = AdvertiseSettings.Builder()
            // LOW_LATENCY = broadcast frequently (every ~100ms)
            // Uses more battery but devices find you faster
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            // MEDIUM power = good balance of range and battery
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            // Keep advertising until we manually stop it
            .setConnectable(false)
            // Don't timeout — advertise indefinitely
            .setTimeout(0)
            .build()

        // --- Build the data packet ---
        // Pack user info into bytes: [status(1)] + [shortId(4)] + [name(up to 8)]
        val serviceData = buildServiceData(shortId, displayName, status)

        val data = AdvertiseData.Builder()
            // Include the NearMe service UUID so scanners can filter for our app
            .addServiceUuid(ParcelUuid(NEARME_SERVICE_UUID))
            // Attach our custom data (name, status, shortId)
            .addServiceData(ParcelUuid(NEARME_SERVICE_UUID), serviceData)
            // Don't include the device name — we send our own name in serviceData
            .setIncludeDeviceName(false)
            // Don't include TX power level — saves space in the packet
            .setIncludeTxPowerLevel(false)
            .build()

        // --- Start advertising ---
        advertiser?.startAdvertising(settings, data, advertiseCallback)
        Log.d(TAG, "Starting BLE advertising: $displayName#$shortId")
    }

    /**
     * Stops broadcasting this device's presence.
     */
    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
        Log.d(TAG, "Stopped BLE advertising")
    }

    /**
     * Packs user info into a byte array for the advertisement packet.
     * Format: [status(1 byte)] + [shortId(4 bytes)] + [name(up to 8 bytes)]
     * Total: up to 13 bytes of service data
     */
    private fun buildServiceData(shortId: String, displayName: String, status: Byte): ByteArray {
        // Limit name to 8 characters to fit in the BLE packet
        val truncatedName = displayName.take(8)

        // Create byte array: 1 (status) + 4 (shortId) + name length
        val data = ByteArray(1 + 4 + truncatedName.toByteArray().size)

        // First byte: status (0=Available, 1=Busy, 2=Emergency)
        data[0] = status

        // Next 4 bytes: shortId characters converted to bytes
        val shortIdBytes = shortId.toByteArray()
        shortIdBytes.copyInto(data, destinationOffset = 1)

        // Remaining bytes: display name converted to bytes
        val nameBytes = truncatedName.toByteArray()
        nameBytes.copyInto(data, destinationOffset = 5)

        return data
    }

    /**
     * Callback that Android calls to tell us if advertising started or failed.
     */
    private val advertiseCallback = object : AdvertiseCallback() {

        // Called when advertising starts successfully
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.d(TAG, "Advertising started successfully")
        }

        // Called when advertising fails to start
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error"
            }
            Log.e(TAG, "Advertising failed: $reason (code: $errorCode)")
        }
    }
}