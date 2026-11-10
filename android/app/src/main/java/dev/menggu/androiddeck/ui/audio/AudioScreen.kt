package dev.menggu.androiddeck.ui.audio

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
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
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.data.DeckAction
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.data.MediaState
import dev.menggu.androiddeck.data.MixerSession
import dev.menggu.androiddeck.ui.components.DeckCard
import dev.menggu.androiddeck.ui.components.SectionLabel
import dev.menggu.androiddeck.ui.theme.LocalPalette
import kotlin.math.roundToInt

/**
 * Local slider value that follows the PC unless the user is dragging (or just let go),
 * so remote updates never make the thumb jump back under the finger.
 */
private class FollowedLevel(initial: Float) {
    var shown by mutableFloatStateOf(initial)
    var dragging by mutableStateOf(false)
    var holdUntil by mutableLongStateOf(0L)
    var lastSent = 0L

    fun follow(remote: Float?) {
        if (remote != null && !dragging && System.currentTimeMillis() > holdUntil) shown = remote
    }

    fun drag(v: Float, send: (Float) -> Unit) {
        dragging = true
        shown = v
        val now = System.currentTimeMillis()
        if (now - lastSent > 60) {
            lastSent = now
            send(v)
        }
    }

    fun release(send: (Float) -> Unit) {
        send(shown)
        holdUntil = System.currentTimeMillis() + 1200
        dragging = false
    }
}

private fun level(v: Float) = (v * 100).roundToInt() / 100.0

