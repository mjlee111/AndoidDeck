package dev.menggu.androiddeck.quick

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.glance.appwidget.updateAll
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.data.Prefs
import dev.menggu.androiddeck.data.Shortcut
import dev.menggu.androiddeck.data.WakeOnLan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs pinned buttons from the widget and quick-settings tiles without the app's live connection. */
object QuickActions {
    const val MAX_SHORTCUTS = 8
    const val TILE_COUNT = 3

    suspend fun run(context: Context, shortcut: Shortcut): Result<String> {
        val servers = Prefs(context).loadServers()
        val action = shortcut.button.action
        val target = servers.find { it.id == (action.device ?: shortcut.serverId) }
            ?: return Result.failure(IllegalStateException("That PC is no longer paired"))
        return when (action.type) {
            "wake" -> WakeOnLan.send(target).map { "Wake signal sent to ${target.name}" }
            "folder" -> Result.failure(IllegalStateException("Folders open in the app"))
            else -> DeckClient.runOnce(target, action).map { shortcut.button.label.ifBlank { "Done" } }
        }
    }

    suspend fun runAndReport(context: Context, shortcut: Shortcut) {
        val result = run(context, shortcut)
        withContext(Dispatchers.Main) {
            result.onFailure {
                Toast.makeText(context, it.message ?: "Action failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Re-renders the widget and asks the system to refresh the tiles. */
    suspend fun refreshSurfaces(context: Context) {
        runCatching { DeckWidget().updateAll(context) }
        runCatching { ControlsWidget().updateAll(context) }
        for (cls in listOf(QuickTile1::class.java, QuickTile2::class.java, QuickTile3::class.java)) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, cls)) }
        }
    }
}
