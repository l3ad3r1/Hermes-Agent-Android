package com.hermes.agent.ui.tile

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.hermes.agent.MainActivity

class JeevesQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        // Set tile to active temporarily
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()

        // Launch MainActivity with an intent to start voice listening
        // For Android 14+, we need to use PendingIntent or startActivityAndCollapse
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.hermes.agent.action.START_VOICE_LISTEN"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)

        // Revert tile state
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
