package dev.menggu.androiddeck.ui.deck

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import dev.menggu.androiddeck.data.DeckAction

/** Built-in button symbols, addressed by a stable name stored in the profile. */
object Symbols {
    val all: List<Pair<String, ImageVector>> = listOf(
        "play_pause" to Icons.Rounded.PlayArrow,
        "pause" to Icons.Rounded.Pause,
        "stop" to Icons.Rounded.Stop,
        "skip_previous" to Icons.Rounded.SkipPrevious,
        "skip_next" to Icons.Rounded.SkipNext,
        "rewind" to Icons.Rounded.FastRewind,
        "forward" to Icons.Rounded.FastForward,
        "shuffle" to Icons.Rounded.Shuffle,
        "repeat" to Icons.Rounded.Repeat,
        "volume_up" to Icons.AutoMirrored.Rounded.VolumeUp,
        "volume_down" to Icons.AutoMirrored.Rounded.VolumeDown,
        "volume_off" to Icons.AutoMirrored.Rounded.VolumeOff,
        "mic" to Icons.Rounded.Mic,
        "mic_off" to Icons.Rounded.MicOff,
        "mouse" to Icons.Rounded.Mouse,
        "videocam" to Icons.Rounded.Videocam,
        "videocam_off" to Icons.Rounded.VideocamOff,
        "record" to Icons.Rounded.FiberManualRecord,
        "headphones" to Icons.Rounded.Headphones,
        "music" to Icons.Rounded.MusicNote,
        "equalizer" to Icons.Rounded.Equalizer,
        "screen_share" to Icons.Rounded.ScreenShare,
        "cast" to Icons.Rounded.Cast,
        "screenshot" to Icons.Rounded.Screenshot,
        "camera" to Icons.Rounded.PhotoCamera,
        "image" to Icons.Rounded.Image,
        "movie" to Icons.Rounded.Movie,
        "tv" to Icons.Rounded.Tv,
        "games" to Icons.Rounded.SportsEsports,
        "gamepad" to Icons.Rounded.Gamepad,
        "apps" to Icons.Rounded.Apps,
        "grid" to Icons.Rounded.GridView,
        "desktop" to Icons.Rounded.DesktopWindows,
        "computer" to Icons.Rounded.Computer,
        "monitor" to Icons.Rounded.Speed,
        "dashboard" to Icons.Rounded.Dashboard,
        "memory" to Icons.Rounded.Memory,
        "storage" to Icons.Rounded.Storage,
        "terminal" to Icons.Rounded.Terminal,
        "code" to Icons.Rounded.Code,
        "bug" to Icons.Rounded.BugReport,
        "build" to Icons.Rounded.Build,
        "rocket" to Icons.Rounded.RocketLaunch,
        "keyboard" to Icons.Rounded.Keyboard,
        "text" to Icons.Rounded.TextFields,
        "copy" to Icons.Rounded.ContentCopy,
        "paste" to Icons.Rounded.ContentPaste,
        "cut" to Icons.Rounded.ContentCut,
        "undo" to Icons.AutoMirrored.Rounded.Undo,
        "redo" to Icons.AutoMirrored.Rounded.Redo,
        "save" to Icons.Rounded.Save,
        "search" to Icons.Rounded.Search,
        "fullscreen" to Icons.Rounded.Fullscreen,
        "layers" to Icons.Rounded.Layers,
        "web" to Icons.Rounded.Language,
        "link" to Icons.Rounded.Link,
        "mail" to Icons.Rounded.Email,
        "chat" to Icons.Rounded.Forum,
        "folder" to Icons.Rounded.Folder,
        "file" to Icons.Rounded.Description,
        "cloud" to Icons.Rounded.Cloud,
        "download" to Icons.Rounded.Download,
        "upload" to Icons.Rounded.Upload,
        "calendar" to Icons.Rounded.CalendarMonth,
        "calculator" to Icons.Rounded.Calculate,
        "notifications" to Icons.Rounded.Notifications,
        "timer" to Icons.Rounded.Timer,
        "wifi" to Icons.Rounded.Wifi,
        "bluetooth" to Icons.Rounded.Bluetooth,
        "settings" to Icons.Rounded.Settings,
        "home" to Icons.Rounded.Home,
        "lock" to Icons.Rounded.Lock,
        "power" to Icons.Rounded.PowerSettingsNew,
        "sleep" to Icons.Rounded.Bedtime,
        "refresh" to Icons.Rounded.Refresh,
        "bolt" to Icons.Rounded.Bolt,
        "light" to Icons.Rounded.Lightbulb,
        "light_mode" to Icons.Rounded.LightMode,
        "dark_mode" to Icons.Rounded.DarkMode,
        "brush" to Icons.Rounded.Brush,
        "palette" to Icons.Rounded.Palette,
        "star" to Icons.Rounded.Star,
        "favorite" to Icons.Rounded.Favorite,
        "party" to Icons.Rounded.Celebration,
        "coffee" to Icons.Rounded.LocalCafe,
        "check" to Icons.Rounded.Check,
        "close" to Icons.Rounded.Close,
        "add" to Icons.Rounded.Add,
        "edit" to Icons.Rounded.Edit,
        "delete" to Icons.Rounded.Delete,
    )

    private val byName = all.toMap()

    operator fun get(name: String?): ImageVector? = name?.let { byName[it] }

    /** Symbol used when a button has no explicit icon. */
    fun forAction(a: DeckAction): String = when (a.type) {
        "app" -> "apps"
        "hotkey" -> "keyboard"
        "text" -> "text"
        "media" -> when (a.key) {
            "next" -> "skip_next"
            "previous" -> "skip_previous"
            "stop" -> "stop"
            else -> "play_pause"
        }
        "volume" -> when (a.op) {
            "down" -> "volume_down"
            "mute" -> "volume_off"
            else -> "volume_up"
        }
        "mic" -> if (a.op == "mute") "mic_off" else "mic"
        "multi" -> "bolt"
        "folder" -> "folder"
        "wake" -> "power"
        "url" -> "web"
        "command" -> "terminal"
        "system" -> if (a.op == "sleep") "sleep" else "lock"
        else -> "add"
    }
}
