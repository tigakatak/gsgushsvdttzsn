package com.redtermapp.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.session.terminalSessionStore
import com.redtermapp.util.Format

class RedTermWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, RedTermWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val provider = RedTermWidgetProvider()
            for (widgetId in ids) {
                provider.updateWidget(context, manager, widgetId)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val installer = DistroInstaller(context)
        val distros = installer.getInstalledDistros()

        val prefs = context.prefs()
        val chosen = prefs.getString("widget_distro_$widgetId", null)
        val distro = when {
            distros.isEmpty() -> null
            chosen != null && distros.contains(chosen) -> chosen
            else -> distros.first()
        }

        if (distro == null) {
            views.setTextViewText(R.id.widget_distro_name, "No distros installed")
            views.setTextViewText(R.id.widget_distro_status, "Open RedTerm to install")
            views.setTextViewText(R.id.widget_session_count, "")
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        } else {
            views.setTextViewText(R.id.widget_distro_name, distro.capitalized())
            val rootfsDir = installer.getRootfsDir(distro)
            val cached = Format.cachedSize(rootfsDir)
            val sizeStr = if (cached != null) Format.size(cached) else ""
            val sessionCount = context.terminalSessionStore.sessions.value.size
            views.setTextViewText(R.id.widget_distro_status, "$sizeStr · $sessionCount session(s)")
            views.setTextViewText(
                R.id.widget_session_count,
                if (sessionCount > 0) "Tap to resume" else "Tap to launch"
            )
            val launchIntent = Intent(context, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_DISTRO, distro)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, widgetId, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            if (cached == null) {
                // dirSizeAsync fires its callback on the main thread; the widget
                // refresh itself does file I/O (directory listing, canonicalization,
                // cache lookup), so dispatch it to a background thread to avoid
                // blocking the main thread / triggering ANRs on first refresh.
                Format.dirSizeAsync(rootfsDir) { bytes ->
                    if (bytes >= 0) {
                        Format.runOnBackground {
                            updateWidget(context, appWidgetManager, widgetId)
                        }
                    }
                }
            }
        }
        appWidgetManager.updateAppWidget(widgetId, views)
    }
}
