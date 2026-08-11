package com.redtermapp.service

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
import com.redtermapp.R
import com.redtermapp.RedTermApp
import com.redtermapp.ui.AppTheme
import com.redtermapp.ui.Prefs
import com.redtermapp.ui.TerminalActivity
import com.redtermapp.ui.RedTermWidgetProvider
import com.redtermapp.ui.capitalized
import com.redtermapp.ui.prefs
import com.redtermapp.session.terminalSessionStore
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
        const val ACTION_ACQUIRE = "com.redtermapp.action.ACQUIRE_WAKELOCK"
        const val ACTION_RELEASE = "com.redtermapp.action.RELEASE_WAKELOCK"
        const val ACTION_AUTO_WAKE = "com.redtermapp.action.AUTO_WAKE"
        const val ACTION_AUTO_RELEASE = "com.redtermapp.action.AUTO_RELEASE"
        const val ACTION_EXIT = "com.redtermapp.action.EXIT"
        const val ACTION_CREATE_SESSION = "com.redtermapp.action.CREATE_SESSION"
        const val EXTRA_DISTRO = "distro"

        private fun terminalPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, TerminalActivity::class.java).apply {
                    context.prefs().getString(Prefs.KEY_LAST_DISTRO, null)?.let {
                        putExtra(TerminalActivity.EXTRA_DISTRO, it)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var userWakelockHeld = false
    private var autoWakelockHeld = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val launchScripts = IdentityHashMap<TerminalSession, File>()
    private val pendingDistros = mutableSetOf<String>()
    private val sessionStore by lazy { terminalSessionStore }
    private val launcher by lazy { SessionLauncher(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        startForeground(RedTermApp.NOTIF_ID_TERMINAL, buildNotification())
        if (sessionStore.sessions.value.isEmpty()) sweepStaleLaunchScripts()
    }

    private fun buildNotification(): android.app.Notification {
        val pendingIntent = terminalPendingIntent(this)
        val exitIntent = Intent(this, TerminalService::class.java).apply { action = ACTION_EXIT }
        val exitPI = PendingIntent.getService(
            this, 3, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RedTermApp.CHANNEL_TERMINAL)
            .setContentTitle("RedTerm - ${getDistroName()}")
            .setContentText("${sessionStore.sessions.value.size} session(s) | Tap to open")
            .setSmallIcon(com.redtermapp.R.drawable.ic_notification)
            .setColor(themeAccent())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(NotificationCompat.Action.Builder(null, "Exit", exitPI).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> {
                userWakelockHeld = true
                prefs().edit().putBoolean(Prefs.KEY_WAKELOCK, true).apply()
                updateWakeLock()
            }
            ACTION_RELEASE -> {
                userWakelockHeld = false
                prefs().edit().putBoolean(Prefs.KEY_WAKELOCK, false).apply()
                updateWakeLock()
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
                intent.getStringExtra(EXTRA_DISTRO)?.let(::createSession)
            }
            ACTION_EXIT -> {
                finishAllSessions()
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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createSession(distroName: String) {
        if (!pendingDistros.add(distroName)) return
        serviceScope.launch {
            var launchScript: File? = null
            try {
                val spec = withContext(Dispatchers.IO) { launcher.prepare(distroName) }
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
                    mSessionName = distroName
                }
                synchronized(launchScripts) {
                    launchScripts[session] = spec.launchScript
                }
                sessionStore.addSession(session, distroName, bridge)
                sessionStore.switchToSession(session)
                updateNotification()
                RedTermWidgetProvider.updateAll(this@TerminalService)
            } catch (e: Exception) {
                launchScript?.delete()
                Log.e("TerminalService", "Failed to launch $distroName", e)
                Toast.makeText(
                    this@TerminalService,
                    "Failed to launch $distroName: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                pendingDistros.remove(distroName)
                if (sessionStore.sessions.value.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun handleSessionFinished(session: TerminalSession) {
        synchronized(launchScripts) {
            launchScripts.remove(session)
        }?.delete()
        serviceScope.launch {
            sessionStore.sessionFinished(session)
            RedTermWidgetProvider.updateAll(this@TerminalService)
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
            ?.notify(RedTermApp.NOTIF_ID_TERMINAL, buildNotification())
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
