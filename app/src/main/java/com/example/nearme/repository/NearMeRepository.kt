package com.example.nearme.repository
import com.example.nearme.nearby.NearbyManager
import android.content.Context
import com.example.nearme.ble.BleAdvertiser
import com.example.nearme.ble.BleScanner
import com.example.nearme.model.UserProfile
import com.example.nearme.util.LocalAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.example.nearme.data.AppDatabase
import com.example.nearme.model.Message
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.app.Notification
import com.example.nearme.MainActivity
import com.example.nearme.R
import com.example.nearme.wifiaware.WifiAwareManager
import android.util.Log
import java.io.File
import androidx.annotation.RequiresApi
import android.os.Handler
import android.os.Looper

class NearMeRepository(private val context: Context) {

    // ─── State Machine ────────────────────────────────────────────────────────
    enum class RadioState {
        STANDBY,          // BLE + NC advertise + Wi-Fi Aware publish all running
        NC_CHAT,          // Active NC connection → Wi-Fi Aware paused
        NAN_CHAT,         // Active Wi-Fi Aware data path → NC advertise paused
        EXTENDED_SEARCH   // Wi-Fi Aware subscribe ON (manual, temporary)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val _radioState = MutableStateFlow(RadioState.STANDBY)
    val radioState: StateFlow<RadioState> = _radioState.asStateFlow()

    // replay = 1 ensures late collectors (ChatViewModel opening after
// connection is already established) still receive the endpointId
    private val _connectedEndpointId = MutableSharedFlow<Pair<String, String>>(replay = 1)
    val connectedEndpointId: SharedFlow<Pair<String, String>> = _connectedEndpointId.asSharedFlow()



    // ─── Coroutine Scope ──────────────────────────────────────────────────────
    // The repository needs its own coroutine scope because it outlives any
    // single ViewModel. SupervisorJob means if one coroutine crashes, the
    // others keep running. Dispatchers.Main because StateFlow updates
    // must happen on the main thread.
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ─── BLE Managers ─────────────────────────────────────────────────────────
    // Moved here from DiscoveryViewModel so the service keeps them alive
    private val bleAdvertiser = BleAdvertiser(context)
    private val bleScanner = BleScanner(context)

    // ─── Managers Placeholders (coming next) ──────────────────────────────────
    private val nearbyManager = NearbyManager(context)

    private val messageDao = AppDatabase.getInstance(context).messageDao()

    private var activeConversationId: String? = null

    private var activeTransport: String? = null  // "NC" or "NDP"
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val wifiAwareManager = WifiAwareManager(context)

    // Exposed to UI so "Search Further" button only appears on
    // phones with Wi-Fi Aware hardware (S21 yes, J4+ no)
    val wifiAwareAvailable: Boolean get() = wifiAwareManager.isSupported

    // ─── Discovered Users ─────────────────────────────────────────────────────
    // Moved here from DiscoveryViewModel — same logic, new home
    // Key = shortId to prevent duplicate entries for the same person
    private val usersMap = mutableMapOf<String, UserProfile>()

    private val _discoveredUsers = MutableStateFlow<List<UserProfile>>(emptyList())
    val discoveredUsers: StateFlow<List<UserProfile>> = _discoveredUsers.asStateFlow()

    // Tracks whether a Wi-Fi Aware subscribe session is currently active.
    // The UI uses this to show a spinner on the "Search Further" button
   // and disable it while search is already running.
    private val _isExtendedSearching = MutableStateFlow(false)
    val isExtendedSearching: StateFlow<Boolean> = _isExtendedSearching.asStateFlow()

    // How long before a user is considered gone (30 seconds)
    private val staleTimeout = 30_000L

    // ─── BLE Control ──────────────────────────────────────────────────────────

