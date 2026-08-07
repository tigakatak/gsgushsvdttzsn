package com.redtermapp.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.redtermapp.R
import com.redtermapp.RedTermApp
import com.redtermapp.ui.AppTheme
import com.redtermapp.ui.TerminalActivity
import java.io.File

class TerminalService : Service() {

    companion object {
        const val ACTION_ACQUIRE = "com.redtermapp.action.ACQUIRE_WAKELOCK"
        const val ACTION_RELEASE = "com.redtermapp.action.RELEASE_WAKELOCK"
        const val ACTION_EXIT = "com.redtermapp.action.EXIT"
        const val ACTION_STOP = "com.redtermapp.action.STOP"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, RedTermApp.CHANNEL_TERMINAL)
            .setContentTitle("RedTerm")
            .setContentText("Starting...")
            .setSmallIcon(com.redtermapp.R.drawable.ic_notification)
            .setColor(themeAccent())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(RedTermApp.NOTIF_ID_TERMINAL, notif)
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> {
                acquireWakeLock()
                updateNotification()
            }
            ACTION_RELEASE -> {
                releaseWakeLock()
                updateNotification()
            }
            ACTION_EXIT -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Process.killProcess(Process.myPid())
            }
            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val wakelockEnabled = prefs.getBoolean("wakelock", false)
                if (wakelockEnabled) acquireWakeLock() else releaseWakeLock()
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RedTermApp:TerminalWakeLock"
        ).apply { acquire() }
        acquireWifiLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        releaseWifiLock()
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "RedTermApp:TerminalWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("TerminalService", "Failed to acquire wifi lock", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w("TerminalService", "Failed to release wifi lock", e)
        }
        wifiLock = null
    }

    private fun updateNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isHeld = wakeLock?.isHeld == true
        val wakelockStatus = if (isHeld) "\u25CF" else "\u25CB"

        val builder = NotificationCompat.Builder(this, RedTermApp.CHANNEL_TERMINAL)
            .setContentTitle("RedTerm - ${getDistroName()}")
            .setContentText("$wakelockStatus Wake lock | Tap to open")
            .setSmallIcon(com.redtermapp.R.drawable.ic_notification)
            .setColor(themeAccent())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (isHeld) {
            val releaseIntent = Intent(this, TerminalService::class.java).apply { action = ACTION_RELEASE }
            val releasePI = PendingIntent.getService(
                this, 1, releaseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(NotificationCompat.Action.Builder(null, "Release", releasePI).build())
        } else {
            val acquireIntent = Intent(this, TerminalService::class.java).apply { action = ACTION_ACQUIRE }
            val acquirePI = PendingIntent.getService(
                this, 2, acquireIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(NotificationCompat.Action.Builder(null, "Acquire", acquirePI).build())
        }

        val exitIntent = Intent(this, TerminalService::class.java).apply { action = ACTION_EXIT }
        val exitPI = PendingIntent.getService(
            this, 3, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(NotificationCompat.Action.Builder(null, "Exit", exitPI).build())

        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(RedTermApp.NOTIF_ID_TERMINAL, builder.build())
    }

    private fun getDistroName(): String {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.getString("last_distro", null)?.let { return it.replaceFirstChar { c -> c.uppercase() } }
        val dir = File(filesDir, "installed")
        return dir.list()?.sorted()?.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Terminal"
    }

    private fun themeAccent(): Int {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        return AppTheme.resolveThemeColor(this, prefs, R.attr.themeAccent, 0xFF89B4FA.toInt())
    }
}
