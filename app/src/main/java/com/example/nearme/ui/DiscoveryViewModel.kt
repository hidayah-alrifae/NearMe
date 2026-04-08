package com.example.nearme.ui



import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nearme.ble.BleAdvertiser
import com.example.nearme.ble.BleScanner
import com.example.nearme.model.UserProfile
import com.example.nearme.util.LocalAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * DiscoveryViewModel manages the BLE advertiser and scanner.
 * It starts broadcasting this device's presence and listens
 * for nearby users. The discovered users are stored in a list
 * that the DiscoveryScreen observes and displays.
 */
class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {

    // BLE components
    private val advertiser = BleAdvertiser(application)
    private val scanner = BleScanner(application)

    // Map of discovered users — key is shortId to prevent duplicates
    private val usersMap = mutableMapOf<String, UserProfile>()

    // Observable list of nearby users — the UI watches this
    private val _nearbyUsers = MutableStateFlow<List<UserProfile>>(emptyList())
    val nearbyUsers: StateFlow<List<UserProfile>> = _nearbyUsers

    // How long before a user is removed from the list (30 seconds)
    private val staleTimeout = 30_000L

    /**
     * Starts BLE advertising and scanning.
     * Called when the Discovery screen appears.
     */
    fun startDiscovery() {
        val context = getApplication<Application>()

        // Get this device's info
        val shortId = LocalAuth.getShortId(context)
        val displayName = LocalAuth.getDisplayName(context)

        // Start broadcasting our presence
        advertiser.startAdvertising(shortId, displayName)

        // Start listening for nearby users
        scanner.startScanning { userProfile ->
            // A user was found — add or update them in the map
            usersMap[userProfile.shortId] = userProfile
            // Update the observable list
            _nearbyUsers.value = usersMap.values.toList()
        }

        // Start a cleanup job to remove stale users
        startCleanupJob()
    }

    /**
     * Stops BLE advertising and scanning.
     * Called when the Discovery screen is closed.
     */
    fun stopDiscovery() {
        advertiser.stopAdvertising()
        scanner.stopScanning()
    }

    /**
     * Periodically removes users who haven't been seen recently.
     * If a user's lastSeen timestamp is older than 30 seconds,
     * they are removed from the list — they probably walked away.
     */
    private fun startCleanupJob() {
        viewModelScope.launch {
            while (true) {
                // Wait 10 seconds between each cleanup
                delay(10_000)

                val now = System.currentTimeMillis()
                // Remove users not seen in the last 30 seconds
                val staleIds = usersMap.filter { (_, user) ->
                    now - user.lastSeen > staleTimeout
                }.keys

                if (staleIds.isNotEmpty()) {
                    staleIds.forEach { usersMap.remove(it) }
                    _nearbyUsers.value = usersMap.values.toList()
                }
            }
        }
    }

    /**
     * Called automatically when the ViewModel is destroyed.
     * Stops all BLE operations to save battery.
     */
    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }
}