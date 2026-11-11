package dev.menggu.androiddeck.ui.deck

import dev.menggu.androiddeck.ui.theme.onColorFor
import dev.menggu.androiddeck.ui.theme.accentOn
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.data.ConnState
import dev.menggu.androiddeck.data.DeckAction
import dev.menggu.androiddeck.data.DeckButton
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.ui.components.BarMeter
import dev.menggu.androiddeck.ui.components.FollowedLevel
import dev.menggu.androiddeck.ui.theme.LocalPalette
import kotlin.math.roundToInt

/** Widget kinds offered in the editor: type, label, default size (cols x rows). */
internal data class WidgetKind(val type: String, val label: String, val cols: Int, val rows: Int)

internal val WIDGET_KINDS = listOf(
    WidgetKind("volume", "Volume", 3, 1),
    WidgetKind("mic", "Mic level", 3, 1),
    WidgetKind("mixer", "App volume", 3, 1),
    WidgetKind("media", "Now playing", 3, 1),
    WidgetKind("monitor", "PC monitor", 2, 1),
)

internal fun widgetLabel(type: String, appName: String? = null): String = when (type) {
    "volume" -> "Volume"
    "mic" -> "Mic"
    "mixer" -> appName ?: "App"
    "media" -> "Now playing"
    "monitor" -> "PC"
    else -> ""
}

private fun round2(v: Float) = (v * 100).roundToInt() / 100.0

/**
 * A live control on the deck: a fader (volume, mic, one app), now playing, or CPU/RAM. It reads
 * from, and acts on, [target] (the button's PC). In edit mode it only renders; the deck handles taps.
 */
