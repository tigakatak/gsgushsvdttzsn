package com.redtermapp.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.redtermapp.R
import com.redtermapp.RedTermApp
import com.redtermapp.ui.AppTheme
import com.redtermapp.ui.Prefs
import com.redtermapp.ui.TerminalActivity
import com.redtermapp.ui.prefs
import java.io.File

class TerminalService : Service() {

    companion object {
        const val ACTION_ACQUIRE = "com.redtermapp.action.ACQUIRE_WAKELOCK"
        const val ACTION_RELEASE = "com.redtermapp.action.RELEASE_WAKELOCK"
        const val ACTION_AUTO_WAKE = "com.redtermapp.action.AUTO_WAKE"
        const val ACTION_AUTO_RELEASE = "com.redtermapp.action.AUTO_RELEASE"
        const val ACTION_EXIT = "com.redtermapp.action.EXIT"
        const val ACTION_STOP = "com.redtermapp.action.STOP"

        private fun terminalPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, TerminalActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var userWakelockHeld = false
    private var autoWakelockHeld = false

    override fun onCreate() {
        super.onCreate()
        val pendingIntent = terminalPendingIntent(this)
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
        acquireWifiLock()
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> {
                userWakelockHeld = true
                prefs().edit().putBoolean(Prefs.KEY_WAKELOCK, true).apply()
                updateWakeLock()
                updateNotification()
            }
            ACTION_RELEASE -> {
                userWakelockHeld = false
                prefs().edit().putBoolean(Prefs.KEY_WAKELOCK, false).apply()
                updateWakeLock()
                updateNotification()
            }
            ACTION_AUTO_WAKE -> {
                autoWakelockHeld = true
                updateWakeLock()
            }
            ACTION_AUTO_RELEASE -> {
                autoWakelockHeld = false
                updateWakeLock()
            }
            ACTION_EXIT -> {
                releaseWakeLock()
                releaseWifiLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_STOP -> {
                releaseWakeLock()
                releaseWifiLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val prefs = prefs()
                userWakelockHeld = prefs.getBoolean(Prefs.KEY_WAKELOCK, true)
                updateWakeLock()
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        releaseWifiLock()
        super.onDestroy()
    }

    private fun updateWakeLock() {
        val shouldHold = userWakelockHeld || autoWakelockHeld
        if (shouldHold && wakeLock?.isHeld != true) {
            acquireWakeLock()
        } else if (!shouldHold && wakeLock?.isHeld == true) {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RedTermApp:TerminalWakeLock"
            ).apply { acquire() }
        } catch (e: Exception) {
            Log.w("TerminalService", "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
        val pendingIntent = terminalPendingIntent(this)

        val isHeld = wakeLock?.isHeld == true
        val wakelockStatus = if (isHeld) "\u25CF" else "\u25CB"
        val autoStatus = if (autoWakelockHeld && !userWakelockHeld) " (auto)" else ""

        val builder = NotificationCompat.Builder(this, RedTermApp.CHANNEL_TERMINAL)
            .setContentTitle("RedTerm - ${getDistroName()}")
            .setContentText("$wakelockStatus Wake lock$autoStatus | Tap to open")
            .setSmallIcon(com.redtermapp.R.drawable.ic_notification)
            .setColor(themeAccent())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (userWakelockHeld) {
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
        val prefs = prefs()
        prefs.getString(Prefs.KEY_LAST_DISTRO, null)?.let { return it.capitalized() }
        val dir = File(filesDir, "installed")
        return dir.list()?.sorted()?.firstOrNull()?.capitalized() ?: "Terminal"
    }

    private fun themeAccent(): Int {
        val prefs = prefs()
        return AppTheme.resolveThemeColor(this, prefs, R.attr.themeAccent, 0xFF89B4FA.toInt())
    }
}
