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
    // File transfer callbacks  repository wires these up alongside the text ones
    var onFileReceived: ((senderEndpointId: String, file: File, fileName: String, mimeType: String) -> Unit)? = null
    var onFileProgress: ((payloadId: Long, bytesTransferred: Long, totalBytes: Long) -> Unit)? = null

    // Tracks incoming file transfers: payloadId → metadata (fileName, mimeType)
    // Populated by the metadata BYTES payload that arrives BEFORE the FILE payload
    private data class FileMetadata(val fileName: String, val mimeType: String)
    private val pendingFileTransfers = mutableMapOf<Long, FileMetadata>()

    // Tracks payloadId → endpointId so we know WHO sent a file when it completes
    private val payloadToEndpoint = mutableMapOf<Long, String>()

    // Stores the received file URI so we can copy it on completion
    private val pendingFileUris = mutableMapOf<Long, android.net.Uri>()

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
        android.util.Log.d("NC_MGR", "Starting discovery for: $shortId")

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
     * Send a file to a connected peer.
     * Two-step process:
     * 1. Send a small BYTES payload with metadata (so receiver knows filename + type)
     * 2. Send the FILE payload (NC handles chunking automatically)
     *
     * The metadata format: "FILE_META:<filePayloadId>:<fileName>:<mimeType>"
     * The receiver parses this in onPayloadReceived and stores it in pendingFileTransfers.
     */
    fun sendFile(endpointId: String, file: File, fileName: String, mimeType: String) {
        try {
            // Step 1: Create the FILE payload from the file
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val filePayload = Payload.fromFile(pfd)
            val filePayloadId = filePayload.id

            // Step 2: Send metadata FIRST so receiver knows what's coming
            val metadata = "FILE_META:$filePayloadId:$fileName:$mimeType"
            val metadataPayload = Payload.fromBytes(metadata.toByteArray())
            connectionsClient.sendPayload(endpointId, metadataPayload)

            // Step 3: Send the actual file
            connectionsClient.sendPayload(endpointId, filePayload)

            Log.d(TAG, "Sending file: $fileName ($mimeType), payloadId=$filePayloadId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send file: ${e.message}")
        }
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

    /**
     * Discovery callback — only connects to the target shortId.
     * Extracts shortId from the broadcast name ("shortId|DisplayName").
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Extract shortId from broadcast name "3fa7|Ahmed" → "3fa7"
            val foundShortId = info.endpointName.substringBefore("|")
            android.util.Log.d("NC_MGR", "Found endpoint: $foundShortId, target: $targetShortId")

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

    /**
     * Payload callback — handles incoming messages.
     * Converts bytes to String and passes to repository via onMessageReceived.
     */
    /**
     * Payload callback — handles both text messages and file transfers.
     *
     * BYTES payloads are either:
     *   - A text message (passed to onMessageReceived)
     *   - File metadata prefixed with "FILE_META:" (stored for when the file arrives)
     *
     * FILE payloads are saved to a temp location by NC automatically.
     * We track them here and move them to permanent storage when complete.
     */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val text = payload.asBytes()?.let { String(it) } ?: return

                    if (text.startsWith("FILE_META:")) {
                        // This is file metadata, not a chat message
                        // Format: "FILE_META:<payloadId>:<fileName>:<mimeType>"
                        val parts = text.removePrefix("FILE_META:").split(":", limit = 3)
                        if (parts.size == 3) {
                            val payloadId = parts[0].toLongOrNull() ?: return
                            val fileName = parts[1]
                            val mimeType = parts[2]
                            pendingFileTransfers[payloadId] = FileMetadata(fileName, mimeType)
                            Log.d(TAG, "File metadata received: $fileName ($mimeType) payloadId=$payloadId")
                        }
                    } else {
                        // Regular text message — same as before
                        onMessageReceived?.invoke(endpointId, text)
                    }
                }

                Payload.Type.FILE -> {
                    payloadToEndpoint[payload.id] = endpointId
                    // Save the file URI — this is the safe way to access the file
                    // asJavaFile() crashes on some Android versions
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
    } }
