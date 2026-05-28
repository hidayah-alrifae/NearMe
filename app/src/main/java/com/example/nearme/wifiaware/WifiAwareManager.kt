package com.example.nearme.wifiaware

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager as SystemWifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.nearme.model.UserProfile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import androidx.annotation.RequiresApi
import android.os.Build


class WifiAwareManager(private val context: Context) {

    companion object {
        private const val TAG = "WIFI_AWARE"
        private const val SERVICE_NAME = "nearme"
        private const val SUBSCRIBE_TIMEOUT_MS = 30_000L
        private const val NDP_PASSPHRASE = "NearMe2026"
    }

    // ─── Hardware & System ────────────────────────────────────────────────

    val isSupported: Boolean = checkHardwareSupport()

    private val systemManager: SystemWifiAwareManager? =
        if (isSupported) {
            context.getSystemService(Context.WIFI_AWARE_SERVICE) as? SystemWifiAwareManager
        } else null

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // ─── Session State ────────────────────────────────────────────────────

    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    private var isPublishing = false
    private var isSubscribing = false
    private var wasPublishingBeforePause = false

    private var localShortId: String = ""
    private var localDisplayName: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    private val subscribeTimeoutRunnable = Runnable {
        Log.d(TAG, "Subscribe timeout reached — stopping")
        stopSubscribing()
    }

    // ─── Peer Tracking ────────────────────────────────────────────────────

    // shortId → PeerHandle. Needed for L2 messaging and NDP setup.
    private val discoveredPeers = mutableMapOf<String, PeerHandle>()

    // ─── NDP State ────────────────────────────────────────────────────────

    private var activeSocket: Socket? = null
    private var socketOutput: DataOutputStream? = null
    private var serverSocket: ServerSocket? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var ndpPeerShortId: String? = null
    private var readThread: Thread? = null
    private var acceptThread: Thread? = null

    // ─── Discovery Callbacks (existing) ───────────────────────────────────

    var onUserDiscovered: ((UserProfile) -> Unit)? = null
    var onSubscribeStopped: (() -> Unit)? = null

    // ─── NDP Chat Callbacks (new) ─────────────────────────────────────────

    var onNdpConnected: ((peerShortId: String) -> Unit)? = null
    var onNdpMessageReceived: ((peerShortId: String, message: String) -> Unit)? = null
    var onNdpDisconnected: ((peerShortId: String) -> Unit)? = null
    var onChatRequested: ((peerShortId: String, peerHandle: PeerHandle) -> Unit)? = null

    // ─── Availability Receiver ────────────────────────────────────────────

    private var availabilityReceiver: BroadcastReceiver? = null

