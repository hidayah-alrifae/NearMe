package com.example.nearme.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * NearbyManager wraps the Google Nearby Connections API.
 * Handles advertising, targeted discovery, connection lifecycle,
 * message sending/receiving, and file transfer.
 *
 * DELIVERY STATUS PROTOCOL:
 *   Text messages:
 *     Sender → "MSG:<messageId>:<text>"     → Receiver
 *     Receiver → "MSG_ACK:<messageId>"      → Sender (auto)
 *
 *   File messages:
 *     Sender → "FILE_META:<payloadId>:<fileName>:<mimeType>:<messageId>"
 *     Sender → Payload.fromFile(...)
 *     Receiver → "FILE_ACK:<messageId>"     → Sender (after file saved)
 *
 * When sender receives MSG_ACK or FILE_ACK, the onAckReceived callback
 * fires so the repository can update the message status to "delivered".
 */
class NearbyManager(private val context: Context) {

    companion object {
        private const val TAG = "NC_MGR"
        private const val SERVICE_ID = "com.example.nearme"
    }

    // ─── Connection State ─────────────────────────────────────────────────

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

    // ─── Callbacks — repository wires these up in startNc() ───────────────

    var onMessageReceived: ((senderEndpointId: String, messageText: String) -> Unit)? = null
    var onConnected: ((endpointId: String) -> Unit)? = null
    var onDisconnected: ((endpointId: String) -> Unit)? = null

    // File transfer callbacks — repository wires these up alongside the text ones
    var onFileReceived: ((senderEndpointId: String, file: File, fileName: String, mimeType: String) -> Unit)? = null
    var onFileProgress: ((payloadId: Long, bytesTransferred: Long, totalBytes: Long) -> Unit)? = null

    // NEW: Called when we receive MSG_ACK or FILE_ACK from the other device
    var onAckReceived: ((messageId: String) -> Unit)? = null

    // Raw group protocol payloads (GMSG / GROUP_*)  repository parses and relays them
    var onGroupPayloadReceived: ((endpointId: String, raw: String) -> Unit)? = null

    // ─── File Transfer Tracking ───────────────────────────────────────────

    // Tracks incoming file transfers: payloadId → metadata
    // Populated by the metadata BYTES payload that arrives BEFORE the FILE payload
    private data class FileMetadata(
        val fileName: String,
        val mimeType: String,
        val messageId: String  // NEW: the sender's Room message ID for ACK
    )
    private val pendingFileTransfers = mutableMapOf<Long, FileMetadata>()

    // Tracks payloadId → endpointId so we know WHO sent a file when it completes
    private val payloadToEndpoint = mutableMapOf<Long, String>()

    // Stores the received file URI so we can copy it on completion
    private val pendingFileUris = mutableMapOf<Long, android.net.Uri>()

    // ─── Advertising ──────────────────────────────────────────────────────

