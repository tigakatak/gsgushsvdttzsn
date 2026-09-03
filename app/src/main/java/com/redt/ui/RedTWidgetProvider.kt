package com.redt.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.redt.R
import com.redt.distro.DistroInstaller
import com.redt.distro.DistroRegistry
import com.redt.session.terminalSessionStore
import com.redt.util.Format

class RedTWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, RedTWidgetProvider::class.java)
            )
            for (widgetId in ids) {
                updateWidget(context, manager, widgetId)
            }
        }

        /**
         * Refreshes one widget instance. Stateless, so both [updateAll] and
         * the instance [onUpdate] path share it without a receiver.
         */
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val rootfsDir = DistroInstaller(context).getRootfsDir(DistroRegistry.alpine.name)
            val sessionCount = context.terminalSessionStore.sessions.size
            val installed = rootfsDir.exists()
            val cached = if (installed) Format.cachedSize(rootfsDir) else null

            views.setTextViewText(R.id.widget_distro_name, "Alpine")
            if (!installed) {
                views.setTextViewText(R.id.widget_distro_status, "Not installed")
                views.setTextViewText(R.id.widget_session_count, "Tap to install")
            } else {
                views.setTextViewText(R.id.widget_distro_status, statusLine(cached, sessionCount))
                views.setTextViewText(
                    R.id.widget_session_count,
                    if (sessionCount > 0) "Tap to resume" else "Tap to launch"
                )
            }
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, widgetId,
                TerminalActivity.launchIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            appWidgetManager.updateAppWidget(widgetId, views)

            if (installed && cached == null) {
                // Cold size cache: compute the size off the main thread and
                // patch only the status line once it is known. dirSizeAsync
                // delivers its callback on the main thread, and the cache is
                // warm afterwards, so this fires at most once per TTL.
                Format.dirSizeAsync(rootfsDir) { bytes ->
                    views.setTextViewText(
                        R.id.widget_distro_status,
                        statusLine(bytes, sessionCount)
                    )
                    appWidgetManager.updateAppWidget(widgetId, views)
                }
            }
        }

        private fun statusLine(sizeBytes: Long?, sessionCount: Int): String =
            if (sizeBytes != null) "${Format.size(sizeBytes)} · $sessionCount session(s)"
            else "$sessionCount session(s)"
    }
}
