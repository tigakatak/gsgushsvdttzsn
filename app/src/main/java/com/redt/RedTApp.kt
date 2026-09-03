package com.redt

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.redt.session.TerminalSessionStore
import com.redt.util.CrashHandler

class RedTApp : Application() {

    internal val terminalSessions = TerminalSessionStore()

    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)

        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
        com.github.anrwatchdog.ANRWatchDog(15000).apply {
            setANRListener { error ->
                android.util.Log.e("RedTApp", "ANR detected (log only)", error)
            }
            setIgnoreDebugger(true)
            start()
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
