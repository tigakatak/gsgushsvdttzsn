package com.redt.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.redt.R
import com.redt.distro.DistroInstaller
import com.redt.distro.DistroRegistry
import com.redt.session.terminalSessionStore
import com.redt.util.Format

class RedTWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, RedTWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val provider = RedTWidgetProvider()
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
        val rootfsDir = DistroInstaller(context).getRootfsDir(DistroRegistry.alpine.name)
        val sessionCount = context.terminalSessionStore.sessions.value.size

        if (!rootfsDir.exists()) {
            views.setTextViewText(R.id.widget_distro_name, "Alpine")
            views.setTextViewText(R.id.widget_distro_status, "Not installed")
            views.setTextViewText(R.id.widget_session_count, "Tap to install")
        } else {
            val cached = Format.cachedSize(rootfsDir)
            val sizeStr = if (cached != null) Format.size(cached) else ""
            views.setTextViewText(R.id.widget_distro_name, "Alpine")
            views.setTextViewText(R.id.widget_distro_status, "$sizeStr · $sessionCount session(s)")
            views.setTextViewText(
                R.id.widget_session_count,
                if (sessionCount > 0) "Tap to resume" else "Tap to launch"
            )
            if (cached == null) {
                // dirSizeAsync fires its callback on the main thread; the widget
                // refresh itself does file I/O (directory listing, cache
                // lookup), so dispatch it to a background thread to avoid
                // blocking the main thread / triggering ANRs on first refresh.
                // The cache is now warm, so the recursive refresh stops here.
                Format.dirSizeAsync(rootfsDir) {
                    Format.runOnBackground {
                        updateWidget(context, appWidgetManager, widgetId)
                    }
                }
            }
        }
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
            context, widgetId,
            TerminalActivity.launchIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
        appWidgetManager.updateAppWidget(widgetId, views)
    }
}
