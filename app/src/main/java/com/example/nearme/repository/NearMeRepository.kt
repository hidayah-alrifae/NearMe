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
import androidx.core.app.NotificationCompat
import com.example.nearme.MainActivity
import com.example.nearme.R

class NearMeRepository(private val context: Context) {

    // ─── State Machine ────────────────────────────────────────────────────────
    enum class RadioState {
        STANDBY,          // BLE + NC advertise + Wi-Fi Aware publish all running
        NC_CHAT,          // Active NC connection → Wi-Fi Aware paused
        NAN_CHAT,         // Active Wi-Fi Aware data path → NC advertise paused
        EXTENDED_SEARCH   // Wi-Fi Aware subscribe ON (manual, temporary)
    }

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
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // private lateinit var wifiAwareManager: WifiAwareManager

    // ─── Discovered Users ─────────────────────────────────────────────────────
    // Moved here from DiscoveryViewModel — same logic, new home
    // Key = shortId to prevent duplicate entries for the same person
    private val usersMap = mutableMapOf<String, UserProfile>()

    private val _discoveredUsers = MutableStateFlow<List<UserProfile>>(emptyList())
    val discoveredUsers: StateFlow<List<UserProfile>> = _discoveredUsers.asStateFlow()

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
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setContentTitle("NearMe")
            .setContentText("New message from $senderShortId")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
                    isFromMe = false
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
            // If we were in NC_CHAT, return to STANDBY
            if (_radioState.value == RadioState.NC_CHAT) {
                _radioState.value = RadioState.STANDBY
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
        return nearbyManager.getEndpointForShortId(shortId)
    }

    // Called by ChatViewModel when user taps a person to start chatting.
// Starts NC discovery filtered to only connect to that specific person.
    fun connectToUser(targetShortId: String) {
        _radioState.value = RadioState.NC_CHAT
        // Change BLE advertisement to "Calling" so the other phone
        // knows to start NC discovery for us
        val shortId = LocalAuth.getShortId(context)
        val displayName = LocalAuth.getDisplayName(context)
        bleAdvertiser.startAdvertising(shortId, displayName, STATUS_CALLING)
        nearbyManager.startDiscoveryForTarget(targetShortId)
    }

    // Called by ChatViewModel to send a message through the NC connection
    fun sendNcMessage(endpointId: String, message: String) {
        nearbyManager.sendMessage(endpointId, message)
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



    fun enterNanChat() {
        // TODO: pause NC advertising to prevent Wi-Fi Direct conflict with NDP
        _radioState.value = RadioState.NAN_CHAT
    }

    fun exitNanChat() {
        // TODO: resume NC advertising
        _radioState.value = RadioState.STANDBY
    }

    fun startExtendedSearch() {
        // TODO: start Wi-Fi Aware subscribe, auto-stop after 30 seconds
        _radioState.value = RadioState.EXTENDED_SEARCH
    }

    fun stopExtendedSearch() {
        _radioState.value = RadioState.STANDBY
    }

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
    }
}