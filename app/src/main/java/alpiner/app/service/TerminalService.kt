package alpiner.app.service

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
import alpiner.app.R
import alpiner.app.AlpinerApp
import alpiner.app.proot.ProotInstaller
import alpiner.app.ui.Prefs
import alpiner.app.ui.TerminalActivity
import alpiner.app.ui.prefs
import alpiner.app.ui.themeAccentColor
import alpiner.app.session.sessionStore
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalService : Service() {

    companion object {
        const val ACTION_ACQUIRE = "alpiner.app.action.ACQUIRE_WAKELOCK"
        const val ACTION_RELEASE = "alpiner.app.action.RELEASE_WAKELOCK"
        const val ACTION_EXIT = "alpiner.app.action.EXIT"
        const val ACTION_CREATE_SESSION = "alpiner.app.action.CREATE_SESSION"

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
    private var sessionStarting = false
    private val launcher by lazy { SessionLauncher(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        exiting = false
        startForeground(AlpinerApp.NOTIF_ID_TERMINAL, buildNotification())
        launcher.deleteLegacyLaunchScripts()
    }

    private fun buildNotification(): android.app.Notification {
        val pendingIntent = terminalPendingIntent(this)
        val exitIntent = Intent(this, TerminalService::class.java).apply { action = ACTION_EXIT }
        val exitPI = PendingIntent.getService(
            this, 3, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sessionCount = sessionStore.sessions.size
        return NotificationCompat.Builder(this, AlpinerApp.CHANNEL_TERMINAL)
            .setContentTitle("$sessionCount session${if (sessionCount == 1) "" else "s"}")
            .setSmallIcon(R.drawable.ic_notification)
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
            startForeground(AlpinerApp.NOTIF_ID_TERMINAL, buildNotification())
        }
        when (intent?.action) {
            ACTION_ACQUIRE, ACTION_RELEASE -> {
                prefs().edit().putBoolean(Prefs.KEY_WAKELOCK, intent?.action == ACTION_ACQUIRE).apply()
                updateWakeLock()
                stopIfIdle()
            }
            ACTION_CREATE_SESSION -> {
                // The pref is the source of truth for the wake lock, so it
                // must be (re)applied on every start path, not only when the
                // user toggles it.
                updateWakeLock()
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
            Toast.makeText(this, "proot binary not found. Please reinstall Alpiner.", Toast.LENGTH_LONG).show()
            stopIfIdle()
            return
        }
        if (sessionStarting) {
            Toast.makeText(this, "A session is already starting", Toast.LENGTH_SHORT).show()
            return
        }
        sessionStarting = true
        serviceScope.launch {
            try {
                val spec = withContext(Dispatchers.IO) { launcher.prepare() }
                if (exiting) return@launch
                val bridge = SessionBridge(::handleSessionFinished)
                val session = TerminalSession(
                    spec.executable,
                    spec.workingDirectory,
                    spec.arguments,
                    emptyArray(),
                    spec.scrollbackRows,
                    bridge,
                )
                sessionStore.addSession(session, bridge)
                sessionStore.switchToSession(session)
                updateNotification()
            } catch (e: Exception) {
                Log.e("TerminalService", "Failed to launch Alpine", e)
                Toast.makeText(this@TerminalService, "Failed to launch Alpine: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                sessionStarting = false
                stopIfIdle()
            }
        }
    }

    private fun handleSessionFinished(session: TerminalSession) {
        serviceScope.launch {
            sessionStore.sessionFinished(session)
            if (sessionStore.sessions.isEmpty()) stopIfIdle() else updateNotification()
        }
    }

    private fun finishAllSessions() {
        sessionStore.finishAllSessions()
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
        // The user pref is the single source of truth: when it is off no
        // wake lock may be held; when it is on the CPU stays awake so
        // background sessions keep running.
        if (prefs().getBoolean(Prefs.KEY_WAKELOCK, Prefs.WAKELOCK_DEFAULT)) acquireWakeLock()
        else releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AlpinerApp:TerminalWakeLock"
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
