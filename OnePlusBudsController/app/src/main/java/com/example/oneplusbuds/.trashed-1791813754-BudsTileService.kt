package com.example.oneplusbuds

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class BudsTileService : TileService() {

    private var connectionManager: BudsConnectionManager? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val manager = getOrCreateManager()
        manager.cycleAnc()
        updateTile()
    }

    override fun onLongClick() {
        super.onLongClick()
        val manager = getOrCreateManager()
        manager.setGameMode(!manager.gameModeOn)
        updateTile()
    }

    private fun getOrCreateManager(): BudsConnectionManager {
        if (connectionManager == null) {
            connectionManager = BudsConnectionManager(this)
            connectionManager?.setListener(object : BudsConnectionManager.Listener {
                override fun onLog(message: String) { }
                override fun onStateChanged(state: BudsConnectionManager.State) { updateTile() }
                override fun onAncModeChanged(mode: OpoProtocol.AncMode) { updateTile() }
                override fun onGameModeChanged(on: Boolean) { updateTile() }
            })
        }
        return connectionManager!!
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val manager = connectionManager
        val ancLabel = manager?.currentAncMode?.label ?: "Off"
        val gameLabel = if (manager?.gameModeOn == true) "Game ON" else ""

        tile.label = "Buds ANC"
        tile.subtitle = listOfNotNull(ancLabel, gameLabel.ifEmpty { null }).joinToString(" | ")

        tile.state = if (manager?.currentAncMode != OpoProtocol.AncMode.OFF &&
            manager?.currentAncMode != null
        ) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager?.disconnect()
    }
}
