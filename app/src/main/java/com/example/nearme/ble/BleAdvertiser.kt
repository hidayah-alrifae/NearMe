package com.example.nearme.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BleAdvertiser broadcasts this device's presence to nearby phones.
 * Automatically selects the best BLE mode based on phone hardware:
 * - BLE 5.0 (extended advertising) on newer phones: longer range, more data
 * - BLE 4.x (legacy advertising) on older phones: compatible with all devices
 */
class BleAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "BLE_ADV"

        // NearMe's unique BLE service identifier
        val NEARME_SERVICE_UUID: UUID =
            UUID.fromString("0000ABCD-0000-1000-8000-00805F9B34FB")
    }

    // Reference to the phone's BLE advertiser hardware
    private var advertiser: BluetoothLeAdvertiser? = null

    // Tracks whether we are currently advertising
    private var isAdvertising = false

    // Tracks which mode we're using
    private var usingExtendedAdvertising = false

    // Reference to the extended advertising set (BLE 5.0 only)
    private var currentAdvertisingSet: AdvertisingSet? = null

    /**
     * Starts broadcasting this device's presence.
     * Automatically picks BLE 5.0 or 4.x based on hardware support.
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(shortId: String, displayName: String, status: Byte = 0) {

        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not available")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        if (advertiser == null) {
            Log.e(TAG, "Failed to get BLE advertiser")
            return
        }

        // Stop any existing advertising
        if (isAdvertising) {
            stopAdvertising()
        }

        // Build the data packet
        val serviceData = buildServiceData(shortId, displayName, status)

        // Check if this phone supports BLE 5.0 extended advertising
        // Requires Android 8.0+ AND hardware support
        // Always start legacy advertising for compatibility with all devices
        startLegacyAdvertising(serviceData, shortId, displayName)

        // Additionally start extended advertising on supported phones for longer range
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bluetoothAdapter.isLeExtendedAdvertisingSupported) {
            startExtendedAdvertising(serviceData, shortId, displayName)
        }
    }

    /**
     * BLE 5.0 Extended Advertising — longer range, more data capacity.
     * Only works on phones with BLE 5.0 hardware.
     */
    @SuppressLint("MissingPermission")
    private fun startExtendedAdvertising(serviceData: ByteArray, shortId: String, displayName: String) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // Extended advertising parameters
        val parameters = AdvertisingSetParameters.Builder()
            // Not legacy — use BLE 5.0 extended mode

            .setPrimaryPhy(1)  // PHY_LE_1M = 1
            .setSecondaryPhy(2)  // PHY_LE_2M = 2
            // Broadcast frequently for fast discovery
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            // Maximum power for longest range
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            // Not connectable — we only broadcast data
            .setConnectable(false)
            .setScannable(false)
            .build()

        // Build the data packet
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(NEARME_SERVICE_UUID))
            .addServiceData(ParcelUuid(NEARME_SERVICE_UUID), serviceData)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        // Callback for extended advertising
        val extendedCallback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: AdvertisingSet?,
                txPower: Int,
                status: Int
            ) {
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    isAdvertising = true
                    usingExtendedAdvertising = true
                    currentAdvertisingSet = advertisingSet
                    Log.d(TAG, "BLE 5.0 extended advertising started: $displayName#$shortId (txPower=$txPower)")
                } else {
                    // BLE 5.0 failed — fall back to legacy
                    Log.w(TAG, "BLE 5.0 failed (status=$status), falling back to legacy")
                    startLegacyAdvertising(serviceData, shortId, displayName)
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
                isAdvertising = false
                usingExtendedAdvertising = false
                currentAdvertisingSet = null
                Log.d(TAG, "BLE 5.0 extended advertising stopped")
            }
        }

        advertiser?.startAdvertisingSet(parameters, data, null, null, null, extendedCallback)
        Log.d(TAG, "Starting BLE 5.0 extended advertising: $displayName#$shortId")
    }

    /**
     * BLE 4.x Legacy Advertising — works on all phones.
     * Shorter range but maximum compatibility.
     */
    @SuppressLint("MissingPermission")
    private fun startLegacyAdvertising(serviceData: ByteArray, shortId: String, displayName: String) {

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(NEARME_SERVICE_UUID))
            .addServiceData(ParcelUuid(NEARME_SERVICE_UUID), serviceData)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        advertiser?.startAdvertising(settings, data, legacyCallback)
        Log.d(TAG, "Starting BLE 4.x legacy advertising: $displayName#$shortId")
    }

    /**
     * Stops broadcasting this device's presence.
     * Automatically stops the correct mode (5.0 or 4.x).
     */
    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        // Stop legacy advertising
        advertiser?.stopAdvertising(legacyCallback)

        // Stop extended advertising if it was running
        if (usingExtendedAdvertising) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && currentAdvertisingSet != null) {
                advertiser?.stopAdvertisingSet(object : AdvertisingSetCallback() {})
                currentAdvertisingSet = null
            }
        }

        isAdvertising = false
        usingExtendedAdvertising = false
        Log.d(TAG, "Stopped BLE advertising")
    }

    /**
     * Packs user info into a byte array.
     * Format: [status(1 byte)] + [shortId(4 bytes)] + [name(up to 8 bytes)]
     */
    private fun buildServiceData(shortId: String, displayName: String, status: Byte): ByteArray {
        val truncatedName = displayName.take(8)
        val data = ByteArray(1 + 4 + truncatedName.toByteArray().size)

        data[0] = status

        val shortIdBytes = shortId.toByteArray()
        shortIdBytes.copyInto(data, destinationOffset = 1)

        val nameBytes = truncatedName.toByteArray()
        nameBytes.copyInto(data, destinationOffset = 5)

        return data
    }

    /**
     * Callback for BLE 4.x legacy advertising.
     */
    private val legacyCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            usingExtendedAdvertising = false
            Log.d(TAG, "BLE 4.x legacy advertising started successfully")
        }

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
            Log.e(TAG, "BLE 4.x advertising failed: $reason (code: $errorCode)")
        }
    }
}