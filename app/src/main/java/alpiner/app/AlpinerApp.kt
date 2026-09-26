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

    internal val terminalSessions = SessionStore()

    /** Survives activity recreation: rootfs installs must not restart on config changes. */
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One installer instance so cancellation and its generation counter are shared. */
    internal val alpineInstaller by lazy { AlpineInstaller(this) }

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
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_TERMINAL,
            getString(R.string.notification_channel_terminal),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification for running terminal sessions"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_TERMINAL = "terminal"
        const val NOTIF_ID_TERMINAL = 1
    }
}
