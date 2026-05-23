package com.example.nearme.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.nearme.model.UserProfile
import com.example.nearme.repository.NearMeRepository
import kotlinx.coroutines.flow.StateFlow


/**
 * DiscoveryViewModel is now a thin observer.
 * It no longer owns BLE — the foreground service (NearMeService)
 * owns BLE via NearMeRepository. This ViewModel just reads the
 * already-running discovered users list from the repository.
 *
 * startDiscovery() and stopDiscovery() are kept as empty stubs
 * so DiscoveryScreen doesn't need to change at all.
 */
class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {

    // Get the singleton repository — same instance the service is using
    private val repository = NearMeRepository.getInstance(application)

    // Expose the repository's live user list directly to the UI.
    // This updates automatically whenever BLE discovers someone new
    // or the cleanup job removes a stale user.
    val nearbyUsers: StateFlow<List<UserProfile>> = repository.discoveredUsers

    // True if this phone has Wi-Fi Aware hardware.
    // show or hide the "Search Further" button.
    val wifiAwareAvailable: Boolean = repository.wifiAwareAvailable

    // True while a 30-second Wi-Fi Aware subscribe session is running.
   // DiscoveryScreen uses this to show a spinner on the button
   // and disable it to prevent duplicate subscribe sessions.
    val isExtendedSearching: StateFlow<Boolean> = repository.isExtendedSearching

    // Called by DiscoveryScreen when user taps "Search Further"
    fun startExtendedSearch() {
        repository.startExtendedSearch()
    }

    // Called if user wants to manually stop the extended search
    // (optional it auto-stops after 30 seconds anyway)
    fun stopExtendedSearch() {
        repository.stopExtendedSearch()
    }

    /**
     * No-op — BLE is started by NearMeService, not this ViewModel.
     * Kept so DiscoveryScreen's DisposableEffect doesn't need to change.
     */
    fun startDiscovery() {
        // BLE is managed by NearMeService via NearMeRepository
    }

    /**
     * No-op — BLE is stopped by NearMeService, not this ViewModel.
     * Kept so DiscoveryScreen's DisposableEffect doesn't need to change.
     */
    fun stopDiscovery() {
        // BLE is managed by NearMeService via NearMeRepository
    }
}