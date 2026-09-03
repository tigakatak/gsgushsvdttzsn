package com.redt.ui

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.redt.session.terminalSessionStore

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
        val sessions = terminalSessionStore.sessions
        tile.state = if (sessions.isNotEmpty()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (sessions.isNotEmpty()) "RedT (${sessions.size})" else "RedT"
        tile.updateTile()
    }
}
