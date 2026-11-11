package dev.menggu.androiddeck.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class Settings(
    /** Size the deck so the whole page fits on screen (centered); [tileSize] is used when false. */
    val autoFit: Boolean = true,
    /** 0 = small, 1 = medium, 2 = large tiles. */
    val tileSize: Int = 1,
    /** Hide the status and navigation bars (swipe from an edge to show them briefly). */
    val fullScreen: Boolean = true,
    /** 0 = landscape (either way up), 1 = portrait, 2 = follow the system's auto-rotate. */
    val orientation: Int = 0,
    val showLabels: Boolean = true,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** 0 = follow system, 1 = dark, 2 = light. */
    val theme: Int = 0,
    /** Highlight color: a preset id from `AccentPresets`, or a custom "#RRGGBB". */
    val accent: String = "lime",
    /** Long-press no longer opens the editor and "Edit deck" is hidden (e.g. while streaming). */
    val lockDeck: Boolean = false,
    /** The one-time "swipe between screens" hint was shown. */
    val swipeHintShown: Boolean = false,
    val pointerSpeed: Float = 1f,
    val scrollSpeed: Float = 1f,
    val naturalScroll: Boolean = true,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("androiddeck", Context.MODE_PRIVATE)
    private val serversSerializer = ListSerializer(SavedServer.serializer())
    private val shortcutsSerializer = ListSerializer(Shortcut.serializer())

    fun loadServers(): List<SavedServer> = runCatching {
        DeckJson.decodeFromString(serversSerializer, sp.getString(KEY_SERVERS, null) ?: "[]")
    }.getOrDefault(emptyList())

    fun saveServers(list: List<SavedServer>) =
        sp.edit { putString(KEY_SERVERS, DeckJson.encodeToString(serversSerializer, list)) }

    var selectedId: String?
        get() = sp.getString(KEY_SELECTED, null)
        set(value) = sp.edit { putString(KEY_SELECTED, value) }

    fun loadSettings(): Settings {
        val raw = sp.getString(KEY_SETTINGS, null)
        if (raw == null) {
            // First load with this version: an install that already paired a PC keeps the old
            // default (screen may turn off); a new install gets "keep screen on".
            val first = if (sp.contains(KEY_SERVERS)) Settings(keepScreenOn = false) else Settings()
            saveSettings(first)
            return first
        }
        return runCatching { DeckJson.decodeFromString(Settings.serializer(), raw) }.getOrDefault(Settings())
    }

    fun saveSettings(s: Settings) =
        sp.edit { putString(KEY_SETTINGS, DeckJson.encodeToString(Settings.serializer(), s)) }

    /** Buttons shown in the home-screen widget; the first three also back the quick-settings tiles. */
    fun loadShortcuts(): List<Shortcut> = runCatching {
        DeckJson.decodeFromString(shortcutsSerializer, sp.getString(KEY_SHORTCUTS, null) ?: "[]")
    }.getOrDefault(emptyList())

    fun saveShortcuts(list: List<Shortcut>) =
        sp.edit { putString(KEY_SHORTCUTS, DeckJson.encodeToString(shortcutsSerializer, list)) }

    /** Ids of two-state buttons that are currently "on" (kept on this phone). */
    fun loadToggled(): Set<String> = sp.getStringSet(KEY_TOGGLED, emptySet()).orEmpty().toSet()

    fun saveToggled(ids: Set<String>) = sp.edit { putStringSet(KEY_TOGGLED, ids) }

    private companion object {
        const val KEY_SHORTCUTS = "shortcuts"
        const val KEY_TOGGLED = "toggled"
        const val KEY_SERVERS = "servers"
        const val KEY_SELECTED = "selected"
        const val KEY_SETTINGS = "settings"
    }
}
