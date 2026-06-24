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
import com.example.nearme.util.GroupStore
import com.example.nearme.util.GroupInfo
import com.example.nearme.util.GroupMember
import com.example.nearme.util.ContactStore
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class NearMeRepository(private val context: Context) {

    // ─── State Machine ────────────────────────────────────────────────────────
    enum class RadioState {
        STANDBY,          // BLE + NC advertise + Wi-Fi Aware publish all running
        NC_CHAT,          // Active 1:1 NC connection → Wi-Fi Aware paused
        NAN_CHAT,         // Active Wi-Fi Aware data path → NC advertise paused
        EXTENDED_SEARCH,  // Wi-Fi Aware subscribe ON (manual, temporary)
        GROUP_CHAT        // Active NC group session (hub or spoke) → Wi-Fi Aware paused
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
    // endpointId → shortId captured at connect time, so onDisconnected (which fires
    // AFTER NearbyManager already cleared its own map) can still tell who left.
    private val endpointShortIdCache = mutableMapOf<String, String>()

    // Group invites are processed ONE AT A TIME. Firing all of them at once would
    // re-create the radio-contention deadlock that breaks NC connections.
    private data class PendingInvite(val groupId: String, val shortId: String)
    private val inviteQueue = ArrayDeque<PendingInvite>()
    private var currentInvite: PendingInvite? = null

    // shortIds currently connected to the hub as group members. When this empties
    // after a disconnect, the radio returns to STANDBY and Wi-Fi Aware resumes.
    private val connectedGroupMembers = mutableSetOf<String>()

    /** Tracks in-flight group file transfers: messageId → group context. */
    private data class PendingGroupFile(
        val groupId: String,
        val originShort: String,
        val originName: String
    )
    /** Public — exposed to ViewModel for the approval dialog. */
    data class IncomingFileRequest(
        val senderShortId: String,
        val endpointId: String,
        val messageId: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val transport: String  // "NC" or "NDP"
    )

    /** Private — sender-side tracking until receiver accepts or rejects. */
    private data class PendingOutgoingFile(
        val endpointId: String,
        val tempFile: File,
        val fileName: String,
        val mimeType: String,
        val transport: String  // "NC" or "NDP"
    )

    /** Sender's pending files keyed by messageId. */
    private val pendingOutgoingFiles = mutableMapOf<String, PendingOutgoingFile>()

    /** Receiver's pending requests keyed by senderShortId so chat can seed on open. */
    private val pendingIncomingRequests = mutableMapOf<String, IncomingFileRequest>()

    /** Live stream of new file requests for the chat UI to react to. */
    private val _incomingFileRequests = MutableSharedFlow<IncomingFileRequest>(extraBufferCapacity = 16)
    val incomingFileRequests: SharedFlow<IncomingFileRequest> = _incomingFileRequests.asSharedFlow()
    private val pendingGroupFiles = mutableMapOf<String, PendingGroupFile>()

    init {
        // Load saved groups as soon as the repository exists (before any UI collects).
        GroupStore.load(context)
    }
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
            // INSTANT_MESSAGE usage tells Android this is a chat ping —
            // affects how the system prioritizes the sound across streams.
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            // Pattern: wait 0ms, buzz 300ms, wait 150ms, buzz 300ms. Two short pulses = "message" feel.
            val vibrationPattern = longArrayOf(0, 300, 150, 300)

            val channel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "NearMe Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming messages"
                enableVibration(true)
                this.vibrationPattern = vibrationPattern
                setSound(soundUri, audioAttributes)
                // bypassDnd needs the user to grant Notification Policy access in Settings
                // to actually take effect — otherwise it's a hint that's silently ignored.
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

        val senderName = ContactStore.getName(context, senderShortId) ?: senderShortId

        val notification = Notification.Builder(context, MESSAGE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_message_title, senderName))
            .setContentText(messageText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(
            senderShortId.hashCode() + MESSAGE_NOTIFICATION_BASE_ID,
            notification
        )

        // Manual vibrator call — the Vibrator API doesn't respect the ringer's silent
        // mode, so this fires even when the channel's own sound/vibration is suppressed.
        triggerVibration()
    }

    /**
     * Manually vibrates the phone with a short two-pulse pattern.
     * Works even when the phone is in silent mode.
     */
    private fun triggerVibration() {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return

            // -1 = play once, no repeat
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            Log.e("REPO", "Vibration failed: ${e.message}")
        }
    }
    fun startBle() {
        val shortId = LocalAuth.getShortId(context)
        val displayName = LocalAuth.getDisplayName(context)

        // Start broadcasting our presence — runs forever in background
        bleAdvertiser.startAdvertising(shortId, displayName)

        // Start scanning in LOW_POWER mode — battery friendly for always-on background use
        // LOW_POWER scans less frequently than LOW_LATENCY but uses far less battery
        bleScanner.startScanning(scanMode = "LOW_POWER") { userProfile ->
            ContactStore.saveName(context, userProfile.shortId, userProfile.displayName)   // ← ADD THIS

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
            ContactStore.saveName(context, userProfile.shortId, userProfile.displayName)

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
                messageText.startsWith("FILE_REQ:") -> {
                    val parts = messageText.removePrefix("FILE_REQ:").split(":", limit = 4)
                    if (parts.size == 4) {
                        val messageId = parts[0]
                        val fileName = parts[1]
                        val mimeType = parts[2]
                        val sizeBytes = parts[3].toLongOrNull() ?: 0L
                        val req = IncomingFileRequest(
                            peerShortId, "NDP_$peerShortId", messageId,
                            fileName, mimeType, sizeBytes, "NDP"
                        )
                        pendingIncomingRequests[peerShortId] = req
                        repositoryScope.launch { _incomingFileRequests.emit(req) }
                        if (activeConversationId != peerShortId) {
                            postMessageNotification(peerShortId, context.getString(R.string.notif_file_request, fileName))
                        }
                        Log.d("REPO", "NDP file request received: $fileName from $peerShortId")
                    }
                }

                messageText.startsWith("FILE_REQ_ACCEPT:") -> {
                    val messageId = messageText.removePrefix("FILE_REQ_ACCEPT:")
                    val pending = pendingOutgoingFiles.remove(messageId)
                    if (pending != null) {
                        repositoryScope.launch { messageDao.updateStatus(messageId, "sending") }
                        Thread {
                            try {
                                val fileBytes = pending.tempFile.readBytes()
                                val chunkSize = 500 * 1024
                                val totalChunks = (fileBytes.size + chunkSize - 1) / chunkSize
                                wifiAwareManager.sendOverNdp("FILE_START:$messageId:${pending.fileName}:${pending.mimeType}:$totalChunks")
                                for (i in 0 until totalChunks) {
                                    val start = i * chunkSize
                                    val end = minOf(start + chunkSize, fileBytes.size)
                                    val chunk = fileBytes.copyOfRange(start, end)
                                    val base64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                                    wifiAwareManager.sendOverNdp("FILE_CHUNK:$messageId:$i:$base64")
                                }
                                repositoryScope.launch { messageDao.updateStatus(messageId, "sent") }
                                repositoryScope.launch {
                                    kotlinx.coroutines.delay(10_000)
                                    pending.tempFile.delete()
                                }
                            } catch (e: Exception) {
                                Log.e("REPO", "NDP file send failed after accept: ${e.message}")
                            }
                        }.start()
                        Log.d("REPO", "NDP file request accepted, sending file: $messageId")
                    }
                }

                messageText.startsWith("FILE_REQ_REJECT:") -> {
                    val messageId = messageText.removePrefix("FILE_REQ_REJECT:")
                    val pending = pendingOutgoingFiles.remove(messageId)
                    if (pending != null) {
                        repositoryScope.launch { messageDao.updateStatus(messageId, "rejected") }
                        pending.tempFile.delete()
                        Log.d("REPO", "NDP file request rejected: $messageId")
                    }
                }
                messageText.startsWith("FILE_ACK:") -> {
                    val id = messageText.removePrefix("FILE_ACK:")
                    repositoryScope.launch { messageDao.updateStatus(id, "delivered") }
                }
                messageText.startsWith("FILE_START:") -> {
                    val parts = messageText.removePrefix("FILE_START:").split(":", limit = 4)
                    if (parts.size == 4) {
                        val messageId = parts[0]
                        val fileName = parts[1]
                        val mimeType = parts[2]
                        val totalChunks = parts[3].toIntOrNull()
                        if (totalChunks != null && totalChunks > 0) {
                            ndpFileAssemblies[messageId] = NdpFileAssembly(
                                fileName = fileName,
                                mimeType = mimeType,
                                totalChunks = totalChunks,
                                senderShortId = peerShortId
                            )
                            Log.d("REPO", "NDP file incoming: $fileName ($totalChunks chunks) from $peerShortId")
                        }
                    }
                }
                messageText.startsWith("FILE_CHUNK:") -> {
                    val parts = messageText.removePrefix("FILE_CHUNK:").split(":", limit = 3)
                    if (parts.size == 3) {
                        val messageId = parts[0]
                        val index = parts[1].toIntOrNull()
                        val base64 = parts[2]
                        val assembly = ndpFileAssemblies[messageId]
                        if (assembly != null && index != null && index in 0 until assembly.totalChunks) {
                            if (assembly.chunks[index] == null) {
                                try {
                                    assembly.chunks[index] = android.util.Base64.decode(
                                        base64, android.util.Base64.NO_WRAP
                                    )
                                    assembly.receivedCount++
                                    if (assembly.receivedCount == assembly.totalChunks) {
                                        finalizeNdpFile(messageId, assembly)
                                    }
                                } catch (e: Exception) {
                                    Log.e("REPO", "Failed to decode FILE_CHUNK $index: ${e.message}")
                                }
                            }
                        }
                    }
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
            val peerShortId = nearbyManager.getShortIdForEndpoint(endpointId) ?: "unknown"
            endpointShortIdCache[endpointId] = peerShortId

            val invite = currentInvite
            if (invite != null) {
                // Mid-invite: only drop the "Calling" beacon when the RIGHT person
                // connects — a bystander connecting first must not cut the beacon short.
                if (invite.shortId == peerShortId) {
                    bleAdvertiser.startAdvertising(myShortId, myDisplayName, STATUS_AVAILABLE)
                    onInviteeConnected(endpointId, peerShortId, invite.groupId)
                }
            } else {
                // Normal 1:1 connect — reset the beacon as before.
                bleAdvertiser.startAdvertising(myShortId, myDisplayName, STATUS_AVAILABLE)
            }

            repositoryScope.launch {
                _connectedEndpointId.emit(Pair(peerShortId, endpointId))
            }
        }

        nearbyManager.onDisconnected = { endpointId ->
            val peerShortId = endpointShortIdCache.remove(endpointId)
            android.util.Log.d("REPO", "NC disconnected: $endpointId ($peerShortId)")

            if (_radioState.value == RadioState.GROUP_CHAT) {
                // Group session: handle roster, DON'T blanket-resume Wi-Fi Aware.
                handleGroupMemberDisconnect(peerShortId)
            } else {
                wifiAwareManager.resumePublishing()
                if (_radioState.value == RadioState.NC_CHAT) {
                    _radioState.value = RadioState.STANDBY
                }
            }
        }

        // When a file finishes transferring, save it as a message in Room
        // Same pattern as onMessageReceived but with filePath and mimeType
        // When a file finishes transferring, save it as a message in Room
        nearbyManager.onFileReceived = { endpointId, file, fileName, mimeType, messageId ->
            val groupContext = pendingGroupFiles.remove(messageId)
            if (groupContext != null) {
                handleGroupFileReceived(endpointId, file, fileName, mimeType, messageId, groupContext)
            } else {
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
        }

        // When we receive a delivery ACK (MSG_ACK or FILE_ACK),
        // update the corresponding message in Room to "delivered"
        nearbyManager.onAckReceived = { messageId ->
            repositoryScope.launch {
                messageDao.updateStatus(messageId, "delivered")
                Log.d("REPO", "Message delivered: $messageId")
            }
        }
        // Receiver side — incoming file request from peer
        nearbyManager.onFileRequestReceived = { endpointId, messageId, fileName, mimeType, sizeBytes ->
            val senderShortId = nearbyManager.getShortIdForEndpoint(endpointId)
            if (senderShortId != null) {
                val req = IncomingFileRequest(senderShortId, endpointId, messageId, fileName, mimeType, sizeBytes, "NC")
                pendingIncomingRequests[senderShortId] = req
                repositoryScope.launch { _incomingFileRequests.emit(req) }

                // Notify if user is not currently in this chat
                if (activeConversationId != senderShortId) {
                    postMessageNotification(
                        senderShortId,
                        context.getString(R.string.notif_file_request, fileName)
                    )
                }
                Log.d("REPO", "File request received: $fileName from $senderShortId")
            }
        }

// Sender side — receiver's accept/reject came back
        nearbyManager.onFileRequestResponse = { messageId, accepted ->
            val pending = pendingOutgoingFiles.remove(messageId)
            if (pending != null) {
                if (accepted) {
                    repositoryScope.launch { messageDao.updateStatus(messageId, "sending") }
                    nearbyManager.sendFile(
                        pending.endpointId, pending.tempFile,
                        pending.fileName, pending.mimeType, messageId
                    )
                    // Give NC time to read the temp file before we delete it
                    repositoryScope.launch {
                        kotlinx.coroutines.delay(10_000)
                        pending.tempFile.delete()
                    }
                    Log.d("REPO", "File request accepted, sending file: $messageId")
                } else {
                    repositoryScope.launch { messageDao.updateStatus(messageId, "rejected") }
                    pending.tempFile.delete()
                    Log.d("REPO", "File request rejected: $messageId")
                }
            }
        }
        // Route group-protocol payloads (GROUP_INVITE, GROUP_JOIN, GROUP_ROSTER,
        // GROUP_LEAVE, GMSG, GMSG_ACK) into the repository's group handler.
        nearbyManager.onGroupPayloadReceived = { endpointId, raw ->
            Log.d("REPO", "Group payload received from $endpointId: ${raw.take(60)}")
            handleGroupPayload(endpointId, raw)
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
    /** Called by ChatViewModel when chat opens — returns any pending request for this peer, or null. */
    fun getPendingFileRequest(senderShortId: String): IncomingFileRequest? =
        pendingIncomingRequests[senderShortId]

    /**
     * Sender side: instead of pushing the file blind, ask first.
     * The actual file send happens later when FILE_REQ_ACCEPT comes back.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun requestFileTransfer(
        endpointId: String,
        messageId: String,
        tempFile: File,
        fileName: String,
        mimeType: String
    ) {
        val transport = if (activeTransport == "NDP" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "NDP" else "NC"
        pendingOutgoingFiles[messageId] = PendingOutgoingFile(endpointId, tempFile, fileName, mimeType, transport)
        val size = tempFile.length()
        val payload = "FILE_REQ:$messageId:$fileName:$mimeType:$size"
        if (transport == "NDP") {
            wifiAwareManager.sendOverNdp(payload)
        } else {
            nearbyManager.sendRaw(endpointId, payload)
        }
        Log.d("REPO", "File request sent ($transport): $fileName ($size bytes) messageId=$messageId")
    }

    /** Receiver side: ChatViewModel calls this when user taps Accept or Reject. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun respondToFileRequest(messageId: String, accepted: Boolean) {
        val entry = pendingIncomingRequests.entries
            .firstOrNull { it.value.messageId == messageId } ?: return
        val req = entry.value
        pendingIncomingRequests.remove(entry.key)

        val opcode = if (accepted) "FILE_REQ_ACCEPT" else "FILE_REQ_REJECT"
        if (req.transport == "NDP") {
            wifiAwareManager.sendOverNdp("$opcode:$messageId")
        } else {
            nearbyManager.sendRaw(req.endpointId, "$opcode:$messageId")
        }
        Log.d("REPO", "File request response sent ($opcode, ${req.transport}) for $messageId")
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

    // ─── Group Chat ─────────────────────────────────────────────────────────

    // Strip wire delimiters from names so they can't corrupt the protocol or routes.
    private fun sanitize(s: String): String =
        s.replace(":", " ").replace("|", " ").replace(",", " ").replace("/", " ").trim()

    /** Create a group with this device as the hub. Returns the new groupId. */
    fun createGroup(name: String): String {
        val myShort = LocalAuth.getShortId(context)
        val myName = LocalAuth.getDisplayName(context)
        val groupId = "grp_" + java.util.UUID.randomUUID().toString().take(6)
        val cleanName = sanitize(name).ifBlank { "Group" }

        GroupStore.upsert(
            context,
            GroupInfo(groupId, cleanName, myShort, true, listOf(GroupMember(myShort, myName)))
        )
        // Lets the Chats list resolve the group's display name from ContactStore.
        ContactStore.saveName(context, groupId, cleanName)
        // First system line, so the group appears in the Chats list immediately.
        insertSystemMessage(groupId, context.getString(R.string.group_system_created))
        Log.d("REPO", "Group created: $groupId ($cleanName)")
        return groupId
    }

    /** Queue members to invite. Processed strictly one at a time. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun inviteMembersToGroup(groupId: String, shortIds: List<String>) {
        shortIds.forEach { inviteQueue.addLast(PendingInvite(groupId, it)) }
        if (currentInvite == null) processNextInvite()
    }

    private fun processNextInvite() {
        val next = inviteQueue.removeFirstOrNull()
        currentInvite = next
        if (next == null) {
            // Queue drained — drop the Calling beacon and stop discovery.
            nearbyManager.stopDiscovery()
            bleAdvertiser.startAdvertising(
                LocalAuth.getShortId(context), LocalAuth.getDisplayName(context), STATUS_AVAILABLE
            )
            return
        }

        wifiAwareManager.pausePublishing()
        _radioState.value = RadioState.GROUP_CHAT

        // Already connected (e.g. a bystander connected during a prior invite)? Reuse it.
        val existing = nearbyManager.getEndpointForShortId(next.shortId)
        if (existing != null) {
            onInviteeConnected(existing, next.shortId, next.groupId)
            scheduleInviteTimeout(next.shortId)
            return
        }

        // Reuse the proven BLE "Calling" + targeted NC discovery handshake.
        bleAdvertiser.startAdvertising(
            LocalAuth.getShortId(context), LocalAuth.getDisplayName(context), STATUS_CALLING
        )
        nearbyManager.startDiscoveryForTarget(next.shortId)
        scheduleInviteTimeout(next.shortId)
    }

    private fun scheduleInviteTimeout(shortId: String) {
        mainHandler.postDelayed({
            if (currentInvite?.shortId == shortId) {
                Log.w("REPO", "Invite to $shortId timed out — moving on")
                nearbyManager.stopDiscovery()
                currentInvite = null
                processNextInvite()
            }
        }, INVITE_TIMEOUT_MS)
    }

    /** Hub side: invitee's NC link is up → send the GROUP_INVITE control message. */
    private fun onInviteeConnected(endpointId: String, peerShortId: String, groupId: String) {
        val group = GroupStore.get(groupId) ?: return
        val myShort = LocalAuth.getShortId(context)
        val myName = LocalAuth.getDisplayName(context)
        nearbyManager.sendRaw(
            endpointId,
            "GROUP_INVITE:$groupId:${sanitize(group.groupName)}:$myShort:${sanitize(myName)}"
        )
        Log.d("REPO", "Sent GROUP_INVITE for $groupId to $peerShortId")
        // Roster add happens when GROUP_JOIN comes back.
    }

    /** Send a text message to a group. Hub fans out; a spoke routes via the hub. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun sendGroupMessage(groupId: String, messageId: String, text: String) {
        val group = GroupStore.get(groupId) ?: return
        val myShort = LocalAuth.getShortId(context)
        val myName = LocalAuth.getDisplayName(context)
        val gmsg = "GMSG:$groupId:$myShort:${sanitize(myName)}:$messageId:$text"

        if (group.isHub) {
            fanOutToMembers(group, gmsg, excludeShortId = myShort)
            // Hub's own message has no network hop — delivered immediately.
            repositoryScope.launch { messageDao.updateStatus(messageId, "delivered") }
        } else {
            val hubEndpoint = nearbyManager.getEndpointForShortId(group.hubShortId)
            if (hubEndpoint != null) nearbyManager.sendRaw(hubEndpoint, gmsg)
            else Log.w("REPO", "No hub connection for $groupId — message stays 'sent'")
        }
    }


    /** Hub helper: relay a GMSG to every member except the excluded shortId and myself. */
    private fun fanOutToMembers(group: GroupInfo, gmsg: String, excludeShortId: String) {
        val myShort = LocalAuth.getShortId(context)
        group.members.forEach { m ->
            if (m.shortId == excludeShortId || m.shortId == myShort) return@forEach
            val ep = nearbyManager.getEndpointForShortId(m.shortId) ?: return@forEach
            nearbyManager.sendRaw(ep, gmsg)
        }
    }
    /**
     * Send a file to a group. Hub fans the file out to all members; spoke
     * routes the file through the hub. The GFILE announce arrives before the
     * FILE payload so the receiver knows this file belongs to the group.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun sendGroupFile(groupId: String, file: File, fileName: String, mimeType: String, messageId: String) {
        val group = GroupStore.get(groupId) ?: return
        val myShort = LocalAuth.getShortId(context)
        val myName = LocalAuth.getDisplayName(context)
        val announce = "GFILE:$groupId:$myShort:${sanitize(myName)}:$messageId:$mimeType:$fileName"

        if (group.isHub) {
            group.members.forEach { m ->
                if (m.shortId == myShort) return@forEach
                val ep = nearbyManager.getEndpointForShortId(m.shortId) ?: return@forEach
                nearbyManager.sendRaw(ep, announce)
                nearbyManager.sendFile(ep, file, fileName, mimeType, messageId)
            }
            // Hub's own file has no network hop — delivered immediately.
            repositoryScope.launch { messageDao.updateStatus(messageId, "delivered") }
        } else {
            val hubEp = nearbyManager.getEndpointForShortId(group.hubShortId)
            if (hubEp != null) {
                nearbyManager.sendRaw(hubEp, announce)
                nearbyManager.sendFile(hubEp, file, fileName, mimeType, messageId)
            } else {
                Log.w("REPO", "No hub connection for $groupId — file stays 'sending'")
            }
        }
    }

    /** Handle a file payload whose announce was already registered as a group file. */
    private fun handleGroupFileReceived(
        endpointId: String,
        file: File,
        fileName: String,
        mimeType: String,
        messageId: String,
        ctx: PendingGroupFile
    ) {
        val group = GroupStore.get(ctx.groupId) ?: return
        val myShort = LocalAuth.getShortId(context)

        repositoryScope.launch {
            messageDao.insertMessage(
                Message(
                    conversationId = ctx.groupId,
                    senderName = ctx.originName,
                    content = fileName,
                    isFromMe = false,
                    status = "delivered",
                    filePath = file.absolutePath,
                    mimeType = mimeType
                )
            )
            if (activeConversationId != ctx.groupId) {
                postMessageNotification(ctx.groupId, "${ctx.originName}: $fileName")
            }
        }

        if (group.isHub) {
            // Tell origin we got it (1✓ → 2✓✓) and fan out to other spokes.
            nearbyManager.getEndpointForShortId(ctx.originShort)?.let {
                nearbyManager.sendRaw(it, "GFILE_ACK:${ctx.groupId}:$messageId")
            }
            val announce = "GFILE:${ctx.groupId}:${ctx.originShort}:${sanitize(ctx.originName)}:$messageId:$mimeType:$fileName"
            group.members.forEach { m ->
                if (m.shortId == myShort || m.shortId == ctx.originShort) return@forEach
                val ep = nearbyManager.getEndpointForShortId(m.shortId) ?: return@forEach
                nearbyManager.sendRaw(ep, announce)
                nearbyManager.sendFile(ep, file, fileName, mimeType, messageId)
            }
        }
        Log.d("REPO", "Group file received from ${ctx.originShort}: $fileName (group ${ctx.groupId})")
    }

    /** Hub helper: push the current member list to everyone. */
    private fun broadcastRoster(groupId: String) {
        val group = GroupStore.get(groupId) ?: return
        if (!group.isHub) return
        val rosterStr = group.members.joinToString(",") { "${it.shortId}|${sanitize(it.name)}" }
        val payload = "GROUP_ROSTER:$groupId:$rosterStr"
        val myShort = LocalAuth.getShortId(context)
        group.members.forEach { m ->
            if (m.shortId == myShort) return@forEach
            val ep = nearbyManager.getEndpointForShortId(m.shortId) ?: return@forEach
            nearbyManager.sendRaw(ep, payload)
        }
    }

    /** Leave (or, if hub, end) a group and free the radio. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun leaveGroup(groupId: String) {
        val group = GroupStore.get(groupId) ?: return
        val myShort = LocalAuth.getShortId(context)

        if (group.isHub) {
            group.members.forEach { m ->
                if (m.shortId == myShort) return@forEach
                val ep = nearbyManager.getEndpointForShortId(m.shortId) ?: return@forEach
                nearbyManager.sendRaw(ep, "GROUP_LEAVE:$groupId:$myShort")
                nearbyManager.disconnect(ep)
                connectedGroupMembers.remove(m.shortId)
            }
        } else {
            nearbyManager.getEndpointForShortId(group.hubShortId)?.let { ep ->
                nearbyManager.sendRaw(ep, "GROUP_LEAVE:$groupId:$myShort")
                nearbyManager.disconnect(ep)
            }
        }

        GroupStore.delete(context, groupId)
        // Also wipe the message history so the group disappears from the Chats list.
        repositoryScope.launch { messageDao.deleteConversation(groupId) }
        if (connectedGroupMembers.isEmpty()) {
            _radioState.value = RadioState.STANDBY
            wifiAwareManager.resumePublishing()
        }
        Log.d("REPO", "Left group $groupId")
    }

    private fun insertSystemMessage(groupId: String, text: String) {
        repositoryScope.launch {
            messageDao.insertMessage(
                Message(
                    conversationId = groupId,
                    senderName = "",          // empty senderName = system line
                    content = text,
                    isFromMe = false,
                    status = "delivered"
                )
            )
        }
    }

    private fun handleGroupMemberDisconnect(peerShortId: String?) {
        if (peerShortId == null) return
        GroupStore.groups.value.forEach { group ->
            if (group.isHub && group.members.any { it.shortId == peerShortId }) {
                GroupStore.removeMember(context, group.groupId, peerShortId)
                connectedGroupMembers.remove(peerShortId)
                broadcastRoster(group.groupId)
                insertSystemMessage(
                    group.groupId,
                    context.getString(
                        R.string.group_system_member_left,
                        ContactStore.getName(context, peerShortId) ?: peerShortId
                    )
                )
            } else if (!group.isHub && peerShortId == group.hubShortId) {
                insertSystemMessage(group.groupId, context.getString(R.string.group_system_host_left))
            }
        }
        if (connectedGroupMembers.isEmpty()) {
            _radioState.value = RadioState.STANDBY
            wifiAwareManager.resumePublishing()
        }
    }
    private fun handleGroupPayload(endpointId: String, raw: String) {
        when {
            raw.startsWith("GROUP_INVITE:") -> {
                val p = raw.removePrefix("GROUP_INVITE:").split(":", limit = 4)
                if (p.size < 4) return
                val groupId = p[0]; val groupName = p[1]; val hubShort = p[2]; val hubName = p[3]
                val myShort = LocalAuth.getShortId(context)
                val myName = LocalAuth.getDisplayName(context)

                // Spoke enters group mode; remember the hub link.
                wifiAwareManager.pausePublishing()
                _radioState.value = RadioState.GROUP_CHAT
                endpointShortIdCache[endpointId] = hubShort

                GroupStore.upsert(
                    context,
                    GroupInfo(
                        groupId, groupName, hubShort, false,
                        listOf(GroupMember(hubShort, hubName), GroupMember(myShort, myName))
                    )
                )
                ContactStore.saveName(context, groupId, groupName)
                insertSystemMessage(groupId, context.getString(R.string.group_system_you_joined, groupName))

                // Accept by replying with GROUP_JOIN.
                nearbyManager.sendRaw(endpointId, "GROUP_JOIN:$groupId:$myShort:${sanitize(myName)}")
                if (activeConversationId != groupId) {
                    postMessageNotification(groupId, context.getString(R.string.group_notif_added, hubName))
                }
                Log.d("REPO", "Joined group $groupId (hub $hubShort)")
            }

            raw.startsWith("GROUP_JOIN:") -> {
                val p = raw.removePrefix("GROUP_JOIN:").split(":", limit = 3)
                if (p.size < 3) return
                val groupId = p[0]; val joinerShort = p[1]; val joinerName = p[2]
                val group = GroupStore.get(groupId) ?: return
                if (!group.isHub) return

                GroupStore.addMember(context, groupId, GroupMember(joinerShort, joinerName))
                connectedGroupMembers.add(joinerShort)
                insertSystemMessage(groupId, context.getString(R.string.group_system_member_joined, joinerName))

                if (currentInvite?.shortId == joinerShort) {
                    currentInvite = null
                    processNextInvite()    // advance to the next invite
                }
                broadcastRoster(groupId)
                Log.d("REPO", "$joinerShort joined group $groupId")
            }

            raw.startsWith("GROUP_ROSTER:") -> {
                val p = raw.removePrefix("GROUP_ROSTER:").split(":", limit = 2)
                if (p.size < 2) return
                val groupId = p[0]
                val members = p[1].split(",").mapNotNull { entry ->
                    val pair = entry.split("|", limit = 2)
                    if (pair.size == 2) GroupMember(pair[0], pair[1]) else null
                }
                if (members.isNotEmpty()) GroupStore.setMembers(context, groupId, members)
            }

            raw.startsWith("GMSG_ACK:") -> {
                val p = raw.removePrefix("GMSG_ACK:").split(":", limit = 2)
                if (p.size < 2) return
                repositoryScope.launch { messageDao.updateStatus(p[1], "delivered") }
            }

            raw.startsWith("GROUP_LEAVE:") -> {
                val p = raw.removePrefix("GROUP_LEAVE:").split(":", limit = 2)
                if (p.size < 2) return
                val groupId = p[0]; val who = p[1]
                val group = GroupStore.get(groupId) ?: return
                // Capture the leaver's name BEFORE removing them from the roster.
                val leaverName = group.members.find { it.shortId == who }?.name
                    ?: ContactStore.getName(context, who) ?: who

                if (group.isHub) {
                    GroupStore.removeMember(context, groupId, who)
                    connectedGroupMembers.remove(who)
                    nearbyManager.getEndpointForShortId(who)?.let { nearbyManager.disconnect(it) }
                    broadcastRoster(groupId)
                    insertSystemMessage(
                        groupId,
                        context.getString(R.string.group_system_member_left, leaverName)
                    )
                    // Tell remaining spokes who left, so they can show "Ahmed left" too.
                    val myShort = LocalAuth.getShortId(context)
                    GroupStore.get(groupId)?.members?.forEach { m ->
                        if (m.shortId == myShort) return@forEach
                        val ep = nearbyManager.getEndpointForShortId(m.shortId) ?: return@forEach
                        nearbyManager.sendRaw(ep, raw)
                    }
                } else if (who == group.hubShortId) {
                    insertSystemMessage(groupId, context.getString(R.string.group_system_host_left))
                    _radioState.value = RadioState.STANDBY
                    wifiAwareManager.resumePublishing()
                } else {
                    // Spoke received notice that another spoke left.
                    GroupStore.removeMember(context, groupId, who)
                    insertSystemMessage(
                        groupId,
                        context.getString(R.string.group_system_member_left, leaverName)
                    )
                }
            }

            raw.startsWith("GMSG:") -> {
                val p = raw.removePrefix("GMSG:").split(":", limit = 5)
                if (p.size < 5) return
                val groupId = p[0]; val originShort = p[1]
                val originName = p[2]; val messageId = p[3]; val text = p[4]
                val group = GroupStore.get(groupId) ?: return

                // Save the incoming message; primary-key clash = duplicate, ignore.
                repositoryScope.launch {
                    try {
                        messageDao.insertMessage(
                            Message(
                                id = messageId,
                                conversationId = groupId,
                                senderName = originName,
                                content = text,
                                isFromMe = false,
                                status = "delivered"
                            )
                        )
                    } catch (e: Exception) {
                        return@launch
                    }
                    if (activeConversationId != groupId) {
                        postMessageNotification(groupId, "$originName: $text")
                    }
                }

                // Hub relays onward to everyone else and ACKs the author.
                if (group.isHub) {
                    fanOutToMembers(group, raw, excludeShortId = originShort)
                    nearbyManager.getEndpointForShortId(originShort)?.let { ep ->
                        nearbyManager.sendRaw(ep, "GMSG_ACK:$groupId:$messageId")
                    }
                }
            }
            raw.startsWith("GFILE:") -> {
                val p = raw.removePrefix("GFILE:").split(":", limit = 6)
                if (p.size < 6) return
                val groupId = p[0]
                val originShort = p[1]
                val originName = p[2]
                val messageId = p[3]
                // p[4] = mimeType, p[5] = fileName — used when the FILE payload arrives.
                pendingGroupFiles[messageId] = PendingGroupFile(groupId, originShort, originName)
                Log.d("REPO", "Group file announced: $messageId from $originShort (group $groupId)")
            }

            raw.startsWith("GFILE_ACK:") -> {
                val p = raw.removePrefix("GFILE_ACK:").split(":", limit = 2)
                if (p.size < 2) return
                repositoryScope.launch { messageDao.updateStatus(p[1], "delivered") }
            }
        }
    }



    // ─── Singleton ────────────────────────────────────────────────────────────
    companion object {
        private const val MESSAGE_CHANNEL_ID = "nearme_messages"
        private const val MESSAGE_NOTIFICATION_BASE_ID = 1000

        const val STATUS_AVAILABLE: Byte = 0
        const val STATUS_CALLING: Byte = 1  // "I want to connect to someone"
        private const val INVITE_TIMEOUT_MS = 15_000L  // skip an unreachable invitee after 15s

        @Volatile
        private var INSTANCE: NearMeRepository? = null

        fun getInstance(context: Context): NearMeRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NearMeRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
