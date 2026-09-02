package com.redt.service

import android.app.PendingIntent
import android.app.Service
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.redt.R
import com.redt.distro.DistroRegistry
import com.redt.RedTApp
import com.redt.proot.ProotInstaller
import com.redt.ui.themeColor
import com.redt.ui.Prefs
import com.redt.ui.TerminalActivity
import com.redt.ui.RedTWidgetProvider
import com.redt.ui.prefs
import com.redt.session.terminalSessionStore
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap

class TerminalService : Service() {

    companion object {
        const val ACTION_ACQUIRE = "com.redt.action.ACQUIRE_WAKELOCK"
        const val ACTION_RELEASE = "com.redt.action.RELEASE_WAKELOCK"
        const val ACTION_AUTO_WAKE = "com.redt.action.AUTO_WAKE"
        const val ACTION_AUTO_RELEASE = "com.redt.action.AUTO_RELEASE"
        const val ACTION_EXIT = "com.redt.action.EXIT"
        const val ACTION_CREATE_SESSION = "com.redt.action.CREATE_SESSION"

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
    private var userWakelockHeld = false
    private var autoWakelockHeld = false
    @Volatile
    private var exiting = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val launchScripts = IdentityHashMap<TerminalSession, File>()
    private var sessionStarting = false
    private val sessionStore by lazy { terminalSessionStore }
    private val launcher by lazy { SessionLauncher(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        exiting = false
        startForeground(RedTApp.NOTIF_ID_TERMINAL, buildNotification())
        if (sessionStore.sessions.value.isEmpty()) sweepStaleLaunchScripts()
    }

    private fun buildNotification(): android.app.Notification {
        val pendingIntent = terminalPendingIntent(this)
        val exitIntent = Intent(this, TerminalService::class.java).apply { action = ACTION_EXIT }
        val exitPI = PendingIntent.getService(
            this, 3, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RedTApp.CHANNEL_TERMINAL)
            .setContentTitle("RedT - Alpine")
            .setContentText("${sessionStore.sessions.value.size} session(s) | Tap to open")
            .setSmallIcon(com.redt.R.drawable.ic_notification)
            .setColor(themeAccent())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(NotificationCompat.Action.Builder(null, "Exit", exitPI).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_EXIT) {
            startForeground(RedTApp.NOTIF_ID_TERMINAL, buildNotification())
        }
        when (intent?.action) {
            ACTION_ACQUIRE -> {
                userWakelockHeld = true
                prefs().edit().putBoolean(Prefs.KEY_WAKELOCK, true).apply()
                updateWakeLock()
                stopIfIdle()
            }
            ACTION_RELEASE -> {
                userWakelockHeld = false
                prefs().edit().putBoolean(Prefs.KEY_WAKELOCK, false).apply()
                updateWakeLock()
                stopIfIdle()
            }
            ACTION_AUTO_WAKE -> {
                autoWakelockHeld = true
                updateWakeLock()
            }
            ACTION_AUTO_RELEASE -> {
                autoWakelockHeld = false
                updateWakeLock()
            }
            ACTION_CREATE_SESSION -> {
                createSession()
            }
            ACTION_EXIT -> {
                exiting = true
                sessionStore.signalExit()
                finishAllSessions()
                // The widget shows a live session count; refresh it before
                // stopping, otherwise it keeps a stale "Tap to resume".
                RedTWidgetProvider.updateAll(this)
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val prefs = prefs()
                userWakelockHeld = prefs.getBoolean(Prefs.KEY_WAKELOCK, true)
                updateWakeLock()
                if (sessionStore.sessions.value.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createSession() {
        if (exiting) return
        if (!ProotInstaller.isInstalled(applicationContext)) {
            Toast.makeText(
                this,
                "proot binary not found. Please reinstall RedT.",
                Toast.LENGTH_LONG,
            ).show()
            stopIfIdle()
            return
        }
        if (sessionStarting) {
            Toast.makeText(
                this,
                "A session is already starting",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        sessionStarting = true
        serviceScope.launch {
            var launchScript: File? = null
            try {
                val spec = withContext(Dispatchers.IO) { launcher.prepare() }
                if (exiting) {
                    spec.launchScript.delete()
                    return@launch
                }
                launchScript = spec.launchScript
                val bridge = TerminalSessionClientBridge(::handleSessionFinished)
                val session = TerminalSession(
                    spec.executable,
                    spec.workingDirectory,
                    spec.arguments,
                    emptyArray(),
                    spec.scrollbackRows,
                    bridge,
                ).apply {
                    mSessionName = DistroRegistry.alpine.name
                }
                synchronized(launchScripts) {
                    launchScripts[session] = spec.launchScript
                }
                sessionStore.addSession(session, bridge)
                sessionStore.switchToSession(session)
                updateNotification()
                RedTWidgetProvider.updateAll(this@TerminalService)
            } catch (e: Exception) {
                launchScript?.delete()
                Log.e("TerminalService", "Failed to launch Alpine", e)
                Toast.makeText(
                    this@TerminalService,
                    "Failed to launch Alpine: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                sessionStarting = false
                stopIfIdle()
            }
        }
    }

    private fun handleSessionFinished(session: TerminalSession) {
        synchronized(launchScripts) {
            launchScripts.remove(session)
        }?.delete()
        serviceScope.launch {
            sessionStore.sessionFinished(session)
            RedTWidgetProvider.updateAll(this@TerminalService)
            if (sessionStore.sessions.value.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                updateNotification()
            }
        }
    }

    private fun finishAllSessions() {
        sessionStore.finishAllSessions()
        synchronized(launchScripts) {
            launchScripts.values.forEach { it.delete() }
            launchScripts.clear()
        }
        filesDir.listFiles { _, name -> name.startsWith("launch_") && name.endsWith(".sh") }
            ?.forEach { it.delete() }
    }

    private fun sweepStaleLaunchScripts() {
        filesDir.listFiles { _, name -> name.startsWith("launch_") && name.endsWith(".sh") }
            ?.forEach { it.delete() }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(RedTApp.NOTIF_ID_TERMINAL, buildNotification())
    }

    private fun stopIfIdle() {
        if (sessionStore.sessions.value.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateWakeLock() {
        // The user pref is the master switch: with wakelock disabled, neither
        // the manual toggle nor the background auto-wakelock may hold one.
        val enabled = prefs().getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)
        val shouldHold = enabled && (userWakelockHeld || autoWakelockHeld)
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
                "RedTApp:TerminalWakeLock"
            ).apply { acquire() }
        } catch (e: Exception) {
            Log.w("TerminalService", "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun themeAccent(): Int = themeColor(R.attr.themeAccent, 0xFF89B4FA.toInt())
}
