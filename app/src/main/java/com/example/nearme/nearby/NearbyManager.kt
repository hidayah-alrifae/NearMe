package com.example.nearme.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

/**
 * NearbyManager wraps the Google Nearby Connections API.
 * Handles advertising, targeted discovery, connection lifecycle,
 * and message sending/receiving.
 *
 * Callbacks are settable lambdas — the repository wires them up
 * after construction. This avoids constructor parameter coupling.
 */
class NearbyManager(private val context: Context) {

    companion object {
        private const val TAG = "NC_MGR"
        private const val SERVICE_ID = "com.example.nearme"
    }

    // Our own broadcast name — used when requesting connections
// so the other side can identify us
    private var localBroadcastName: String = ""

    // The single Nearby Connections client — shared across all NC operations
    private val connectionsClient = Nearby.getConnectionsClient(context)

    // Maps endpointId → shortId for connected peers
    private val connectedEndpoints = mutableMapOf<String, String>()

    // Temporarily holds shortId during connection handshake
    // (between onConnectionInitiated and onConnectionResult)
    private val pendingConnections = mutableMapOf<String, String>()

    // The shortId we're looking for during discovery
    private var targetShortId: String? = null

    // Settable callbacks — repository wires these up in startNc()
    var onMessageReceived: ((senderEndpointId: String, messageText: String) -> Unit)? = null
    var onConnected: ((endpointId: String) -> Unit)? = null
    var onDisconnected: ((endpointId: String) -> Unit)? = null

    /**
     * Start NC advertising so other devices can find us.
     * Broadcast name format: "shortId|DisplayName" (e.g. "3fa7|Ahmed")
     * so the discovering device can identify us by shortId.
     */
    fun startAdvertising(shortId: String, displayName: String) {
        val broadcastName = "$shortId|$displayName"
        localBroadcastName = broadcastName  // save for requestConnection

        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()
        connectionsClient.startAdvertising(
            broadcastName, SERVICE_ID, connectionLifecycleCallback, options
        ).addOnSuccessListener {
            Log.d(TAG, "NC advertising started: $broadcastName")
        }.addOnFailureListener { e ->
            Log.e(TAG, "NC advertising failed: ${e.message}")
        }
    }

    fun stopAdvertising() {
        connectionsClient.stopAdvertising()
    }

    /**
     * Start discovery looking for a SPECIFIC shortId.
     * Only requests connection when the target is found.
     * This prevents connecting to random nearby NC devices.
     */
    fun startDiscoveryForTarget(shortId: String) {
        targetShortId = shortId
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, options
        )
    }

    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        targetShortId = null
    }

    /**
     * Send a text message to a connected peer.
     */
    fun sendMessage(endpointId: String, message: String) {
        val payload = Payload.fromBytes(message.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
    }

    /**
     * Disconnect from a specific peer.
     */
    fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        connectedEndpoints.remove(endpointId)
    }

    /**
     * Stop everything — advertising, discovery, all connections.
     */
    fun stopAllConnections() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        targetShortId = null
    }

    /**
     * Look up which shortId is behind a given endpointId.
     * Used by the repository when a message arrives to know
     * which conversation it belongs to.
     */
    fun getShortIdForEndpoint(endpointId: String): String? {
        return connectedEndpoints[endpointId]
    }

    /**
     * Discovery callback — only connects to the target shortId.
     * Extracts shortId from the broadcast name ("shortId|DisplayName").
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Extract shortId from broadcast name "3fa7|Ahmed" → "3fa7"
            val foundShortId = info.endpointName.substringBefore("|")
            val target = targetShortId
            if (target != null && foundShortId == target) {
                // Found our target — request connection
                // Send OUR broadcast name so the acceptor can identify us
                connectionsClient.requestConnection(
                    localBroadcastName, endpointId, connectionLifecycleCallback
                )
            }
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    /**
     * Connection lifecycle — handles the three stages:
     * 1. onConnectionInitiated — save the shortId from broadcast name, auto-accept
     * 2. onConnectionResult — if success, promote to connectedEndpoints, notify repository
     * 3. onDisconnected — clean up and notify repository
     */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Extract shortId from the other device's broadcast name
            val shortId = info.endpointName.substringBefore("|")
            pendingConnections[endpointId] = shortId
            // Auto-accept — no confirmation dialog needed for NearMe
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                // Connection succeeded — promote from pending to connected
                val shortId = pendingConnections.remove(endpointId) ?: "unknown"
                connectedEndpoints[endpointId] = shortId
                // Stop discovery — we found who we were looking for
                stopDiscovery()
                // Notify repository so it can emit on SharedFlow
                onConnected?.invoke(endpointId)
            } else {
                // Connection failed — clean up pending
                pendingConnections.remove(endpointId)
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            onDisconnected?.invoke(endpointId)
        }
    }

    /**
     * Payload callback — handles incoming messages.
     * Converts bytes to String and passes to repository via onMessageReceived.
     */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val message = payload.asBytes()?.let { String(it) } ?: return
            onMessageReceived?.invoke(endpointId, message)
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}