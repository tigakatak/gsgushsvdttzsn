package alpiner.app.ui

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
        if (!isLocked) {
            TerminalActivity.launch(this)
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
