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