    /**
     * Start NC advertising so other devices can find us.
     * Broadcast name format: "shortId|DisplayName" (e.g. "3fa7|Ahmed")
     * so the discovering device can identify us by shortId.
     */
    fun startAdvertising(shortId: String, displayName: String) {
        val broadcastName = "$shortId|$displayName"
        localBroadcastName = broadcastName

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

    // ─── Discovery ────────────────────────────────────────────────────────

    /**
     * Start discovery looking for a SPECIFIC shortId.
     * Only requests connection when the target is found.
     * This prevents connecting to random nearby NC devices.
     */
    fun startDiscoveryForTarget(shortId: String) {
        Log.d(TAG, "Starting discovery for: $shortId")
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

    // ─── Sending ──────────────────────────────────────────────────────────

    /**
     * Send a text message with a message ID for delivery tracking.
     * Format: "MSG:<messageId>:<text>"
     * The receiver will send back "MSG_ACK:<messageId>" on receipt.
     */

    /**
     * Send an arbitrary already-formatted string to one endpoint.
     * Used by the repository for all group-protocol messages and relays.
     */
    fun sendRaw(endpointId: String, payload: String) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload.toByteArray()))
    }
    fun sendMessage(endpointId: String, messageId: String, text: String) {
        val payload = "MSG:$messageId:$text"
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(payload.toByteArray()))
    }

    /**
     * Send a file to the connected peer using Payload.fromFile().
     * Two-step process:
     * 1. Send a small BYTES payload with metadata (so receiver knows filename + type + messageId)
     * 2. Send the FILE payload (NC handles chunking automatically)
     *
     * The metadata format: "FILE_META:<filePayloadId>:<fileName>:<mimeType>:<messageId>"
     * The receiver parses this and stores it in pendingFileTransfers.
     * When the file finishes, the receiver sends "FILE_ACK:<messageId>" back.
     */
    fun sendFile(endpointId: String, file: File, fileName: String, mimeType: String, messageId: String) {
        try {
            // Step 1: Create the FILE payload from the file
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val filePayload = Payload.fromFile(pfd)
            val filePayloadId = filePayload.id

            // Step 2: Send metadata FIRST so receiver knows what's coming
            // Added messageId at the end so receiver can ACK it
            val metadata = "FILE_META:$filePayloadId:$fileName:$mimeType:$messageId"
            val metadataPayload = Payload.fromBytes(metadata.toByteArray())
            connectionsClient.sendPayload(endpointId, metadataPayload)

            // Step 3: Send the actual file
            connectionsClient.sendPayload(endpointId, filePayload)

            Log.d(TAG, "Sending file: $fileName ($mimeType), payloadId=$filePayloadId, messageId=$messageId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send file: ${e.message}")
        }
    }

    // ─── Connection Management ────────────────────────────────────────────

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
     * Check if we already have an active connection to this shortId.
     * Prevents starting discovery for someone we're already chatting with.
     */
    fun isConnectedTo(shortId: String): Boolean {
        return connectedEndpoints.containsValue(shortId)
    }

    /**
     * Get the endpointId for an already-connected shortId.
     * Returns null if not connected.
     */
    fun getEndpointForShortId(shortId: String): String? {
        return connectedEndpoints.entries.firstOrNull { it.value == shortId }?.key
    }

    // ─── Private: ACK Helper ──────────────────────────────────────────────

    /**
     * Send a delivery acknowledgment back to the sender.
     * Called automatically when a text message or file is fully received.
     */
    private fun sendAck(endpointId: String, type: String, id: String) {
        val ack = "${type}_ACK:$id"
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(ack.toByteArray()))
        Log.d(TAG, "Sent $ack to $endpointId")
    }

    // ─── Private: Discovery Callback ──────────────────────────────────────

    /**
     * Discovery callback — only connects to the target shortId.
     * Extracts shortId from the broadcast name ("shortId|DisplayName").
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Extract shortId from broadcast name "3fa7|Ahmed" → "3fa7"
            val foundShortId = info.endpointName.substringBefore("|")
            Log.d(TAG, "Found endpoint: $foundShortId, target: $targetShortId")

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

    // ─── Private: Connection Lifecycle ────────────────────────────────────

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
                val shortId = pendingConnections.remove(endpointId) ?: "unknown"
                connectedEndpoints[endpointId] = shortId
                stopDiscovery()

                onConnected?.invoke(endpointId)
            } else {
                pendingConnections.remove(endpointId)
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            onDisconnected?.invoke(endpointId)
        }
    }

    // ─── Private: Payload Callback (the protocol handler) ─────────────────

    /**
     * Payload callback — handles ALL incoming payloads.
     *
     * BYTES payloads (protocol prefixes):
     *   "MSG:<id>:<text>"                        → text message, send MSG_ACK back
     *   "MSG_ACK:<id>"                           → delivery confirmation for text
     *   "FILE_META:<payloadId>:<name>:<mime>:<msgId>" → file metadata, store for when file arrives
     *   "FILE_ACK:<id>"                          → delivery confirmation for file
     *   (anything else)                          → legacy plain text (backward compat)
     *
     * FILE payloads: tracked via pendingFileTransfers map, completed in onPayloadTransferUpdate.
     */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val text = payload.asBytes()?.let { String(it) } ?: return

                    when {
                        text.startsWith("MSG:") -> {
                            // Format: MSG:<messageId>:<text>
                            val firstColon = text.indexOf(':')
                            val secondColon = text.indexOf(':', firstColon + 1)
                            if (secondColon == -1) return

                            val messageId = text.substring(firstColon + 1, secondColon)
                            val messageText = text.substring(secondColon + 1)

                            // Deliver the message to the repository
                            onMessageReceived?.invoke(endpointId, messageText)
                            // ACK back to sender — "I got your message"
                            sendAck(endpointId, "MSG", messageId)
                        }

                        text.startsWith("MSG_ACK:") -> {
                            val messageId = text.removePrefix("MSG_ACK:")
                            onAckReceived?.invoke(messageId)
                        }

                        text.startsWith("FILE_ACK:") -> {
                            val messageId = text.removePrefix("FILE_ACK:")
                            onAckReceived?.invoke(messageId)
                        }

                        text.startsWith("FILE_META:") -> {
                            // Format: FILE_META:<payloadId>:<fileName>:<mimeType>:<messageId>
                            val parts = text.removePrefix("FILE_META:").split(":", limit = 4)
                            if (parts.size == 4) {
                                val payloadId = parts[0].toLongOrNull() ?: return
                                val fileName = parts[1]
                                val mimeType = parts[2]
                                val messageId = parts[3]
                                pendingFileTransfers[payloadId] = FileMetadata(fileName, mimeType, messageId)
                                Log.d(TAG, "File metadata received: $fileName ($mimeType) payloadId=$payloadId messageId=$messageId")
                            }
                        }

                        text.startsWith("GMSG:") ||
                                text.startsWith("GMSG_ACK:") ||
                                text.startsWith("GROUP_INVITE:") ||
                                text.startsWith("GROUP_JOIN:") ||
                                text.startsWith("GROUP_ROSTER:") ||
                                text.startsWith("GROUP_LEAVE:") -> {
                            onGroupPayloadReceived?.invoke(endpointId, text)
                        }

                        else -> {
                            // Fallback for plain text (backward compatibility)
                            onMessageReceived?.invoke(endpointId, text)
                        }
                    }
                }

                Payload.Type.FILE -> {
                    payloadToEndpoint[payload.id] = endpointId
                    // Save the file URI — this is the safe way to access the file
                    val fileUri = payload.asFile()?.asUri()
                    if (fileUri != null) {
                        pendingFileUris[payload.id] = fileUri
                    }
                    Log.d(TAG, "File payload receiving started: payloadId=${payload.id} from $endpointId, uri=$fileUri")
                }

                else -> {
                    Log.w(TAG, "Unknown payload type: ${payload.type}")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Report progress for UI
                    onFileProgress?.invoke(
                        update.payloadId,
                        update.bytesTransferred,
                        update.totalBytes
                    )
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    val metadata = pendingFileTransfers.remove(update.payloadId) ?: return
                    val senderEndpoint = payloadToEndpoint.remove(update.payloadId) ?: return
                    val fileUri = pendingFileUris.remove(update.payloadId)

                    if (fileUri != null) {
                        try {
                            // Copy from the URI to permanent storage
                            val receivedDir = File(context.filesDir, "received_files")
                            receivedDir.mkdirs()

                            val permanentFile = File(receivedDir, metadata.fileName)
                            val finalFile = if (permanentFile.exists()) {
                                val nameWithoutExt = metadata.fileName.substringBeforeLast(".")
                                val ext = metadata.fileName.substringAfterLast(".", "")
                                val uniqueName = "${nameWithoutExt}_${System.currentTimeMillis()}.$ext"
                                File(receivedDir, uniqueName)
                            } else {
                                permanentFile
                            }

                            // Use ContentResolver to read from the URI
                            context.contentResolver.openInputStream(fileUri)?.use { input ->
                                finalFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }

                            if (finalFile.exists() && finalFile.length() > 0) {
                                Log.d(TAG, "File received and saved: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
                                onFileReceived?.invoke(senderEndpoint, finalFile, metadata.fileName, metadata.mimeType)

                                // NEW: ACK back to sender — "I got the complete file"
                                sendAck(senderEndpoint, "FILE", metadata.messageId)
                            } else {
                                Log.e(TAG, "Saved file is empty or missing: ${finalFile.absolutePath}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save received file: ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "No file URI found for payloadId=${update.payloadId}")
                    }
                }

                PayloadTransferUpdate.Status.FAILURE -> {
                    pendingFileTransfers.remove(update.payloadId)
                    payloadToEndpoint.remove(update.payloadId)
                    pendingFileUris.remove(update.payloadId)
                    Log.e(TAG, "File transfer failed: payloadId=${update.payloadId}")
                }

                PayloadTransferUpdate.Status.CANCELED -> {
                    pendingFileTransfers.remove(update.payloadId)
                    payloadToEndpoint.remove(update.payloadId)
                    pendingFileUris.remove(update.payloadId)
                    Log.w(TAG, "File transfer canceled: payloadId=${update.payloadId}")
                }
            }
        }
    }
}