    // Called by the foreground service in onCreate() to start BLE
    fun setActiveConversation(conversationId: String?) {
        activeConversationId = conversationId
        if (conversationId != null) {
            notificationManager.cancel(conversationId.hashCode() + MESSAGE_NOTIFICATION_BASE_ID)
        }
    }
    fun createMessageNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "NearMe Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming messages"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    private fun postMessageNotification(senderShortId: String, messageText: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, senderShortId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, MESSAGE_CHANNEL_ID)
            .setContentTitle("NearMe")
            .setContentText("New message from $senderShortId")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(
            senderShortId.hashCode() + MESSAGE_NOTIFICATION_BASE_ID,
            notification
        )
    }
    fun startBle() {
        val shortId = LocalAuth.getShortId(context)
        val displayName = LocalAuth.getDisplayName(context)

        // Start broadcasting our presence — runs forever in background
        bleAdvertiser.startAdvertising(shortId, displayName)

        // Start scanning in LOW_POWER mode — battery friendly for always-on background use
        // LOW_POWER scans less frequently than LOW_LATENCY but uses far less battery
        bleScanner.startScanning(scanMode = "LOW_POWER") { userProfile ->
            usersMap[userProfile.shortId] = userProfile
            _discoveredUsers.value = usersMap.values.toList()
            // If someone is calling us, start NC discovery for them too
            if (userProfile.status == "Calling") {
                nearbyManager.startDiscoveryForTarget(userProfile.shortId)
            }
        }

        // Start the stale user cleanup job
        startCleanupJob()
    }

    // Called by the foreground service in onDestroy()
    fun stopBle() {
        bleAdvertiser.stopAdvertising()
        bleScanner.stopScanning()
    }

    // ─── Wi-Fi Aware Control ──────────────────────────────────────────────────

    // Called by the foreground service in onCreate() after startNc().
    // Wires up callbacks and starts always-on publishing so other NearMe
    // phones can find us at extended range (50-100m vs BLE's ~30m).
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startWifiAware() {
        val shortId = LocalAuth.getShortId(context)
        val displayName = LocalAuth.getDisplayName(context)

        // When Wi-Fi Aware finds a nearby NearMe publisher, feed the
        // UserProfile into the same usersMap as BLE — keyed by shortId,
        // so if BLE AND Wi-Fi Aware both see the same person, they merge
        // into one entry instead of duplicating.
        wifiAwareManager.onUserDiscovered = { userProfile ->
            usersMap[userProfile.shortId] = userProfile
            _discoveredUsers.value = usersMap.values.toList()
            Log.d("REPO", "Wi-Fi Aware discovered: ${userProfile.displayName}#${userProfile.shortId}")
        }

        // When the 30-second subscribe timeout fires (or manual stop),
        // flip the state back to STANDBY and update the button state.
        wifiAwareManager.onSubscribeStopped = {
            _isExtendedSearching.value = false
            if (_radioState.value == RadioState.EXTENDED_SEARCH) {
                _radioState.value = RadioState.STANDBY
            }
            Log.d("REPO", "Wi-Fi Aware subscribe stopped — back to STANDBY")
        }

        // Register receiver so we handle Wi-Fi on/off events
        // (mirrors how BluetoothStateReceiver handles BT toggle)
        wifiAwareManager.registerAvailabilityReceiver()

        wifiAwareManager.onChatRequested = { peerShortId, peerHandle ->
            Log.d("REPO", "Chat requested via Wi-Fi Aware from: $peerShortId")
            _radioState.value = RadioState.NAN_CHAT
            wifiAwareManager.startNdpServer(peerShortId, peerHandle)
        }

        wifiAwareManager.onNdpConnected = { peerShortId ->
            activeTransport = "NDP"
            _radioState.value = RadioState.NAN_CHAT
            Log.d("REPO", "NDP connected to $peerShortId")
            repositoryScope.launch {
                _connectedEndpointId.emit(Pair(peerShortId, "NDP_$peerShortId"))
            }
        }

        wifiAwareManager.onNdpMessageReceived = { peerShortId, messageText ->
            when {
                messageText.startsWith("MSG:") -> {
                    val firstColon = messageText.indexOf(':')
                    val secondColon = messageText.indexOf(':', firstColon + 1)
                    if (secondColon != -1) {
                        val messageId = messageText.substring(firstColon + 1, secondColon)
                        val text = messageText.substring(secondColon + 1)
                        repositoryScope.launch {
                            val message = Message(
                                conversationId = peerShortId,
                                senderName = peerShortId,
                                content = text,
                                isFromMe = false,
                                status = "delivered"
                            )
                            messageDao.insertMessage(message)
                            if (activeConversationId != peerShortId) {
                                postMessageNotification(peerShortId, text)
                            }
                        }
                        wifiAwareManager.sendOverNdp("MSG_ACK:$messageId")
                    }
                }
                messageText.startsWith("MSG_ACK:") -> {
                    val id = messageText.removePrefix("MSG_ACK:")
                    repositoryScope.launch { messageDao.updateStatus(id, "delivered") }
                }
                messageText.startsWith("FILE_ACK:") -> {
                    val id = messageText.removePrefix("FILE_ACK:")
                    repositoryScope.launch { messageDao.updateStatus(id, "delivered") }
                }
            }
        }

        wifiAwareManager.onNdpDisconnected = { peerShortId ->
            Log.d("REPO", "NDP disconnected from $peerShortId")
            activeTransport = null
            ndpFileAssemblies.clear()
            if (_radioState.value == RadioState.NAN_CHAT) {
                _radioState.value = RadioState.STANDBY
            }
        }

        // Start always-on publishing — makes us discoverable at extended range
        wifiAwareManager.startPublishing(shortId, displayName)
    }

