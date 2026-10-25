package app.echomind.surfaces

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.echomind.App
import kotlinx.coroutines.launch

/** A Quick Settings tile: travel mode on or off from the notification shade. */
class TravelTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val g = App.graph
        app.echomind.telemetry.Usage.count("tile_tap", mapOf("tile" to "travel", "was_on" to g.travel.active))
        g.scope.launch {
            if (g.travel.active) g.travel.disable() else g.travel.enable("manual")
            refresh()
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = App.graph.travel.active
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Travel mode"
        tile.subtitle = if (on) "EchoMind resting" else "Off"
        tile.updateTile()
    }
}