@Composable
fun WidgetTile(button: DeckButton, target: DeckClient?, vm: DeckViewModel, editMode: Boolean, haptics: Boolean, modifier: Modifier = Modifier) {
    val w = button.widget ?: return
    val p = LocalPalette.current
    val connected = target?.state?.collectAsStateWithLifecycle()?.value == ConnState.Connected
    if (target != null) {
        // Mixer and stats stream only while something on screen needs them.
        DisposableEffect(target.id, w.type) {
            val release = vm.holdLive(target.id, stats = w.type == "monitor", mixer = w.type == "mixer")
            onDispose { release() }
        }
    }
    val device = button.action.device
    val run: (DeckAction) -> Unit = { vm.run(it.copy(device = device)) }
    val live = connected && !editMode
    Box(modifier.clip(TileShape).background(p.surface).alpha(if (connected || editMode) 1f else 0.45f)) {
        if (target == null) {
            Text("PC not found", style = MaterialTheme.typography.labelMedium, color = p.textDim, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        when (w.type) {
            "volume", "mic" -> {
                val mic = w.type == "mic"
                val st by (if (mic) target.mic else target.volume).collectAsStateWithLifecycle()
                val muted = st.muted == true
                Fader(
                    level = st.level?.toFloat(),
                    muted = muted,
                    enabled = live && st.available,
                    icon = when {
                        mic && muted -> Icons.Rounded.MicOff
                        mic -> Icons.Rounded.Mic
                        muted -> Icons.AutoMirrored.Rounded.VolumeOff
                        else -> Icons.AutoMirrored.Rounded.VolumeUp
                    },
                    iconUrl = null,
                    label = button.label.ifBlank { widgetLabel(w.type) },
                    note = if (!st.available && connected) "Not available" else null,
                    haptics = haptics,
                    onLevel = { run(DeckAction(type = w.type, op = "set", value = round2(it))) },
                    onToggle = { run(DeckAction(type = w.type, op = "toggle")) },
                )
            }
            "mixer" -> {
                val mixer by target.mixer.collectAsStateWithLifecycle()
                val s = mixer.sessions.firstOrNull { it.id == w.target }
                    ?: mixer.sessions.firstOrNull { w.name != null && it.name.equals(w.name, ignoreCase = true) }
                Fader(
                    level = s?.level?.toFloat(),
                    muted = s?.muted == true,
                    enabled = live && s != null,
                    icon = Icons.Rounded.Apps,
                    iconUrl = s?.appId?.let(target::iconUrl),
                    label = button.label.ifBlank { widgetLabel("mixer", w.name) },
                    note = if (s == null && connected) "Not playing" else null,
                    haptics = haptics,
                    onLevel = { v -> s?.let { run(DeckAction(type = "mixer", target = it.id, op = "set", value = round2(v))) } },
                    onToggle = { s?.let { run(DeckAction(type = "mixer", target = it.id, op = "mute")) } },
                )
            }
            "media" -> MediaWidget(target, live, haptics, run)
            "monitor" -> MonitorWidget(target)
        }
    }
}

/**
 * The whole tile is a fader: drag along its long side (relative, after the touch slop) to set the
 * level, tap to mute. Vertical when the tile is at least as tall as it is wide.
 */
@Composable
private fun Fader(
    level: Float?,
    muted: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    iconUrl: String?,
    label: String,
    note: String?,
    haptics: Boolean,
    onLevel: (Float) -> Unit,
    onToggle: () -> Unit,
) {
    val p = LocalPalette.current
    val view = LocalView.current
    val state = remember { FollowedLevel(level ?: 0f) }
    LaunchedEffect(level, state.dragging) { state.follow(level) }
    val send by rememberUpdatedState(onLevel)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vertical = maxHeight >= maxWidth
        val short = minOf(maxWidth, maxHeight)
        val fraction = if (level == null) 0f else state.shown.coerceIn(0f, 1f)
        val percent = if (level == null) "—" else "${(fraction * 100).roundToInt()}%"
        val fillColor = if (muted) p.textFaint else p.accentOn(p.surface)
        val onFill = if (muted) p.text else onColorFor(fillColor)
        Box(
            Modifier
                .fillMaxSize()
                // One node for screen readers: the label is drawn twice for contrast.
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = note ?: if (muted) "Muted, $percent" else percent
                    if (enabled) {
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                        setProgress { v ->
                            state.drag(v.coerceIn(0f, 1f), send)
                            state.release(send)
                            true
                        }
                        onClick(label = if (muted) "Unmute" else "Mute") {
                            onToggle()
                            true
                        }
                    }
                }
                .then(
                    if (!enabled) Modifier else Modifier
                        .pointerInput(vertical) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val length = (if (vertical) size.height else size.width).toFloat().coerceAtLeast(1f)
                                var moved = 0f
                                val drag = if (vertical) {
                                    awaitVerticalTouchSlopOrCancellation(down.id) { c, over -> c.consume(); moved = over }
                                } else {
                                    awaitHorizontalTouchSlopOrCancellation(down.id) { c, over -> c.consume(); moved = over }
                                } ?: return@awaitEachGesture
                                val start = state.shown
                                fun apply() = state.drag((start + (if (vertical) -moved else moved) / length).coerceIn(0f, 1f), send)
                                apply()
                                if (vertical) {
                                    verticalDrag(drag.id) { c -> moved += c.positionChange().y; c.consume(); apply() }
                                } else {
                                    horizontalDrag(drag.id) { c -> moved += c.positionChange().x; c.consume(); apply() }
                                }
                                state.release(send)
                            }
                        }
                        .clickable {
                            if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onToggle()
                        },
                ),
        ) {
            Box(
                (if (vertical) Modifier.fillMaxWidth().fillMaxHeight(fraction).align(Alignment.BottomCenter)
                else Modifier.fillMaxHeight().fillMaxWidth(fraction).align(Alignment.CenterStart))
                    .background(if (enabled) fillColor else p.surfaceHigher),
            )
            // Drawn twice and clipped at the fill edge, so text contrasts on both sides of it.
            for ((color, filledSide) in listOf(p.text to false, onFill to true)) {
                Box(
                    Modifier.matchParentSize().drawWithContent {
                        if (vertical) {
                            val edge = size.height * (1f - fraction)
                            clipRect(top = if (filledSide) edge else 0f, bottom = if (filledSide) size.height else edge) {
                                this@drawWithContent.drawContent()
                            }
                        } else {
                            val edge = size.width * fraction
                            clipRect(left = if (filledSide) 0f else edge, right = if (filledSide) edge else size.width) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    },
                ) {
                    FaderLabel(vertical, short, icon, iconUrl, label, note ?: percent, color)
                }
            }
        }
    }
}

@Composable
private fun FaderLabel(vertical: Boolean, short: Dp, icon: ImageVector, iconUrl: String?, label: String, value: String, color: Color) {
    val iconSize = (short * 0.26f).coerceIn(18.dp, 34.dp)
    val glyph: @Composable () -> Unit = {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            if (iconUrl != null) AsyncImage(model = iconUrl, contentDescription = null, modifier = Modifier.size(iconSize))
            else Icon(icon, null, tint = color, modifier = Modifier.size(iconSize))
        }
    }
    if (vertical) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            glyph()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
                Text(label, style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            glyph()
            Text(
                label, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
        }
    }
}

