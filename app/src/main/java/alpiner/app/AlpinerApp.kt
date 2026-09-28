package alpiner.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import alpiner.app.distro.AlpineInstaller
import alpiner.app.session.SessionStore
import alpiner.app.util.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AlpinerApp : Application() {

    internal val sessionStore = SessionStore()

    /** Outlives activities: a rootfs install must not restart on config changes. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Single owner of the install job and its state. */
    internal val alpineInstaller by lazy { AlpineInstaller(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)

        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            com.github.anrwatchdog.ANRWatchDog(15000).apply {
                setANRListener { error -> android.util.Log.e("AlpinerApp", "ANR detected (log only)", error) }
                setIgnoreDebugger(true)
                start()
            }
        }

        val channel = NotificationChannel(
            CHANNEL_TERMINAL,
            getString(R.string.notification_channel_terminal),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Notification for running terminal sessions" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // Reclaim disk space left by interrupted installs.
        alpineInstaller.sweepOrphanFiles()
    }

    companion object {
        const val CHANNEL_TERMINAL = "terminal"
        const val NOTIF_ID_TERMINAL = 1
    }
}