    // Called by the foreground service in onDestroy() before stopNc().
    @RequiresApi(Build.VERSION_CODES.Q)
    fun stopWifiAware() {
        wifiAwareManager.stopAll()
    }


    // ─── NC Control ───────────────────────────────────────────────────────

    // Called by the foreground service to start NC advertising in background.
// This makes the phone reachable for incoming chat requests at all times.
// NC advertising in standby uses BLE only — no Wi-Fi conflict.
    fun startNc() {
        val shortId = LocalAuth.getShortId(context)
        val displayName = LocalAuth.getDisplayName(context)

        // Wire up callbacks so incoming messages and connections
        // reach the repository even when the app is in the background
        nearbyManager.onMessageReceived = { endpointId, messageText ->
            val senderShortId = nearbyManager.getShortIdForEndpoint(endpointId) ?: "unknown"
            repositoryScope.launch {
                val message = Message(
                    conversationId = senderShortId,
                    senderName = senderShortId,
                    content = messageText,
                    isFromMe = false,
                    status = "delivered"

                )
                messageDao.insertMessage(message)
                // Now this check runs on Main thread  same thread that writes activeConversationId
                if (activeConversationId != senderShortId) {
                    postMessageNotification(senderShortId, messageText)
                }
            }
            android.util.Log.d("REPO", "Message received from $senderShortId: $messageText")
        }

        nearbyManager.onConnected = { endpointId ->
            android.util.Log.d("REPO", "NC connected: $endpointId")
            val myShortId = LocalAuth.getShortId(context)
            val myDisplayName = LocalAuth.getDisplayName(context)
            bleAdvertiser.startAdvertising(myShortId, myDisplayName, STATUS_AVAILABLE)
            // Look up WHO we just connected to
            val peerShortId = nearbyManager.getShortIdForEndpoint(endpointId) ?: "unknown"
            repositoryScope.launch {
                _connectedEndpointId.emit(Pair(peerShortId, endpointId))
            }
        }

        nearbyManager.onDisconnected = { endpointId ->
            android.util.Log.d("REPO", "NC disconnected: $endpointId")

            wifiAwareManager.resumePublishing()

            // If we were in NC_CHAT, return to STANDBY
            if (_radioState.value == RadioState.NC_CHAT) {
                _radioState.value = RadioState.STANDBY
            }
        }

        // When a file finishes transferring, save it as a message in Room
        // Same pattern as onMessageReceived but with filePath and mimeType
        // When a file finishes transferring, save it as a message in Room
        nearbyManager.onFileReceived = { endpointId, file, fileName, mimeType ->
            val senderShortId = nearbyManager.getShortIdForEndpoint(endpointId) ?: "unknown"
            repositoryScope.launch {
                val message = Message(
                    conversationId = senderShortId,
                    senderName = senderShortId,
                    content = fileName,
                    isFromMe = false,
                    status = "delivered",
                    filePath = file.absolutePath,
                    mimeType = mimeType
                )
                messageDao.insertMessage(message)
                if (activeConversationId != senderShortId) {
                    postMessageNotification(senderShortId, "Sent you a file: $fileName")
                }
            }
            Log.d("REPO", "File received from $senderShortId: $fileName")
        }

        // When we receive a delivery ACK (MSG_ACK or FILE_ACK),
        // update the corresponding message in Room to "delivered"
        nearbyManager.onAckReceived = { messageId ->
            repositoryScope.launch {
                messageDao.updateStatus(messageId, "delivered")
                Log.d("REPO", "Message delivered: $messageId")
            }
        }

        // Start always-on advertising so others can reach us
        nearbyManager.startAdvertising(shortId, displayName)
    }

