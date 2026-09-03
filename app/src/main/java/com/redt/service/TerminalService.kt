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
import com.redt.ui.Prefs
import com.redt.ui.TerminalActivity
import com.redt.ui.prefs
import com.redt.ui.themeAccentColor
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
        const val ACTION_EXIT = "com.redt.action.EXIT"
        const val ACTION_CREATE_SESSION = "com.redt.action.CREATE_SESSION"

        private fun terminalPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                TerminalActivity.launchIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    private var wakeLock: PowerManager.WakeLock? = null
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
        if (sessionStore.sessions.isEmpty()) sweepStaleLaunchScripts()
    }

    private fun buildNotification(): android.app.Notification {
        val pendingIntent = terminalPendingIntent(this)
        val exitIntent = Intent(this, TerminalService::class.java).apply { action = ACTION_EXIT }
        val exitPI = PendingIntent.getService(
            this, 3, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RedTApp.CHANNEL_TERMINAL)
            .setContentTitle("RedT - ${DistroRegistry.alpine.name.replaceFirstChar { it.uppercase() }}")
            .setContentText("${sessionStore.sessions.size} session(s) | Tap to open")
            .setSmallIcon(com.redt.R.drawable.ic_notification)
            .setColor(themeAccentColor())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(NotificationCompat.Action.Builder(null, "Exit", exitPI).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_EXIT) {
            // EXIT must never (re)enter the foreground state; every other
            // entry point has to hold the notification while it runs.
            startForeground(RedTApp.NOTIF_ID_TERMINAL, buildNotification())
        }
        when (intent?.action) {
            ACTION_ACQUIRE, ACTION_RELEASE -> {
                prefs().edit().putBoolean(Prefs.KEY_WAKELOCK, intent?.action == ACTION_ACQUIRE).apply()
                updateWakeLock()
                stopIfIdle()
            }
            ACTION_CREATE_SESSION -> {
                createSession()
            }
            ACTION_EXIT -> {
                exiting = true
                sessionStore.signalExit()
                finishAllSessions()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                updateWakeLock()
                stopIfIdle()
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
            if (sessionStore.sessions.isEmpty()) {
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
        launchScriptFiles()?.forEach { it.delete() }
    }

    private fun launchScriptFiles(): Array<File>? =
        filesDir.listFiles { _, name -> name.startsWith("launch_") && name.endsWith(".sh") }

    private fun sweepStaleLaunchScripts() {
        launchScriptFiles()?.forEach { it.delete() }
    }


    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(RedTApp.NOTIF_ID_TERMINAL, buildNotification())
    }

    private fun stopIfIdle() {
        if (sessionStore.sessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateWakeLock() {
        // The user pref is the single source of truth: when it is off no
        // wake lock may be held; when it is on the service keeps the CPU
        // alive so background sessions keep running.
        val shouldHold = prefs().getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)
        if (shouldHold) {
            acquireWakeLock()
        } else {
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


}
