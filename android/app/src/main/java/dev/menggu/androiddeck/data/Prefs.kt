package dev.menggu.androiddeck.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class Settings(
    /** 0 = small, 1 = medium, 2 = large tiles. */
    val tileSize: Int = 1,
    val showLabels: Boolean = true,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = false,
    /** 0 = follow system, 1 = dark, 2 = light. */
    val theme: Int = 0,
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

    fun loadSettings(): Settings = runCatching {
        DeckJson.decodeFromString(Settings.serializer(), sp.getString(KEY_SETTINGS, null) ?: "{}")
    }.getOrDefault(Settings())

    fun saveSettings(s: Settings) =
        sp.edit { putString(KEY_SETTINGS, DeckJson.encodeToString(Settings.serializer(), s)) }

    /** Buttons shown in the home-screen widget; the first three also back the quick-settings tiles. */
    fun loadShortcuts(): List<Shortcut> = runCatching {
        DeckJson.decodeFromString(shortcutsSerializer, sp.getString(KEY_SHORTCUTS, null) ?: "[]")
    }.getOrDefault(emptyList())

    fun saveShortcuts(list: List<Shortcut>) =
        sp.edit { putString(KEY_SHORTCUTS, DeckJson.encodeToString(shortcutsSerializer, list)) }

    private companion object {
        const val KEY_SHORTCUTS = "shortcuts"
        const val KEY_SERVERS = "servers"
        const val KEY_SELECTED = "selected"
        const val KEY_SETTINGS = "settings"
    }
}
