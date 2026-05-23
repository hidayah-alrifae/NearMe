package com.example.nearme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.nearme.MainActivity
import com.example.nearme.R
import com.example.nearme.repository.NearMeRepository

class NearMeService : Service() {

    private lateinit var repository: NearMeRepository

    // Listens for Bluetooth being turned off and back on.
    // When Bluetooth restarts, the BLE adapter resets — our advertisers
    // and scanners hold stale state and stop working silently.
    // We fix this by stopping everything and starting fresh when BT comes back.
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

            val state = intent.getIntExtra(
                BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR
            )

            when (state) {
                BluetoothAdapter.STATE_OFF -> {
                    // Bluetooth is turning off — stop everything cleanly
                    // so we don't hold dead references
                    android.util.Log.d("SERVICE", "Bluetooth off — stopping BLE and NC")
                    repository.stopBle()
                    repository.stopNc()
                }

                BluetoothAdapter.STATE_ON -> {
                    // Bluetooth is back on — restart BLE and NC fresh
                    // Small delay gives the adapter time to fully initialize
                    android.util.Log.d("SERVICE", "Bluetooth on — restarting BLE and NC")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        repository.startBle()
                        repository.startNc()
                    }, 1000) // 1 second delay
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "nearme_discovery"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, NearMeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NearMeService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()

        repository = NearMeRepository.getInstance(this)

        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Register the Bluetooth state receiver
        // This must be done BEFORE startBle() so we catch any state changes
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)

        repository.startBle()
        repository.startNc()
        repository.startWifiAware()
        repository.createMessageNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister receiver before stopping — avoids leaks
        unregisterReceiver(bluetoothReceiver)
        repository.stopWifiAware()
        repository.stopBle()
        repository.stopNc()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NearMe Discovery",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps NearMe running for peer discovery"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NearMe")
            .setContentText("Keeping you discoverable")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}