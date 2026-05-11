package com.example.nearme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.nearme.MainActivity
import com.example.nearme.R
import com.example.nearme.repository.NearMeRepository

class NearMeService : Service() {

    // The repository reference
    // The service owns this  keeps it alive even when all Activities die
    private lateinit var repository: NearMeRepository

    // Constants
    companion object {
        // Channel ID for the persistent "keeping you discoverable" notification
        private const val CHANNEL_ID = "nearme_discovery"

        // Unique ID for the foreground notification Android uses this to
        // identify and update the notification later if needed
        private const val NOTIFICATION_ID = 1

        //  Helper to start the service from anywhere in the app
        // On Android 8+ we must use startForegroundService() instead of
        // startService(), otherwise the system throws an exception
        fun start(context: Context) {
            val intent = Intent(context, NearMeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // Helper to stop the service
        fun stop(context: Context) {
            context.stopService(Intent(context, NearMeService::class.java))
        }
    }

    //  Service Lifecycle

    override fun onCreate() {
        super.onCreate()

        // Get the singleton repository  same instance that ViewModels will use
        repository = NearMeRepository.getInstance(this)

        // Create the notification channel (required Android 8+, safe to call
        // multiple times  Android ignores duplicates)
        createNotificationChannel()

        // Build the persistent notification
        val notification = buildNotification()

        // Start as foreground  this is the line that tells Android
        // "don't kill this process." Must be called within 5 seconds
        // of startForegroundService() or the system throws an ANR.
        //
        // Android 10+ requires specifying the foreground service type.
        // On older versions we use the simpler overload.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Start BLE advertising and scanning via the repository
        repository.startBle()
        // Start NC advertising so this phone is reachable for incoming chats
        repository.startNc()
        repository.createMessageNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY tells Android: if the system kills this service
        // to reclaim memory, restart it automatically as soon as possible.
        // The intent will be null on restart, which is fine  we don't
        // pass any data through the intent.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop BLE when the service is destroyed
        repository.stopBle()

        // Stop NC when the service is destroyed
        repository.stopNc()
    }

    // Required by Service class but we don't support binding
    // everything communicates through the repository singleton instead
    override fun onBind(intent: Intent?): IBinder? = null

    //  Notification

    private fun createNotificationChannel() {
        // Notification channels only exist on Android 8+ (API 26)
        // Our minSDK is 26 so this always runs, but the version check
        // is good practice and satisfies the compiler
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NearMe Discovery",          // Name the user sees in system settings
                NotificationManager.IMPORTANCE_LOW  // Low = no sound, no vibration,
                // just sits quietly in the shade
            ).apply {
                description = "Keeps NearMe running for peer discovery"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // When the user taps the notification, open the app
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                // If the app is already open, bring it to front instead of
                // creating a new Activity on top
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            // FLAG_IMMUTABLE is required on Android 12+ for security
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NearMe")
            .setContentText("Keeping you discoverable")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // TODO: replace with a proper app icon later
            .setContentIntent(pendingIntent)
            .setOngoing(true)   // Prevents the user from swiping it away
            // they must stop the service to dismiss it
            .setSilent(true)     // No sound when it first appears
            .build()
    }
}