    // Called by the foreground service in onDestroy
    fun stopNc() {
        nearbyManager.stopAllConnections()
    }

    // Add this method to NearMeRepository
    fun getEndpointForShortId(shortId: String): String? {
        nearbyManager.getEndpointForShortId(shortId)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && wifiAwareManager.isNdpConnectedTo(shortId)) {
            return "NDP_$shortId"
        }
        return null
    }

    // Called by ChatViewModel when user taps a person to start chatting.
// Starts NC discovery filtered to only connect to that specific person.
    @RequiresApi(Build.VERSION_CODES.Q)
    fun connectToUser(targetShortId: String) {
        val user = usersMap[targetShortId]

        if (user?.discoverySource == "WIFI_AWARE" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d("REPO", "Initiating NDP to $targetShortId — sending CHAT_REQ, awaiting CHAT_ACK")
            wifiAwareManager.onChatAcked = { acker ->
                if (acker == targetShortId) {
                    Log.d("REPO", "Received CHAT_ACK from $targetShortId — starting NDP client")
                    wifiAwareManager.startNdpClient(targetShortId)
                    wifiAwareManager.onChatAcked = null
                }
            }
            wifiAwareManager.sendChatRequest(targetShortId)
        } else {
            wifiAwareManager.pausePublishing()
            _radioState.value = RadioState.NC_CHAT
            val shortId = LocalAuth.getShortId(context)
            val displayName = LocalAuth.getDisplayName(context)
            bleAdvertiser.startAdvertising(shortId, displayName, STATUS_CALLING)
            nearbyManager.startDiscoveryForTarget(targetShortId)
        }
    }

    // Called by ChatViewModel to send a message through the NC connection
    // Called by ChatViewModel to send a text message with delivery tracking
    fun sendNcMessage(endpointId: String, messageId: String, message: String) {
        nearbyManager.sendMessage(endpointId, messageId, message)
    }

    // Called by ChatViewModel to send a file through the NC connection
    // Called by ChatViewModel to send a file with delivery tracking
    fun sendNcFile(endpointId: String, file: File, fileName: String, mimeType: String, messageId: String) {
        nearbyManager.sendFile(endpointId, file, fileName, mimeType, messageId)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun sendChatMessage(endpointId: String, messageId: String, text: String) {
        if (activeTransport == "NDP" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiAwareManager.sendOverNdp("MSG:$messageId:$text")
        } else {
            nearbyManager.sendMessage(endpointId, messageId, text)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun sendChatFile(endpointId: String, file: File, fileName: String, mimeType: String, messageId: String) {
        if (activeTransport == "NDP" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {            Thread {
                try {
                    val fileBytes = file.readBytes()
                    val chunkSize = 500 * 1024
                    val totalChunks = (fileBytes.size + chunkSize - 1) / chunkSize
                    wifiAwareManager.sendOverNdp("FILE_START:$messageId:$fileName:$mimeType:$totalChunks")
                    for (i in 0 until totalChunks) {
                        val start = i * chunkSize
                        val end = minOf(start + chunkSize, fileBytes.size)
                        val chunk = fileBytes.copyOfRange(start, end)
                        val base64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                        wifiAwareManager.sendOverNdp("FILE_CHUNK:$messageId:$i:$base64")
                    }
                } catch (e: Exception) {
                    Log.e("REPO", "NDP file send failed: ${e.message}")
                }
            }.start()
        } else {
            nearbyManager.sendFile(endpointId, file, fileName, mimeType, messageId)
        }
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun finalizeNdpFile(messageId: String, assembly: NdpFileAssembly) {
        ndpFileAssemblies.remove(messageId)
        // Disk I/O off the main thread.
        repositoryScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Mirror the directory NC uses so saveFileToDevice() in ChatScreen
                // works identically regardless of which transport delivered the file.
                val receivedDir = File(context.filesDir, "received_files").apply { mkdirs() }
                var targetFile = File(receivedDir, assembly.fileName)
                if (targetFile.exists()) {
                    val base = assembly.fileName.substringBeforeLast(".", assembly.fileName)
                    val ext = assembly.fileName.substringAfterLast(".", "")
                    val unique = if (ext.isNotEmpty())
                        "${base}_${System.currentTimeMillis()}.$ext"
                    else
                        "${assembly.fileName}_${System.currentTimeMillis()}"
                    targetFile = File(receivedDir, unique)
                }

                java.io.FileOutputStream(targetFile).use { out ->
                    for (chunk in assembly.chunks) {
                        if (chunk != null) out.write(chunk)
                    }
                }

                // Insert into Room — identical shape to NC-received files
                // so the chat UI doesn't need to know which transport was used.
                val msg = Message(
                    conversationId = assembly.senderShortId,
                    senderName = assembly.senderShortId,
                    content = assembly.fileName,
                    isFromMe = false,
                    status = "delivered",
                    filePath = targetFile.absolutePath,
                    mimeType = assembly.mimeType
                )
                messageDao.insertMessage(msg)

                // ACK back so the sender's "sending ⏳" flips to "delivered ✓✓".
                wifiAwareManager.sendOverNdp("FILE_ACK:$messageId")

                if (activeConversationId != assembly.senderShortId) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        postMessageNotification(
                            assembly.senderShortId,
                            "Sent you a file: ${assembly.fileName}"
                        )
                    }
                }
                Log.d("REPO", "NDP file saved: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e("REPO", "Failed to finalize NDP file: ${e.message}")
            }
        }
    }
    // Called when user leaves the chat screen
    fun disconnectNc(endpointId: String) {
        nearbyManager.disconnect(endpointId)
        _radioState.value = RadioState.STANDBY
    }

    // ─── Stale User Cleanup ───────────────────────────────────────────────────
    // Moved here from DiscoveryViewModel — same logic, runs in repositoryScope
    // so it keeps running even when the Discovery screen is closed
    private fun startCleanupJob() {
        repositoryScope.launch {
            while (true) {
                delay(10_000) // Check every 10 seconds
                val now = System.currentTimeMillis()

                val staleIds = usersMap.filter { (_, user) ->
                    now - user.lastSeen > staleTimeout
                }.keys

                if (staleIds.isNotEmpty()) {
                    staleIds.forEach { usersMap.remove(it) }
                    _discoveredUsers.value = usersMap.values.toList()
                }
            }
        }
    }

    // ─── State Transitions ────────────────────────────────────────────────────


    @RequiresApi(Build.VERSION_CODES.Q)
    fun enterNanChat() {
        _radioState.value = RadioState.NAN_CHAT
        activeTransport = "NDP"
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    fun exitNanChat() {
        wifiAwareManager.disconnectNdp()
        activeTransport = null
        _radioState.value = RadioState.STANDBY
        wifiAwareManager.resumePublishing()
    }

    fun startExtendedSearch() {
        if (!wifiAwareManager.isSupported) {
            Log.d("REPO", "Extended search unavailable — no Wi-Fi Aware hardware")
            return
        }
        _radioState.value = RadioState.EXTENDED_SEARCH
        _isExtendedSearching.value = true
        wifiAwareManager.startSubscribing()
        Log.d("REPO", "Extended search started (30-second Wi-Fi Aware subscribe)")
    }

    fun stopExtendedSearch() {
        wifiAwareManager.stopSubscribing()
        _isExtendedSearching.value = false
        _radioState.value = RadioState.STANDBY
        Log.d("REPO", "Extended search stopped manually")
    }

    // ─── NDP File Reassembly ──────────────────────────────────────────────
   // One entry per in-flight file transfer, keyed by sender's messageId.
   // Chunks come in over a single TCP stream so they arrive in order, but
   // we index them anyway so duplicate/out-of-order would still work.
    private class NdpFileAssembly(
        val fileName: String,
        val mimeType: String,
        val totalChunks: Int,
        val senderShortId: String
    ) {
        val chunks: Array<ByteArray?> = arrayOfNulls(totalChunks)
        var receivedCount: Int = 0
        val startedAt: Long = System.currentTimeMillis()
    }
    private val ndpFileAssemblies = mutableMapOf<String, NdpFileAssembly>()

    // ─── Singleton ────────────────────────────────────────────────────────────
    companion object {
        private const val MESSAGE_CHANNEL_ID = "nearme_messages"
        private const val MESSAGE_NOTIFICATION_BASE_ID = 1000

        const val STATUS_AVAILABLE: Byte = 0
        const val STATUS_CALLING: Byte = 1  // "I want to connect to someone"

        @Volatile
        private var INSTANCE: NearMeRepository? = null

        fun getInstance(context: Context): NearMeRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NearMeRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }}
