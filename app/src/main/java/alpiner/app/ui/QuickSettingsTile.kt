package alpiner.app.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import alpiner.app.session.sessionStore

class QuickSettingsTile : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onTileAdded() {
        updateTile()
    }

    override fun onClick() {
        if (isLocked) return
        val intent = TerminalActivity.launchIntent(this)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // API 34+ requires the PendingIntent form so the shade collapses.
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val sessions = sessionStore.sessions
        val active = sessions.isNotEmpty()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "Alpiner (${sessions.size})" else "Alpiner"
        tile.updateTile()
    }
}
