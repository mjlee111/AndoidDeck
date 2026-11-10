package dev.menggu.androiddeck.quick

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.menggu.androiddeck.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick-settings tile bound to widget shortcut [slot] (0-based). */
abstract class QuickTileBase(private val slot: Int) : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        render(busy = false)
    }

    private fun render(busy: Boolean) {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        val shortcut = prefs.loadShortcuts().getOrNull(slot)
        if (shortcut == null) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "Deck ${slot + 1}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "Not set"
        } else {
            tile.state = if (busy) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = shortcut.button.label.ifBlank { "Deck ${slot + 1}" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = prefs.loadServers().find { it.id == (shortcut.button.action.device ?: shortcut.serverId) }?.name
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val shortcut = Prefs(this).loadShortcuts().getOrNull(slot) ?: return
        render(busy = true)
        scope.launch {
            QuickActions.runAndReport(applicationContext, shortcut)
            render(busy = false)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class QuickTile1 : QuickTileBase(0)
class QuickTile2 : QuickTileBase(1)
class QuickTile3 : QuickTileBase(2)