@Composable
fun AudioScreen(vm: DeckViewModel, client: DeckClient) {
    val p = LocalPalette.current
    val view = LocalView.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val vol by client.volume.collectAsStateWithLifecycle()
    val mic by client.mic.collectAsStateWithLifecycle()
    val mixer by client.mixer.collectAsStateWithLifecycle()
    val media by client.media.collectAsStateWithLifecycle()
    val apps by client.apps.collectAsStateWithLifecycle()

    DisposableEffect(client.id) {
        vm.setAudioVisible(true)
        onDispose { vm.setAudioVisible(false) }
    }
    // Mixer rows show app icons when the PC's app list is known.
    LaunchedEffect(client.id, mixer.sessions.isNotEmpty()) {
        if (mixer.sessions.isNotEmpty() && client.apps.value == null) client.loadApps(false)
    }

    val master = remember(client.id) { FollowedLevel(vol.level?.toFloat() ?: 0f) }
    LaunchedEffect(vol.level, master.dragging) { master.follow(vol.level?.toFloat()) }

    fun tap() {
        if (settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    val muted = vol.muted == true
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (media.available && media.title != null) {
                Spacer(Modifier.height(8.dp))
                NowPlaying(media, client)
            }

            SectionLabel("Master volume · ${client.server.name}")
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (vol.available) "${(master.shown * 100).roundToInt()}" else "—",
                    style = MaterialTheme.typography.displayLarge,
                    color = if (muted) p.textFaint else p.text,
                )
                Text("%", style = MaterialTheme.typography.headlineSmall, color = p.textDim, modifier = Modifier.padding(bottom = 16.dp, start = 4.dp))
            }
            Text(
                when {
                    !vol.available -> "Level not readable on this PC — buttons send volume keys"
                    muted -> "Muted"
                    else -> " "
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (muted) p.danger else p.textDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            LevelSlider(
                value = master.shown,
                enabled = vol.available,
                muted = muted,
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                height = 76.dp,
                onChange = { v -> master.drag(v) { vm.run(DeckAction(type = "volume", op = "set", value = level(it))) } },
                onEnd = { master.release { vm.run(DeckAction(type = "volume", op = "set", value = level(it))) } },
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundButton(Icons.AutoMirrored.Rounded.VolumeDown, "Volume down") {
                    tap(); vm.run(DeckAction(type = "volume", op = "down", value = 0.05))
                }
                RoundButton(
                    Icons.AutoMirrored.Rounded.VolumeOff, if (muted) "Unmute" else "Mute",
                    bg = if (muted) p.danger else null, tint = if (muted) Color.White else null,
                ) {
                    tap(); vm.run(DeckAction(type = "volume", op = "mute"))
                }
                RoundButton(Icons.AutoMirrored.Rounded.VolumeUp, "Volume up") {
                    tap(); vm.run(DeckAction(type = "volume", op = "up", value = 0.05))
                }
            }

            SectionLabel("Media")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RoundButton(Icons.Rounded.Stop, "Stop", size = 48.dp) {
                    tap(); vm.run(DeckAction(type = "media", key = "stop"))
                }
                RoundButton(Icons.Rounded.SkipPrevious, "Previous", size = 60.dp) {
                    tap(); vm.run(DeckAction(type = "media", key = "previous"))
                }
                Box(
                    Modifier.size(84.dp).clip(CircleShape).background(p.accent).clickable {
                        tap(); vm.run(DeckAction(type = "media", key = "play_pause"))
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        media.available && media.playing -> Icon(Icons.Rounded.Pause, "Pause", tint = p.onAccent, modifier = Modifier.size(38.dp))
                        media.available -> Icon(Icons.Rounded.PlayArrow, "Play", tint = p.onAccent, modifier = Modifier.size(40.dp))
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlayArrow, "Play/Pause", tint = p.onAccent, modifier = Modifier.size(32.dp))
                            Icon(Icons.Rounded.Pause, null, tint = p.onAccent, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                RoundButton(Icons.Rounded.SkipNext, "Next", size = 60.dp) {
                    tap(); vm.run(DeckAction(type = "media", key = "next"))
                }
                Spacer(Modifier.width(48.dp))
            }

            if (mic.available) {
                SectionLabel("Microphone")
                MicRow(mic.level?.toFloat() ?: 0f, mic.muted == true, onToggle = {
                    tap(); vm.run(DeckAction(type = "mic", op = "toggle"))
                }, onLevel = { vm.run(DeckAction(type = "mic", op = "set", value = level(it))) })
            }

            if (mixer.available) {
                SectionLabel("Apps")
                if (mixer.sessions.isEmpty()) {
                    Text(
                        "No app is playing audio right now.",
                        style = MaterialTheme.typography.bodySmall, color = p.textDim,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                mixer.sessions.forEach { s ->
                    key(s.id) {
                        MixerRow(
                            session = s,
                            iconUrl = s.appId?.takeIf { id -> apps?.any { it.id == id && it.hasIcon } == true }?.let(client::iconUrl),
                            onLevel = { vm.run(DeckAction(type = "mixer", target = s.id, op = "set", value = level(it))) },
                            onMute = { tap(); vm.run(DeckAction(type = "mixer", target = s.id, op = "mute")) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun NowPlaying(media: MediaState, client: DeckClient) {
    val p = LocalPalette.current
    DeckCard(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(p.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, null, tint = p.textFaint, modifier = Modifier.size(28.dp))
                client.artUrl(media.art)?.let { url ->
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(media.title ?: "", style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(media.artist, media.album).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = p.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                media.source?.let {
                    Text(
                        (if (media.playing) "Playing in " else "Paused in ") + it,
                        style = MaterialTheme.typography.labelSmall, color = if (media.playing) p.ok else p.textFaint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MicRow(level: Float, muted: Boolean, onToggle: () -> Unit, onLevel: (Float) -> Unit) {
    val p = LocalPalette.current
    val state = remember { FollowedLevel(level) }
    LaunchedEffect(level, state.dragging) { state.follow(level) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton(
            if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (muted) "Unmute microphone" else "Mute microphone",
            size = 52.dp, bg = if (muted) p.danger else null, tint = if (muted) Color.White else null, onClick = onToggle,
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            LevelSlider(
                value = state.shown, enabled = true, muted = muted, icon = null, height = 52.dp,
                onChange = { v -> state.drag(v, onLevel) }, onEnd = { state.release(onLevel) },
            )
        }
    }
}

@Composable
private fun MixerRow(session: MixerSession, iconUrl: String?, onLevel: (Float) -> Unit, onMute: () -> Unit) {
    val p = LocalPalette.current
    val state = remember(session.id) { FollowedLevel(session.level.toFloat()) }
    LaunchedEffect(session.level, state.dragging) { state.follow(session.level.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (iconUrl != null) AsyncImage(model = iconUrl, contentDescription = null, modifier = Modifier.size(20.dp))
                else Icon(Icons.Rounded.Apps, null, tint = p.textDim, modifier = Modifier.size(18.dp))
            }
            Text(
                session.name, style = MaterialTheme.typography.labelLarge, color = p.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            Text("${(state.shown * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = p.textDim)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                LevelSlider(
                    value = state.shown, enabled = true, muted = session.muted, icon = null, height = 40.dp,
                    onChange = { v -> state.drag(v, onLevel) }, onEnd = { state.release(onLevel) },
                )
            }
            Spacer(Modifier.width(10.dp))
            RoundButton(
                Icons.AutoMirrored.Rounded.VolumeOff, if (session.muted) "Unmute ${session.name}" else "Mute ${session.name}",
                size = 40.dp, bg = if (session.muted) p.danger else null, tint = if (session.muted) Color.White else null, onClick = onMute,
            )
        }
    }
}

@Composable
private fun LevelSlider(
    value: Float,
    enabled: Boolean,
    muted: Boolean,
    icon: ImageVector?,
    height: Dp,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
) {
    val p = LocalPalette.current
    // The gesture coroutine outlives recompositions; always call the latest callbacks.
    val latestChange by rememberUpdatedState(onChange)
    val latestEnd by rememberUpdatedState(onEnd)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height * 0.34f))
            .background(p.surfaceHigh)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    latestChange((down.position.x / w).coerceIn(0f, 1f))
                    horizontalDrag(down.id) { change ->
                        change.consume()
                        latestChange((change.position.x / w).coerceIn(0f, 1f))
                    }
                    latestEnd()
                }
            },
    ) {
        val fillColor = if (muted) p.textFaint else p.accent
        Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).background(if (enabled) fillColor else p.surfaceHigher))
        if (icon != null) {
            Icon(
                icon, null,
                tint = if (value > 0.12f && enabled) p.onAccent else p.textDim,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp).size(26.dp),
            )
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    size: Dp = 60.dp,
    bg: Color? = null,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Box(
        Modifier.size(size).clip(CircleShape).background(bg ?: p.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint ?: p.text, modifier = Modifier.size(size * 0.42f))
    }
}
