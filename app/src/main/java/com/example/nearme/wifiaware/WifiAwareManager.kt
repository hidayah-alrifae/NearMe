package com.example.nearme.wifiaware



import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager as SystemWifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.nearme.model.UserProfile
import android.annotation.SuppressLint


class WifiAwareManager(private val context: Context) {

    companion object {
        private const val TAG = "WIFI_AWARE"

        // Service name used to identify NearMe's Wi-Fi Aware service.
        // Other NearMe phones filter for this exact name during subscribe.

        private const val SERVICE_NAME = "nearme"

        // How long a subscribe session runs before auto-stopping (milliseconds).
        private const val SUBSCRIBE_TIMEOUT_MS = 30_000L
    }


    // Checked once at construction time. If false, ALL methods are no-ops.
    val isSupported: Boolean = checkHardwareSupport()

    // System Service
    // The Android system's Wi-Fi Aware manager — null if not supported.
    private val systemManager: SystemWifiAwareManager? =
        if (isSupported) {
            context.getSystemService(Context.WIFI_AWARE_SERVICE) as? SystemWifiAwareManager
        } else null

    // Session State
    // The WifiAwareSession is obtained by calling attach() on the system
    // manager. It must be kept alive for the entire lifetime of our service.
    // If the session is lost (Wi-Fi turned off, system reclaims it), we
    // re-attach when startPublishing() is called again.
    private var session: WifiAwareSession? = null

    // Discovery Sessions
    // A publish session and subscribe session are separate objects.
    // Each has its own lifecycle and can be started/stopped independently.
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    //State Tracking
    private var isPublishing = false
    private var isSubscribing = false

    // Whether publishing was active before we paused it for NC chat.
    // Used by resumePublishing() to know if it should restart.
    private var wasPublishingBeforePause = false

    // Our identity set by startPublishing(), used in the service info payload
    private var localShortId: String = ""
    private var localDisplayName: String = ""

    // Handler for posting delayed operations (subscribe timeout)
    private val mainHandler = Handler(Looper.getMainLooper())

    // The runnable that auto-stops subscribing after SUBSCRIBE_TIMEOUT_MS
    private val subscribeTimeoutRunnable = Runnable {
        Log.d(TAG, "Subscribe timeout reached (${SUBSCRIBE_TIMEOUT_MS / 1000}s) — stopping")
        stopSubscribing()
    }

    // Callbacks
    // Settable by the repository, same pattern as NearbyManager.
    // onUserDiscovered is called every time a Wi-Fi Aware publisher is found.
    var onUserDiscovered: ((UserProfile) -> Unit)? = null

    // Called when subscribe finishes (timeout or manual stop).
    // The repository uses this to update the RadioState back to STANDBY.
    var onSubscribeStopped: (() -> Unit)? = null

    //  Wi-Fi Aware Availability Receiver
    // Wi-Fi Aware availability can change at runtime (e.g. Wi-Fi turned off,
    // Location turned off, airplane mode).  register a receiver to detect
    // these changes and re-attach when availability returns.
    private var availabilityReceiver: BroadcastReceiver? = null

    //  Hardware Check


      //Checks if this phone supports Wi-Fi Aware at the hardware level.


