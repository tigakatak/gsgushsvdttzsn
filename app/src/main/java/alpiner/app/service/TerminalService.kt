package alpiner.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import alpiner.app.AlpinerApp
import alpiner.app.R
import alpiner.app.prefs
import alpiner.app.proot.ProotInstaller
import alpiner.app.session.sessionStore
import alpiner.app.ui.Palette
import alpiner.app.ui.TerminalActivity
import alpiner.app.util.toast
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalService : Service() {

    companion object {
        const val ACTION_EXIT = "alpiner.app.action.EXIT"
        const val ACTION_CREATE_SESSION = "alpiner.app.action.CREATE_SESSION"
        /** Like [ACTION_CREATE_SESSION], but silently does nothing when a session already exists or is starting. */
        const val ACTION_AUTO_CREATE_SESSION = "alpiner.app.action.AUTO_CREATE_SESSION"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var exiting = false
    private var sessionStarting = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val launcher by lazy { SessionLauncher(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        startForeground(AlpinerApp.NOTIF_ID_TERMINAL, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_EXIT) {
            // EXIT must never (re)enter the foreground state; every other
            // entry point has to hold the notification while it runs.
            startForeground(AlpinerApp.NOTIF_ID_TERMINAL, buildNotification())
        }
        when (intent?.action) {
            ACTION_CREATE_SESSION -> createSession()
            ACTION_AUTO_CREATE_SESSION ->
                if (sessionStore.sessions.isEmpty() && !sessionStarting) createSession() else stopIfIdle()
            ACTION_EXIT -> {
                exiting = true
                sessionStore.signalExit()
                sessionStore.finishAll()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Started (also after a settings change): (re)apply the wake
                // lock preference, then stop if there is nothing to do.
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

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            TerminalActivity.launchIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalService::class.java).apply { action = ACTION_EXIT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sessionCount = sessionStore.sessions.size
        return NotificationCompat.Builder(this, AlpinerApp.CHANNEL_TERMINAL)
            .setContentTitle("$sessionCount session${if (sessionCount == 1) "" else "s"}")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Palette(this).accent)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(NotificationCompat.Action.Builder(null, "Exit", exitIntent).build())
            .build()
    }

    private fun createSession() {
        if (exiting) return
        if (!ProotInstaller.isInstalled(applicationContext)) {
            toast("proot binary not found. Please reinstall Alpiner.")
            stopIfIdle()
            return
        }
        if (sessionStarting) {
            toast("A session is already starting", android.widget.Toast.LENGTH_SHORT)
            return
        }
        sessionStarting = true
        updateWakeLock()
        serviceScope.launch {
            try {
                val spec = withContext(Dispatchers.IO) { launcher.prepare() }
                if (exiting) return@launch
                val session = TerminalSession(
                    spec.executable,
                    spec.workingDirectory,
                    spec.arguments,
                    emptyArray(),
                    spec.scrollbackRows,
                    SessionClient(applicationContext, ::handleSessionFinished),
                )
                sessionStore.add(session)
                updateNotification()
            } catch (e: Exception) {
                Log.e("TerminalService", "Failed to launch Alpine", e)
                toast("Failed to launch Alpine: ${e.message}")
            } finally {
                sessionStarting = false
                stopIfIdle()
            }
        }
    }

    private fun handleSessionFinished(session: TerminalSession) {
        serviceScope.launch {
            sessionStore.remove(session)
            if (sessionStore.sessions.isEmpty()) stopIfIdle() else updateNotification()
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(AlpinerApp.NOTIF_ID_TERMINAL, buildNotification())
    }

    /** Never stops the service while a session creation is still in flight. */
    private fun stopIfIdle() {
        if (!sessionStarting && sessionStore.sessions.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateWakeLock() {
        // The user pref is the single source of truth: when it is off no wake
        // lock may be held; when it is on the CPU stays awake so background
        // sessions keep running.
        if (prefs.wakelock) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlpinerApp:TerminalWakeLock")
                .apply { acquire() }
        } catch (e: Exception) {
            Log.w("TerminalService", "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