    private fun checkHardwareSupport(): Boolean {
        val hasFeature = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_WIFI_AWARE
        )
        Log.d(TAG, "Wi-Fi Aware hardware supported: $hasFeature")
        return hasFeature
    }

    fun isAvailable(): Boolean {
        if (!isSupported) return false
        return systemManager?.isAvailable == true
    }

    @SuppressLint("MissingPermission")
    private fun attachSession(onAttached: () -> Unit) {
        if (session != null) {
            onAttached()
            return
        }
        systemManager?.attach(object : AttachCallback() {
            override fun onAttached(wifiAwareSession: WifiAwareSession) {
                Log.d(TAG, "Wi-Fi Aware session attached")
                session = wifiAwareSession
                onAttached()
            }
            override fun onAttachFailed() {
                Log.e(TAG, "Wi-Fi Aware attach FAILED")
                session = null
            }
        }, mainHandler)
    }

    fun registerAvailabilityReceiver() {
        if (!isSupported) return
        availabilityReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (isAvailable()) {
                    Log.d(TAG, "Wi-Fi Aware became AVAILABLE — re-attaching")
                    if (localShortId.isNotEmpty()) {
                        startPublishing(localShortId, localDisplayName)
                    }
                } else {
                    Log.d(TAG, "Wi-Fi Aware became UNAVAILABLE")
                    publishSession = null
                    subscribeSession = null
                    session = null
                    isPublishing = false
                    isSubscribing = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        disconnectNdp()
                    }
                }
            }
        }
        val filter = IntentFilter(SystemWifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
        context.registerReceiver(availabilityReceiver, filter)
        Log.d(TAG, "Availability receiver registered")
    }

    fun unregisterAvailabilityReceiver() {
        availabilityReceiver?.let {
            try { context.unregisterReceiver(it) }
            catch (_: IllegalArgumentException) {}
        }
        availabilityReceiver = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PART 1: DISCOVERY
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    fun startPublishing(shortId: String, displayName: String) {
        if (!isSupported) return
        localShortId = shortId
        localDisplayName = displayName
        if (!isAvailable()) return

        attachSession {
            val currentSession = session ?: return@attachSession
            val serviceInfo = "$shortId|$displayName".toByteArray()

            val publishConfig = PublishConfig.Builder()
                .setServiceName(SERVICE_NAME)
                .setServiceSpecificInfo(serviceInfo)
                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .build()

            currentSession.publish(publishConfig, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    Log.d(TAG, "Publishing started")
                    publishSession = session
                    isPublishing = true
                }

                // NEW: Handle incoming L2 chat requests from subscribers
                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val msgString = String(message, Charsets.UTF_8)
                    Log.d(TAG, "Publish received L2 message: $msgString")
                    if (msgString.startsWith("CHAT_REQ:")) {
                        val subscriberShortId = msgString.removePrefix("CHAT_REQ:")
                        discoveredPeers[subscriberShortId] = peerHandle
                        onChatRequested?.invoke(subscriberShortId, peerHandle)
                    }
                }

                override fun onSessionTerminated() {
                    publishSession = null
                    isPublishing = false
                    session = null
                }
            }, mainHandler)
        }
    }

    fun stopPublishing() {
        publishSession?.close()
        publishSession = null
        isPublishing = false
    }

    fun pausePublishing() {
        wasPublishingBeforePause = isPublishing
        if (isPublishing) stopPublishing()
        if (isSubscribing) stopSubscribing()
    }

    fun resumePublishing() {
        if (wasPublishingBeforePause && localShortId.isNotEmpty()) {
            startPublishing(localShortId, localDisplayName)
            wasPublishingBeforePause = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startSubscribing() {
        if (!isSupported || !isAvailable() || isSubscribing) return

        attachSession {
            val currentSession = session ?: return@attachSession

            val config = SubscribeConfig.Builder()
                .setServiceName(SERVICE_NAME)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .build()

            currentSession.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    isSubscribing = true
                    mainHandler.postDelayed(subscribeTimeoutRunnable, SUBSCRIBE_TIMEOUT_MS)
                }

                // NEW: Store PeerHandle alongside UserProfile
                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray,
                    matchFilter: List<ByteArray>
                ) {
                    val userProfile = parseServiceInfo(serviceSpecificInfo)
                    if (userProfile != null) {
                        discoveredPeers[userProfile.shortId] = peerHandle
                        onUserDiscovered?.invoke(userProfile)
                    }
                }

                override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
                    Log.d(TAG, "Service lost (reason=$reason)")
                }

                override fun onSessionTerminated() {
                    cleanupSubscribe()
                }
            }, mainHandler)
        }
    }

    fun stopSubscribing() {
        if (!isSubscribing) return
        cleanupSubscribe()
    }

    private fun cleanupSubscribe() {
        mainHandler.removeCallbacks(subscribeTimeoutRunnable)
        subscribeSession?.close()
        subscribeSession = null
        isSubscribing = false
        onSubscribeStopped?.invoke()
    }

    private fun parseServiceInfo(serviceInfo: ByteArray): UserProfile? {
        return try {
            val infoString = String(serviceInfo, Charsets.UTF_8)
            val parts = infoString.split("|", limit = 2)
            if (parts.size < 2) return null
            val shortId = parts[0]
            val displayName = parts[1]
            if (shortId.length != 4) return null
            if (shortId == localShortId) return null

            UserProfile(
                shortId = shortId,
                displayName = displayName,
                status = "Available",
                rssi = 0,
                lastSeen = System.currentTimeMillis(),
                discoverySource = "WIFI_AWARE"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse service info: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PART 2: NDP DATA TRANSFER
    // ═══════════════════════════════════════════════════════════════════════

    fun getPeerHandle(shortId: String): PeerHandle? = discoveredPeers[shortId]
    fun isNdpConnectedTo(shortId: String): Boolean = ndpPeerShortId == shortId

    // ─── L2 Chat Request ──────────────────────────────────────────────────

    fun sendChatRequest(targetShortId: String) {
        val peerHandle = discoveredPeers[targetShortId]
        val subSession = subscribeSession
        if (peerHandle == null || subSession == null) {
            Log.e(TAG, "Cannot send chat request — missing PeerHandle or session")
            return
        }
        val message = "CHAT_REQ:$localShortId".toByteArray(Charsets.UTF_8)
        subSession.sendMessage(peerHandle, 0, message)
        Log.d(TAG, "Sent CHAT_REQ to $targetShortId")
    }

    // ─── NDP Server (Publisher/Responder) ─────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    fun startNdpServer(peerShortId: String, peerHandle: PeerHandle) {
        val pubSession = publishSession
        if (pubSession == null) {
            Log.e(TAG, "No publish session — cannot start NDP server")
            return
        }
        ndpPeerShortId = peerShortId

        try {
            serverSocket = ServerSocket(0)
            val port = serverSocket!!.localPort
            Log.d(TAG, "NDP ServerSocket opened on port $port")

            val specifier = WifiAwareNetworkSpecifier.Builder(pubSession, peerHandle)
                .setPskPassphrase(NDP_PASSPHRASE)
                .setPort(port)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "NDP network available (server)")
                    acceptThread = Thread {
                        try {
                            val socket = serverSocket!!.accept()
                            activeSocket = socket
                            socketOutput = DataOutputStream(socket.getOutputStream())
                            mainHandler.post { onNdpConnected?.invoke(peerShortId) }
                            startReadLoop(peerShortId)
                        } catch (e: Exception) {
                            if (ndpPeerShortId != null) {
                                Log.e(TAG, "NDP server accept failed: ${e.message}")
                            }
                        }
                    }
                    acceptThread?.isDaemon = true
                    acceptThread?.start()
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "NDP network lost (server)")
                    handleNdpDisconnection()
                }
            }
            networkCallback = callback
            connectivityManager.requestNetwork(request, callback)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NDP server: ${e.message}")
            cleanupNdp()
        }
    }

    // ─── NDP Client (Subscriber/Initiator) ────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    fun startNdpClient(targetShortId: String) {
        val subSession = subscribeSession
        val peerHandle = discoveredPeers[targetShortId]
        if (subSession == null || peerHandle == null) {
            Log.e(TAG, "Cannot start NDP client — missing session or PeerHandle")
            return
        }
        ndpPeerShortId = targetShortId

        try {
            val specifier = WifiAwareNetworkSpecifier.Builder(subSession, peerHandle)
                .setPskPassphrase(NDP_PASSPHRASE)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    val peerInfo = capabilities.transportInfo as? WifiAwareNetworkInfo
                        ?: return
                    val peerIpv6 = peerInfo.peerIpv6Addr ?: return
                    val peerPort = peerInfo.port
                    if (peerPort == 0) return
                    if (activeSocket != null) return

                    Log.d(TAG, "NDP peer info: $peerIpv6:$peerPort")
                    Thread {
                        try {
                            val socket = network.socketFactory.createSocket(peerIpv6, peerPort)
                            activeSocket = socket
                            socketOutput = DataOutputStream(socket.getOutputStream())
                            mainHandler.post { onNdpConnected?.invoke(targetShortId) }
                            startReadLoop(targetShortId)
                        } catch (e: Exception) {
                            Log.e(TAG, "NDP client connect failed: ${e.message}")
                            mainHandler.post { handleNdpDisconnection() }
                        }
                    }.start()
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "NDP network lost (client)")
                    handleNdpDisconnection()
                }
            }
            networkCallback = callback
            connectivityManager.requestNetwork(request, callback)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NDP client: ${e.message}")
            cleanupNdp()
        }
    }

    // ─── Socket I/O ───────────────────────────────────────────────────────

    // Length-prefixed framing: [4-byte int: length] + [N bytes: UTF-8 message]
    // TCP is a byte stream — without framing you can't tell where one message
    // ends and the next begins.
    @RequiresApi(Build.VERSION_CODES.Q)
    fun sendOverNdp(message: String) {
        val output = socketOutput ?: return
        Thread {
            try {
                val bytes = message.toByteArray(Charsets.UTF_8)
                synchronized(output) {
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "NDP send failed: ${e.message}")
                mainHandler.post { handleNdpDisconnection() }
            }
        }.start()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startReadLoop(peerShortId: String) {
        readThread = Thread {
            try {
                val input = DataInputStream(activeSocket!!.getInputStream())
                while (!Thread.currentThread().isInterrupted) {
                    val length = input.readInt()
                    if (length <= 0 || length > 50 * 1024 * 1024) break
                    val buffer = ByteArray(length)
                    input.readFully(buffer)
                    val message = String(buffer, Charsets.UTF_8)
                    mainHandler.post {
                        onNdpMessageReceived?.invoke(peerShortId, message)
                    }
                }
            } catch (e: Exception) {
                if (ndpPeerShortId != null) {
                    Log.d(TAG, "NDP read loop ended: ${e.message}")
                }
            } finally {
                mainHandler.post { handleNdpDisconnection() }
            }
        }
        readThread?.isDaemon = true
        readThread?.start()
    }

    // ─── NDP Lifecycle ────────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.Q)
    fun disconnectNdp() {
        cleanupNdp()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleNdpDisconnection() {
        val peer = ndpPeerShortId ?: return
        cleanupNdp()
        onNdpDisconnected?.invoke(peer)
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun cleanupNdp() {
        ndpPeerShortId = null
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        socketOutput = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        readThread?.interrupt()
        readThread = null
        acceptThread?.interrupt()
        acceptThread = null
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) }
            catch (_: Exception) {}
        }
        networkCallback = null
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    fun stopAll() {
        disconnectNdp()
        stopPublishing()
        stopSubscribing()
        unregisterAvailabilityReceiver()
        session?.close()
        session = null
        discoveredPeers.clear()
    }
}