    private fun checkHardwareSupport(): Boolean {

        val hasFeature = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_WIFI_AWARE
        )
        Log.d(TAG, "Wi-Fi Aware hardware supported: $hasFeature")
        return hasFeature
    }

    /**
     * Checks if Wi-Fi Aware is currently available at runtime.

     * Always call this before starting publish/subscribe operations.
     */
    fun isAvailable(): Boolean {
        if (!isSupported) return false
        return systemManager?.isAvailable == true
    }

    // Session Management

    /**
     * Attaches to the Wi-Fi Aware subsystem to obtain a WifiAwareSession.
     * The session is the gateway to all Wi-Fi Aware operations — you
     * cannot publish or subscribe without one. We attach once and keep
     * the session alive as long as the foreground service runs.
     * If attachment fails (e.g. Wi-Fi is off), we log it and return.
     * The availability receiver will trigger re-attachment when
     * conditions improve.
     */
    private fun attachSession(onSessionReady: () -> Unit) {
        if (!isSupported || !isAvailable()) {
            Log.w(TAG, "Cannot attach — Wi-Fi Aware not available")
            return
        }

        // If we already have a valid session, skip re-attachment
        if (session != null) {
            onSessionReady()
            return
        }

        Log.d(TAG, "Attaching to Wi-Fi Aware subsystem...")

        systemManager?.attach(object : AttachCallback() {

            /**
             * Called when the Wi-Fi Aware subsystem is ready.
             * We now have a session and can start publishing/subscribing.
             */
            override fun onAttached(wifiAwareSession: WifiAwareSession) {
                Log.d(TAG, "Wi-Fi Aware session attached successfully")
                session = wifiAwareSession
                onSessionReady()
            }


            override fun onAttachFailed() {
                Log.e(TAG, "Wi-Fi Aware attach FAILED — will retry when available")
                session = null
            }



        }, mainHandler)
    }

    /**
     * Registers a BroadcastReceiver that listens for Wi-Fi Aware
     * availability changes.
     * When Wi-Fi Aware becomes availabl we re-attach and restart publishing. This makes NearMe resilient
     * to Wi-Fi toggle events, similar to how BluetoothStateReceiver
     * handles Bluetooth on/off in NearMeService.
     */
    fun registerAvailabilityReceiver() {
        if (!isSupported) return

        availabilityReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Wi-Fi Aware availability changed
                if (isAvailable()) {
                    Log.d(TAG, "Wi-Fi Aware became AVAILABLE — re-attaching")
                    // Re-start publishing if we were publishing before
                    if (localShortId.isNotEmpty()) {
                        startPublishing(localShortId, localDisplayName)
                    }
                } else {
                    Log.d(TAG, "Wi-Fi Aware became UNAVAILABLE")
                    // Clean up stale references — session is dead
                    publishSession = null
                    subscribeSession = null
                    session = null
                    isPublishing = false
                    isSubscribing = false
                }
            }
        }

        val filter = IntentFilter(SystemWifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
        context.registerReceiver(availabilityReceiver, filter)
        Log.d(TAG, "Availability receiver registered")
    }

    /**
     * Unregisters the availability receiver.
     * Called by NearMeService.onDestroy() via repository.stopWifiAware().
     */
    fun unregisterAvailabilityReceiver() {
        availabilityReceiver?.let {
            try {
                context.unregisterReceiver(it)
                Log.d(TAG, "Availability receiver unregistered")
            } catch (e: IllegalArgumentException) {
                // Already unregistered — safe to ignore
                Log.w(TAG, "Receiver was already unregistered")
            }
        }
        availabilityReceiver = null
    }

    // Publishing (Being Discoverable)

    /**
     * Starts Wi-Fi Aware publishing — makes this phone discoverable
     * to other NearMe phones at extended range (50–100m).
     *
     * Publishing is always-on during STANDBY state. It uses
     * PUBLISH_TYPE_UNSOLICITED which actively broadcasts the service
     * during NAN Discovery Windows (every ~512ms). This is analogous
     * to BLE advertising we're always saying "I'm here."
     *
     * The service specific info contains our identity:
     *   "shortId|displayName"  →  e.g. "3fa7|Ahmed"
     *
     * @param shortId Our 4-character unique identifier
     * @param displayName Our chosen display name
     */
    fun startPublishing(shortId: String, displayName: String) {
        if (!isSupported) {
            Log.d(TAG, "Publish skipped  hardware not supported")
            return
        }

        // Save identity for re-publishing after session loss
        localShortId = shortId
        localDisplayName = displayName

        if (!isAvailable()) {
            Log.w(TAG, "Publish deferred  Wi-Fi Aware not available right now")
            // The availability receiver will call startPublishing() when ready
            return
        }

        // Attach first (no-op if already attached), then publish
        @SuppressLint("MissingPermission")
        attachSession {
            val currentSession = session
            if (currentSession == null) {
                Log.e(TAG, "Session is null after attach  cannot publish")
                return@attachSession
            }

            // Build the service identity payload
            // Format matches NC broadcast name: "shortId|displayName"
            val serviceInfo = "$shortId|$displayName".toByteArray()

            // Build the publish configuration
            val publishConfig = PublishConfig.Builder()
                // Service name  subscribers filter on this exact string
                .setServiceName(SERVICE_NAME)
                // Our identity payload  subscribers read this to learn who we are
                .setServiceSpecificInfo(serviceInfo)

                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .build()

            Log.d(TAG, "Starting publish: $shortId|$displayName")

            currentSession.publish(publishConfig, object : DiscoverySessionCallback() {

                /**
                 * Called when the publish session is successfully started.
                 * We save the session handle so we can stop it later.
                 */

                override fun onPublishStarted(session: PublishDiscoverySession) {
                    Log.d(TAG, "Publishing started successfully")
                    publishSession = session
                    isPublishing = true
                }

                /**
                 * Called when a subscriber sends us a message (we don't use this).
                 * In NearMe, all data transfer goes through NC, not Wi-Fi Aware.
                 * Wi-Fi Aware is discovery-only.
                 */
                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    // Ignored — we don't use Wi-Fi Aware for messaging
                    Log.d(TAG, "Publish received message (ignored — discovery only)")
                }


                 //Called when the publish session is terminated by the system.

                override fun onSessionTerminated() {
                    Log.w(TAG, "Publish session terminated by system")
                    publishSession = null
                    isPublishing = false
                    // Session itself may be dead too
                    session = null
                }

            }, mainHandler)
        }

    }

    /**
     * Stops Wi-Fi Aware publishing.
     * Called when the service shuts down or as part of cleanup.
     */
    fun stopPublishing() {
        publishSession?.close()
        publishSession = null
        isPublishing = false
        Log.d(TAG, "Publishing stopped")
    }

    /**
     * Pauses publishing during an active NC chat.
     * NC uses Wi-Fi Direct for data transport. Wi-Fi Aware and Wi-Fi
     * Direct share the Wi-Fi radio and can conflict on many chipsets.
     * Pausing Wi-Fi Aware during NC chat prevents:
     *    NC connection failures (WIFI_LAN ACCEPT_CONNECTION_FAILED)
     *    Throughput degradation in the NC data channel
     *    Wi-Fi Aware session drops due to radio contention
     *
     * Called by NearMeRepository when radioState transitions to NC_CHAT.
     */
    fun pausePublishing() {
        wasPublishingBeforePause = isPublishing
        if (isPublishing) {
            stopPublishing()
            Log.d(TAG, "Publishing PAUSED for NC chat (Wi-Fi Direct conflict avoidance)")
        }
        // Also stop subscribing if it was running
        if (isSubscribing) {
            stopSubscribing()
        }
    }

    /**
     * Resumes publishing after an NC chat ends.
     * Only restarts if we were publishing before the pause.
     * Called by NearMeRepository when radioState transitions back to STANDBY.
     */
    fun resumePublishing() {
        if (wasPublishingBeforePause && localShortId.isNotEmpty()) {
            Log.d(TAG, "Resuming publishing after NC chat ended")
            startPublishing(localShortId, localDisplayName)
            wasPublishingBeforePause = false
        }
    }

    //  Subscribing (Searching for Others)

    /**
     * Starts a 30-second Wi-Fi Aware subscribe session to find
     * NearMe users at extended range.
     *
     * Subscribing is on-demand only (not always-on) because:
     * 1. It uses more power than publishing  the phone must actively
         process incoming service discovery frames
     * 2. BLE discovery handles the common case (nearby users <30m)
     * 3. Extended search is only needed when BLE finds no one, meaning
     *    users are probably farther away
     *
     * We use SUBSCRIBE_TYPE_PASSIVE which listens silently for
     * unsolicited publishes. This is the correct pairing with our
     * PUBLISH_TYPE_UNSOLICITED — the publisher broadcasts, the
     * subscriber passively listens. No extra probe frames needed.
     *
     * The subscribe auto-stops after SUBSCRIBE_TIMEOUT_MS (30 seconds)
     * to conserve battery. The user can tap "Search Further" again
     * to restart it.
     *
     * Discovered users are reported via the onUserDiscovered callback,
     * which feeds into the same usersMap as BLE — no duplicate entries.
     */
    fun startSubscribing() {
        if (!isSupported) {
            Log.d(TAG, "Subscribe skipped  hardware not supported")
            return
        }

        if (isSubscribing) {
            Log.d(TAG, "Already subscribing  ignoring duplicate request")
            return
        }

        if (!isAvailable()) {
            Log.w(TAG, "Subscribe failed  Wi-Fi Aware not available")
            onSubscribeStopped?.invoke()
            return
        }

        // Attach first (no-op if already attached), then subscribe
        @SuppressLint("MissingPermission")
        attachSession {
            val currentSession = session
            if (currentSession == null) {
                Log.e(TAG, "Session is null after attach — cannot subscribe")
                onSubscribeStopped?.invoke()
                return@attachSession
            }

            // Build the subscribe configuration
            val subscribeConfig = SubscribeConfig.Builder()
                // Filter for NearMe's service name — only hear NearMe publishers
                .setServiceName(SERVICE_NAME)
                // PASSIVE = listen silently for unsolicited publishes
                // Matches our PUBLISH_TYPE_UNSOLICITED on the other side
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .build()

            Log.d(TAG, "Starting subscribe (${SUBSCRIBE_TIMEOUT_MS / 1000}s timeout)...")

            currentSession.subscribe(subscribeConfig, object : DiscoverySessionCallback() {

                /**
                 * Called when the subscribe session is successfully started.
                 * We save the session handle and start the timeout timer.
                 */
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    Log.d(TAG, "Subscribe session started")
                    subscribeSession = session
                    isSubscribing = true

                    // Schedule auto-stop after 30 seconds
                    mainHandler.postDelayed(subscribeTimeoutRunnable, SUBSCRIBE_TIMEOUT_MS)
                }

                /**
                 * Called when a NearMe publisher is discovered nearby.
                 *
                 * This is the main event  a phone running NearMe was found
                 * at extended range via Wi-Fi Aware. We parse the service info
                 * to extract the user's identity and create a UserProfile,
                 * then report it to the repository via the callback.

                 */
                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: List<ByteArray>
                ) {
                    // Parse the service info to extract user identity
                    val userProfile = parseServiceInfo(serviceSpecificInfo)

                    if (userProfile != null) {
                        Log.d(TAG, "Discovered user via Wi-Fi Aware: " +
                                "${userProfile.displayName}#${userProfile.shortId}")
                        // Report to repository — feeds into same usersMap as BLE
                        onUserDiscovered?.invoke(userProfile)
                    } else {
                        Log.w(TAG, "Failed to parse Wi-Fi Aware service info")
                    }
                }

                /**
                 * Called when a previously discovered service is lost.
                 * This happens when the publisher goes out of range or
                 * stops publishing. We don't need to handle this explicitly
                 * because the repository's stale user cleanup job (every 10s,
                 * 30s timeout) will remove the user if they're no longer seen
                 * by any discovery channel (BLE or Wi-Fi Aware).
                 */
                override fun onServiceLost(
                    peerHandle: PeerHandle,
                    reason: Int
                ) {
                    Log.d(TAG, "Service lost (reason=$reason) — " +
                            "stale cleanup will handle removal")
                }

                /**
                 * Called when the subscribe session is terminated by the system.
                 * We clean up and notify the repository.
                 */
                override fun onSessionTerminated() {
                    Log.w(TAG, "Subscribe session terminated by system")
                    cleanupSubscribe()
                }

            }, mainHandler)
        }
    }

    /**
     * Stops the current subscribe session.
     * Called when the 30-second timeout fires, or manually by the user/repository.
     */
    fun stopSubscribing() {
        if (!isSubscribing) return
        cleanupSubscribe()
        Log.d(TAG, "Subscribe stopped")
    }

    /**
     * Internal cleanup for subscribe  removes timeout, closes session,
     * resets state, and notifies the repository.
     */
    private fun cleanupSubscribe() {
        // Cancel the timeout if it hasn't fired yet
        mainHandler.removeCallbacks(subscribeTimeoutRunnable)
        // Close the subscribe session
        subscribeSession?.close()
        subscribeSession = null
        isSubscribing = false
        // Notify repository so it can update RadioState
        onSubscribeStopped?.invoke()
    }

    //  Service Info Parsing

    /**
     * Parses the service specific info from a Wi-Fi Aware publisher
     * into a UserProfile.
     *
     * Expected format: "shortId|displayName" as UTF-8 bytes
     * Example: "3fa7|Ahmed" → UserProfile(shortId="3fa7", displayName="Ahmed")
     *
     * The UserProfile created here is identical in structure to those
     * created by BleScanner.parseServiceData(). This means Wi-Fi Aware
     * discovered users merge seamlessly into the same usersMap —
     * keyed by shortId, no duplicates.
     *
     * @param serviceInfo Raw bytes from the publisher's service specific info
     * @return A UserProfile, or null if parsing fails
     */
    private fun parseServiceInfo(serviceInfo: ByteArray): UserProfile? {
        return try {
            val infoString = String(serviceInfo, Charsets.UTF_8)

            // Split on "|" — format is "shortId|displayName"
            val parts = infoString.split("|", limit = 2)
            if (parts.size < 2) {
                Log.w(TAG, "Malformed service info: '$infoString' (no '|' separator)")
                return null
            }

            val shortId = parts[0]
            val displayName = parts[1]

            // Validate shortId — must be exactly 4 characters
            if (shortId.length != 4) {
                Log.w(TAG, "Invalid shortId length: '${shortId}' (expected 4 chars)")
                return null
            }

            // Filter out our own broadcasts — don't discover ourselves
            if (shortId == localShortId) {
                Log.d(TAG, "Ignoring own broadcast: $shortId")
                return null
            }

            UserProfile(
                shortId = shortId,
                displayName = displayName,
                status = "Available",
                // Wi-Fi Aware doesn't provide RSSI in onServiceDiscovered,
                // so we use 0. The UI can distinguish BLE vs Wi-Fi Aware
                // users by this (BLE has real RSSI, Wi-Fi Aware has 0).
                // A proper solution would add a discoverySource field to
                // UserProfile, but that's deferred to Iteration 6 (polish).
                rssi = 0,
                lastSeen = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Wi-Fi Aware service info: ${e.message}")
            null
        }
    }

    // Full Cleanup

    /**
     * Stops everything and releases all Wi-Fi Aware resources.
     * Called by NearMeRepository.stopWifiAware() during service shutdown.
     *
     * Order matters:
     * 1. Stop subscribing (cancel timeout, close session)
     * 2. Stop publishing (close session)
     * 3. Close the WifiAwareSession itself
     * 4. Unregister the availability receiver
     * 5. Clear all state
     */
    fun stopAll() {
        Log.d(TAG, "Stopping all Wi-Fi Aware operations")

        // Cancel any pending subscribe timeout
        mainHandler.removeCallbacks(subscribeTimeoutRunnable)

        // Close discovery sessions
        subscribeSession?.close()
        subscribeSession = null
        isSubscribing = false

        publishSession?.close()
        publishSession = null
        isPublishing = false

        // Close the Wi-Fi Aware session — releases system resources
        session?.close()
        session = null

        // Unregister the availability receiver
        unregisterAvailabilityReceiver()

        wasPublishingBeforePause = false
        Log.d(TAG, "All Wi-Fi Aware operations stopped and cleaned up")
    }
}