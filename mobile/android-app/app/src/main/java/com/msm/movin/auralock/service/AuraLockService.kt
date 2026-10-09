/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.msm.movin.auralock.MainActivity

/**
 * Foreground Service that keeps AuraLock active in the background when the phone screen turns off or locks.
 * Holds PARTIAL_WAKE_LOCK and WifiLock during active PC connection / file transfers,
 * and automatically releases all resources and stops when disconnected.
 */
class AuraLockService : Service() {

    companion object {
        private const val TAG = "AuraLockService"
        const val CHANNEL_ID = "auralock_service_channel"
        const val NOTIFICATION_ID = 47891

        const val ACTION_START = "com.msm.movin.auralock.action.START_SERVICE"
        const val ACTION_STOP = "com.msm.movin.auralock.action.STOP_SERVICE"
        const val ACTION_UPDATE_TRANSFER = "com.msm.movin.auralock.action.UPDATE_TRANSFER"
        const val ACTION_UPDATE_STATUS = "com.msm.movin.auralock.action.UPDATE_STATUS"

        const val EXTRA_PC_NAME = "extra_pc_name"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_IS_INCOMING = "extra_is_incoming"
        const val EXTRA_STATUS = "extra_status"

        fun isEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences("auralock_paired_prefs", Context.MODE_PRIVATE)
            return prefs.getBoolean("background_service_enabled", true)
        }

        fun start(context: Context, pcName: String) {
            if (!isEnabled(context)) return
            val intent = Intent(context, AuraLockService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PC_NAME, pcName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start foreground service: ${e.message}")
            }
        }

        fun updateTransfer(context: Context, fileName: String, progress: Float, isIncoming: Boolean) {
            if (!isEnabled(context)) return
            val intent = Intent(context, AuraLockService::class.java).apply {
                action = ACTION_UPDATE_TRANSFER
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update transfer notification: ${e.message}")
            }
        }

        fun updateStatus(context: Context, status: String) {
            if (!isEnabled(context)) return
            val intent = Intent(context, AuraLockService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS, status)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update status notification: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AuraLockService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                try {
                    context.stopService(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var currentPcName: String = "PC"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                releaseLocks()
                try {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_TRANSFER -> {
                val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: "File"
                val progress = intent?.getFloatExtra(EXTRA_PROGRESS, 0f) ?: 0f
                val isIncoming = intent?.getBooleanExtra(EXTRA_IS_INCOMING, true) ?: true
                acquireLocks()
                val title = if (isIncoming) "AuraDrop • Receiving File" else "AuraDrop • Sending File"
                val text = "$fileName (${(progress * 100).toInt()}%)"
                showForegroundNotification(title, text, (progress * 100).toInt())
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Secure Tunnel Active"
                acquireLocks()
                showForegroundNotification("AuraLock • Linked & Armed", status, null)
            }
            ACTION_START -> {
                val pcName = intent?.getStringExtra(EXTRA_PC_NAME)
                if (!pcName.isNullOrBlank()) {
                    currentPcName = pcName
                }
                acquireLocks()
                showForegroundNotification(
                    "AuraLock • Linked & Armed",
                    "Connected to $currentPcName • Background Security Active",
                    null
                )
            }
        }

        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuraLock::ForegroundWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 24h safety limit
                }
            } else if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "AuraLock::ForegroundWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wifi lock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock: ${e.message}")
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wifi lock: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AuraLock Background Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains secure link with PC and background file transfers when screen is off"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showForegroundNotification(title: String, text: String, progress: Int?) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (progress != null) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, false)
        }

        val notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }
}
