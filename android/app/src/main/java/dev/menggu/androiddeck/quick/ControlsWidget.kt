package dev.menggu.androiddeck.quick

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.menggu.androiddeck.data.DeckAction
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.data.LiveState
import dev.menggu.androiddeck.data.Prefs
import dev.menggu.androiddeck.data.SavedServer
import dev.menggu.androiddeck.ui.theme.DarkPalette
import dev.menggu.androiddeck.ui.theme.accentColor
import dev.menggu.androiddeck.ui.theme.onColorFor
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val CONTROL = ActionParameters.Key<String>("control")

/** The PC the controls act on: the one selected in the app, else the first paired one. */
private fun controlsPc(context: Context): SavedServer? {
    val prefs = Prefs(context)
    val servers = prefs.loadServers()
    return servers.find { it.id == prefs.selectedId } ?: servers.firstOrNull()
}

/** Action behind each control button. */
internal fun controlAction(key: String): DeckAction? = when (key) {
    "vol_down" -> DeckAction(type = "volume", op = "down", value = 0.05)
    "vol_up" -> DeckAction(type = "volume", op = "up", value = 0.05)
    "vol_mute" -> DeckAction(type = "volume", op = "toggle")
    "mic" -> DeckAction(type = "mic", op = "toggle")
    "prev" -> DeckAction(type = "media", key = "previous")
    "play" -> DeckAction(type = "media", key = "play_pause")
    "next" -> DeckAction(type = "media", key = "next")
    else -> null
}

/**
 * Home-screen controls for the selected PC: volume, mic, media and CPU/RAM, more of them as the
 * widget grows. Home-screen widgets cannot drag, so levels change in steps; the state is read over
 * HTTPS when the widget updates (after every press, on refresh, and every 30 minutes).
 */
class ControlsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val pc = controlsPc(context)
        val accent = accentColor(Prefs(context).loadSettings().accent, dark = true)
        val state = pc?.let { withTimeoutOrNull(5_000) { DeckClient.fetchState(it).getOrNull() } }
        val icons = WidgetIcons(accent)
        provideContent { ControlsBody(pc?.name, state, accent, icons) }
    }
}

/** Icons are drawn to bitmaps once per update (RemoteViews cannot draw vectors). */
private class WidgetIcons(accent: Color) {
    private val cache = HashMap<Pair<ImageVector, Int>, ImageProvider>()
    val onAccent = onColorFor(accent).toArgb()
    fun get(icon: ImageVector, color: Int = DarkPalette.text.toArgb()): ImageProvider =
        cache.getOrPut(icon to color) { ImageProvider(icon.toBitmap(72, color)) }
}

@Composable
private fun ControlsBody(pcName: String?, s: LiveState?, accent: Color, icons: WidgetIcons) {
    val p = DarkPalette
    val size = LocalSize.current
    val dim = TextStyle(color = ColorProvider(p.textDim), fontSize = 12.sp)
    Column(
        GlanceModifier.fillMaxSize().background(ColorProvider(p.bg)).cornerRadius(24.dp).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pcName ?: "AndroidDeck",
                maxLines = 1,
                style = TextStyle(color = ColorProvider(p.text), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (pcName != null && s == null) Text("Offline  ", style = dim)
            Image(
                icons.get(Icons.Rounded.Refresh, p.textDim.toArgb()), "Refresh",
                modifier = GlanceModifier.size(26.dp).padding(3.dp).clickable(actionRunCallback<RefreshControls>()),
            )
        }
        if (pcName == null) {
            Text("Pair a PC in AndroidDeck first.", style = dim, modifier = GlanceModifier.padding(top = 6.dp))
            return@Column
        }
        Spacer(GlanceModifier.height(6.dp))
        val vol = s?.volume
        val muted = vol?.muted == true
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ControlButton(icons.get(Icons.AutoMirrored.Rounded.VolumeDown), "Volume down", "vol_down", p.surface)
            Text(
                vol?.level?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                style = TextStyle(color = ColorProvider(if (muted) p.textDim else p.text), fontSize = 18.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight().padding(horizontal = 10.dp),
            )
            ControlButton(icons.get(Icons.AutoMirrored.Rounded.VolumeUp), "Volume up", "vol_up", p.surface)
            Spacer(GlanceModifier.width(6.dp))
            ControlButton(
                icons.get(Icons.AutoMirrored.Rounded.VolumeOff, if (muted) DarkPalette.bg.toArgb() else p.text.toArgb()),
                if (muted) "Unmute" else "Mute", "vol_mute", if (muted) p.danger else p.surface,
            )
        }
        if (size.height >= 120.dp) {
            Spacer(GlanceModifier.height(8.dp))
            val micMuted = s?.mic?.muted == true
            val playing = s?.media?.playing == true
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ControlButton(
                    icons.get(if (micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (micMuted) DarkPalette.bg.toArgb() else p.text.toArgb()),
                    if (micMuted) "Unmute microphone" else "Mute microphone", "mic", if (micMuted) p.danger else p.surface,
                )
                Text(
                    s?.media?.title?.takeIf { s.media.available } ?: "Nothing playing",
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(p.textDim), fontSize = 12.sp),
                    modifier = GlanceModifier.defaultWeight().padding(horizontal = 10.dp),
                )
                ControlButton(icons.get(Icons.Rounded.SkipPrevious), "Previous", "prev", p.surface)
                Spacer(GlanceModifier.width(6.dp))
                ControlButton(
                    icons.get(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, icons.onAccent),
                    if (playing) "Pause" else "Play", "play", accent,
                )
                Spacer(GlanceModifier.width(6.dp))
                ControlButton(icons.get(Icons.Rounded.SkipNext), "Next", "next", p.surface)
            }
        }
        if (size.height >= 170.dp) {
            Spacer(GlanceModifier.height(8.dp))
            val u = s?.usage
            Text(
                if (u != null) "CPU ${u.cpu.roundToInt()}%  ·  RAM ${u.mem.roundToInt()}%" else "CPU —  ·  RAM —",
                style = TextStyle(color = ColorProvider(p.text), fontSize = 14.sp),
            )
        }
    }
}

@Composable
private fun ControlButton(icon: ImageProvider, description: String, key: String, bg: Color) {
    Box(
        GlanceModifier.size(44.dp).cornerRadius(22.dp).background(ColorProvider(bg))
            .clickable(actionRunCallback<RunControl>(actionParametersOf(CONTROL to key))),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, description, modifier = GlanceModifier.size(22.dp))
    }
}

class RunControl : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val action = parameters[CONTROL]?.let(::controlAction) ?: return
        val pc = controlsPc(context) ?: return
        DeckClient.runOnce(pc, action)
        ControlsWidget().updateAll(context)
    }
}

class RefreshControls : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ControlsWidget().updateAll(context)
    }
}

class ControlsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ControlsWidget()
}
