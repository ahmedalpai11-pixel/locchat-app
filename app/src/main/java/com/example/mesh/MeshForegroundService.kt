package com.example.mesh

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
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

class MeshForegroundService : Service() {

    private var btReceiverRegistered = false

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val repo = MeshRepository.getInstance(applicationContext)
            when (state) {
                BluetoothAdapter.STATE_ON -> repo.startMeshEngine()
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> repo.stopMeshEngine()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (!btReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            btReceiverRegistered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } catch (_: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        MeshRepository.getInstance(applicationContext).startMeshEngine()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (btReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothStateReceiver)
            } catch (_: Exception) {
            }
            btReceiverRegistered = false
        }
        MeshRepository.getInstance(applicationContext).stopMeshEngine()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mesh_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.mesh_notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.mesh_service_title))
            .setContentText(getString(R.string.mesh_service_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "locchat_mesh_channel"
        private const val NOTIFICATION_ID = 4102

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
    }
}
