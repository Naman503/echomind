package app.echomind.surfaces

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.echomind.App
import app.echomind.capture.CaptureController

/** Quick Settings tile: one tap to start or pause listening. */
class CaptureTile : TileService() {
    override fun onStartListening() {
        val mode = App.graph.capture.state.value.mode
        qsTile?.apply {
            state = if (mode == CaptureController.Mode.LISTENING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = when (mode) {
                CaptureController.Mode.LISTENING -> "Listening"
                CaptureController.Mode.PAUSED -> "Paused"
                CaptureController.Mode.BLOCKED_CALL -> "On a call"
                CaptureController.Mode.PROCESSING -> "Catching up"
                CaptureController.Mode.QUIET -> "Quiet hours"
                else -> "Off"
            }
            updateTile()
        }
    }

    override fun onClick() {
        val g = App.graph
        if (g.capture.state.value.mode == CaptureController.Mode.LISTENING) {
            g.capture.pause(0)
            onStartListening()
            return
        }
        // Starting the microphone needs a visible activity; a translucent one does it and closes.
        val pi = PendingIntent.getActivity(this, 0, Intent(this, StartListeningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        startActivityAndCollapse(pi)
    }
}

/** Invisible: starts listening from a user-visible context, then finishes. Used by the tile and shortcuts. */
class StartListeningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.graph.capture.start()
        finish()
    }
}