@Composable
private fun MediaWidget(target: DeckClient, live: Boolean, haptics: Boolean, run: (DeckAction) -> Unit) {
    val p = LocalPalette.current
    val view = LocalView.current
    val media by target.media.collectAsStateWithLifecycle()
    fun press(key: String) {
        if (!live) return
        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        run(DeckAction(type = "media", key = key))
    }
    val title = media.title?.takeIf { media.available }
    val artist = listOfNotNull(media.artist, media.source).firstOrNull()?.takeIf { media.available }
    val art: @Composable (Modifier) -> Unit = { m ->
        Box(m.clip(RoundedCornerShape(14.dp)).background(p.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = p.textFaint, modifier = Modifier.size(24.dp))
            target.artUrl(media.art)?.let { url ->
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
    val playing = media.available && media.playing
    BoxWithConstraints(Modifier.fillMaxSize().semantics { stateDescription = if (playing) "Playing ${title ?: ""}" else "Paused" }) {
        val wide = maxWidth > maxHeight * 1.3f
        val small = maxWidth < 190.dp && maxHeight < 190.dp
        val showArtist = maxHeight >= 90.dp
        val roomyRow = maxWidth >= 300.dp
        val roomyColumn = maxWidth >= 150.dp
        when {
            // One cell: the art is the play/pause button.
            small && !wide -> Box(Modifier.fillMaxSize().clickable(enabled = live) { press("play_pause") }) {
                art(Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(p.accent), contentAlignment = Alignment.Center) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = p.onAccent)
                }
            }
            wide -> Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                art(Modifier.fillMaxHeight().aspectRatio(1f))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(title ?: "Nothing playing", style = MaterialTheme.typography.titleSmall, color = if (title != null) p.text else p.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (artist != null && showArtist) {
                        Text(artist, style = MaterialTheme.typography.bodySmall, color = p.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                MediaControls(roomyRow, playing, live, ::press)
            }
            else -> Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                art(Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = true))
                Spacer(Modifier.height(8.dp))
                Text(title ?: "Nothing playing", style = MaterialTheme.typography.labelLarge, color = if (title != null) p.text else p.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                MediaControls(roomyColumn, playing, live, ::press)
            }
        }
    }
}

@Composable
private fun MediaControls(withPrevious: Boolean, playing: Boolean, enabled: Boolean, press: (String) -> Unit) {
    val p = LocalPalette.current
    @Composable
    fun Round(icon: ImageVector, desc: String, key: String, primary: Boolean = false) {
        Box(
            Modifier.size(if (primary) 46.dp else 40.dp).clip(CircleShape).background(if (primary) p.accent else p.surfaceHigh)
                .clickable(enabled = enabled) { press(key) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, desc, tint = if (primary) p.onAccent else p.text, modifier = Modifier.size(if (primary) 26.dp else 22.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (withPrevious) Round(Icons.Rounded.SkipPrevious, "Previous", "previous")
        Round(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", "play_pause", primary = true)
        Round(Icons.Rounded.SkipNext, "Next", "next")
    }
}

@Composable
private fun MonitorWidget(target: DeckClient) {
    val p = LocalPalette.current
    val stats by target.stats.collectAsStateWithLifecycle()
    val s = stats
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…", style = MaterialTheme.typography.labelMedium, color = p.textDim)
        }
        return
    }
    val lines = buildList {
        add(Triple("CPU", s.cpu.percent, p.cpu))
        add(Triple("RAM", s.mem.percent, p.mem))
        s.gpus.firstOrNull()?.util?.let { add(Triple("GPU", it, p.gpu)) }
        s.disks.firstOrNull()?.let { add(Triple("Disk", it.percent, p.disk)) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        val small = maxWidth < 150.dp
        if (small) {
            // One cell: CPU as a big number, RAM below.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${s.cpu.percent.roundToInt()}%", style = MaterialTheme.typography.headlineSmall, color = p.text)
                Text("CPU", style = MaterialTheme.typography.labelSmall, color = p.textDim)
                Spacer(Modifier.height(6.dp))
                Text("RAM ${s.mem.percent.roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = p.textDim)
            }
        } else {
            val fit = (maxHeight / 30.dp).toInt().coerceIn(1, lines.size)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                lines.take(fit).forEach { (name, pct, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.labelMedium, color = p.textDim, modifier = Modifier.width(38.dp))
                        BarMeter((pct / 100).toFloat(), color, Modifier.weight(1f), height = 8.dp)
                        Text("${pct.roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = p.text, modifier = Modifier.width(48.dp).padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
