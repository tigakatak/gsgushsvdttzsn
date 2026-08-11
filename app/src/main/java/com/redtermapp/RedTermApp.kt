package com.redtermapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.redtermapp.session.TerminalSessionStore
import com.redtermapp.ui.NightModeReceiver
import com.redtermapp.ui.Prefs
import com.redtermapp.util.CrashHandler

class RedTermApp : Application() {

    internal val terminalSessions = TerminalSessionStore()

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        CrashHandler.init(this)
        com.redtermapp.util.Format.invalidateAll()
        val prefs = getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(Prefs.KEY_AUTO_NIGHT, false)) {
            NightModeReceiver.scheduleNightMode(this, prefs)
        }
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
        com.github.anrwatchdog.ANRWatchDog().start()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
