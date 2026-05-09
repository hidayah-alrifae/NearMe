package com.example.nearme.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

// context: needed to access Nearby Connections API
// onMessageReceived: a function that gets called when a message arrives from the other phone
// onConnected: called when a connection is successfully established
// onDisconnected: called when the other phone disconnects

class NearbyManager(
    private val context: Context,
    private val onMessageReceived: (String, String) -> Unit ,// (senderEndpointId, messageText)
    private val onConnected: (String) -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {

    // unique ID for your app — only NearMe devices will find each other
    private val SERVICE_ID = "com.example.nearme"

    // the Nearby Connections client — this is the main tool that does everything
    private val connectionsClient = Nearby.getConnectionsClient(context)

    // stores connected users: endpointId → displayName
    private val connectedEndpoints = mutableMapOf<String, String>()

    // handles finding and losing nearby devices
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        // found a device that is advertising
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // automatically request connection when we find someone
            requestConnection(info.endpointName, endpointId)
        }

        // a device that was advertising has stopped
        override fun onEndpointLost(endpointId: String) {
            // the device went away — nothing to do here
        }
    }

    // handles connection events: request received, connected, disconnected
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        // someone wants to connect to you
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // automatically accept the connection
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        // connection was successful or failed
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                // connected! save this endpoint
                connectedEndpoints[endpointId] = endpointId
                onConnected(endpointId)  // tell ChatViewModel the real endpointId
            }
        }

        // the other person disconnected
        override fun onDisconnected(endpointId: String) {
            // remove them from the map
            connectedEndpoints.remove(endpointId)
            onDisconnected()  // tell ChatViewModel to clear endpoint and restart
        }
    }

    // handles receiving messages from connected phones
    private val payloadCallback = object : PayloadCallback() {

        // a message arrived from the other phone
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // convert the raw bytes into a readable string
            val message = payload.asBytes()?.let { String(it) } ?: return
            // pass it up — the ViewModel will save it to database and show it on screen
            onMessageReceived(endpointId, message)
        }

        // tracks progress of large transfers (we don't need this for text messages)
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // not needed for simple text messages
        }
    }

    // start advertising: "I'm available for chat"
    fun startAdvertising(displayName: String) {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)  // allows multiple connections
            .build()

        connectionsClient.startAdvertising(
            displayName, SERVICE_ID, connectionLifecycleCallback, options
        )
    }

    // start discovery: "Who's available?"
    fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, options
        )
    }

    // request connection to a specific device
    fun requestConnection(displayName: String, endpointId: String) {
        connectionsClient.requestConnection(displayName, endpointId, connectionLifecycleCallback)
    }

    // send a text message to a connected device
    fun sendMessage(endpointId: String, message: String) {
        val payload = Payload.fromBytes(message.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
    }

    // disconnect from a specific device
    fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        connectedEndpoints.remove(endpointId)
    }

    // stop everything and clean up
    fun stopAllConnections() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
    }

}