package com.example.nearme.model



/**
 * Represents a nearby discovered user.
 * Contains all information received from their BLE advertisement.
 */
data class UserProfile(
    // Unique identifier (last 4 chars of UUID, e.g., "a4f2")
    val shortId: String,

    // User's chosen display name (e.g., "Ahmed")
    val displayName: String,

    // Current status: "Available", "Busy", or "Emergency"
    val status: String = "Available",

    // Signal strength indicator (used to estimate proximity)
    val rssi: Int = 0,

    // Timestamp of last discovery (to remove stale users)
    val lastSeen: Long = System.currentTimeMillis(),
    // How this user was discovered — determines which transport to use.
    //   "BLE"        → chat via NC (Nearby Connections / Bluetooth Classic)
    //   "WIFI_AWARE" → chat via NDP (NAN Data Path / Wi-Fi)
    val discoverySource: String = "BLE"
)