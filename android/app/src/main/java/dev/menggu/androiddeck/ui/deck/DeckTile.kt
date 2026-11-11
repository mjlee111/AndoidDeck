package dev.menggu.androiddeck.ui.deck

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.menggu.androiddeck.data.DeckButton
import dev.menggu.androiddeck.ui.components.contentColorFor
import dev.menggu.androiddeck.ui.components.buttonColor
import dev.menggu.androiddeck.ui.theme.LocalPalette
import dev.menggu.androiddeck.ui.theme.onColorFor

val TileShape = RoundedCornerShape(22.dp)

/** Resolves an app icon URL on a given PC (device null = the deck's own PC). */
typealias IconUrl = (appId: String, device: String?) -> String?

/** Live state shown on a tile, e.g. microphone muted or media playing. */
data class TileLive(
    val symbol: String? = null,
    val dot: Color? = null,
    /** Short status badge (e.g. "MUTED"); with [tone] the whole tile is tinted and outlined. */
    val badge: String? = null,
    val tone: Color? = null,
    /** State read by screen readers, e.g. "Muted". */
    val state: String? = null,
)

/** What a screen reader says for a button: its label, or what it does when it has none. */
fun DeckButton.spokenName(): String = label.ifBlank {
    val a = action
    when (a.type) {
        "app" -> a.name ?: "App"
        "hotkey" -> a.keys.orEmpty().joinToString(" + ").ifBlank { "Hotkey" }
        "media" -> when (a.key) { "next" -> "Next track"; "previous" -> "Previous track"; "stop" -> "Stop"; else -> "Play or pause" }
        "volume" -> when (a.op) { "down" -> "Volume down"; "mute" -> "Mute"; "set" -> "Set volume"; else -> "Volume up" }
        "mic" -> when (a.op) { "mute" -> "Mute microphone"; "unmute" -> "Unmute microphone"; else -> "Microphone" }
        "folder" -> "Folder"
        "multi" -> "Macro"
        "text" -> "Type text"
        "url" -> a.url ?: "Website"
        "command" -> "Run command"
        "system" -> if (a.op == "sleep") "Sleep" else "Lock"
        "wake" -> "Wake PC"
        else -> "Button"
    }
}

/** App id whose icon should be drawn for this button, if any. */
fun DeckButton.appIconId(): String? = when {
    icon.kind == "app" && !icon.value.isNullOrBlank() -> icon.value
    icon.kind == "none" && action.type == "app" -> action.appId
    else -> null
}

/** PC that owns the app icon: explicit icon device, else the action's target PC. */
fun DeckButton.appIconDevice(): String? = if (icon.kind == "app") icon.device else action.device

@Composable
fun ButtonGlyph(button: DeckButton, iconUrl: IconUrl, tint: Color, size: Dp, live: TileLive? = null) {
    val appId = button.appIconId()
    val url = appId?.let { iconUrl(it, button.appIconDevice()) }
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size * 1.2f),
        )
        return
    }
    val name = live?.symbol ?: if (button.icon.kind == "symbol") button.icon.value else Symbols.forAction(button.action)
    val vector = Symbols[name] ?: Symbols[Symbols.forAction(button.action)]
    if (vector != null) Icon(vector, null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun FolderPreview(button: DeckButton, iconUrl: IconUrl, tint: Color, size: Dp) {
    val kids = button.children.orEmpty().take(4)
    val p = LocalPalette.current
    val cell = size * 0.5f
    Column(Modifier.size(size), verticalArrangement = Arrangement.SpaceEvenly) {
        for (row in 0 until 2) {
            Row(Modifier.size(width = size, height = cell), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (col in 0 until 2) {
                    val child = kids.getOrNull(row * 2 + col)
                    Box(
                        Modifier.size(cell * 0.86f).clip(RoundedCornerShape(cell * 0.26f)).background(tint.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (child != null) {
                            val cTint = buttonColor(child.color, p.accent) ?: p.text
                            ButtonGlyph(child, iconUrl, cTint, cell * 0.5f)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeckTile(
    button: DeckButton,
    iconUrl: IconUrl,
    showLabel: Boolean,
    editMode: Boolean,
    flash: Boolean?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    live: TileLive? = null,
    deviceBadge: ImageVector? = null,
    /** The PC this button acts on is not connected. */
    offline: Boolean = false,
    /** Spoken name of the long-press action, e.g. "Edit" or "Next track". */
    onLongClickLabel: String? = null,
    /** Called with true on press and false on release or cancel (push-to-talk buttons). */
    onPressChange: ((Boolean) -> Unit)? = null,
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressChange by rememberUpdatedState(onPressChange)
    if (onPressChange != null) {
        LaunchedEffect(interaction) {
            interaction.interactions.collect {
                when (it) {
                    is PressInteraction.Press -> pressChange?.invoke(true)
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressChange?.invoke(false)
                }
            }
        }
    }
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    val custom = buttonColor(button.color, p.accent)
    val tone = live?.tone
    // A toned state (muted) overrides the button color so it reads the same on every tile.
    val bg = when {
        tone != null -> lerp(p.surface, tone, 0.22f)
        else -> custom ?: p.surface
    }
    val fg = if (custom != null && tone == null) contentColorFor(custom) else p.text
    val ringTarget = when (flash) {
        true -> p.ok
        false -> p.danger
        null -> tone ?: Color.Transparent
    }
    val ring by animateColorAsState(ringTarget, tween(if (flash == null) 450 else 90), label = "ring")
    // The whole tile flashes green or red, readable at arm's length.
    val fillTarget = when (flash) {
        true -> p.ok.copy(alpha = 0.30f)
        false -> p.danger.copy(alpha = 0.40f)
        null -> Color.Transparent
    }
    val fill by animateColorAsState(fillTarget, tween(if (flash == null) 450 else 90), label = "fill")
    val stateText = live?.state ?: if (offline) "PC not connected" else null
    val spoken = if (showLabel && button.label.isNotBlank()) null else button.spokenName()

    // Size comes from the caller (cells on the deck grid); tiles can be wide, tall or square.
    BoxWithConstraints(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (offline) 0.45f else 1f)
            .clip(TileShape)
            .background(bg)
            .background(fill)
            .border(2.5.dp, ring, TileShape)
            .semantics {
                if (stateText != null) stateDescription = stateText
                if (spoken != null) contentDescription = spoken
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val labelVisible = showLabel && button.label.isNotBlank()
        val short = minOf(maxWidth, maxHeight)
        // Wide tiles put the label beside the icon; square and tall ones stack them.
        val wide = maxWidth > maxHeight * 1.6f
        val tall = maxHeight > maxWidth * 1.6f
        val glyph = short * if (labelVisible && !wide) 0.34f else 0.42f
        val labelStyle = when {
            wide && short >= 96.dp -> MaterialTheme.typography.titleMedium
            wide -> MaterialTheme.typography.titleSmall
            short < 84.dp -> MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing)
            short > 140.dp -> MaterialTheme.typography.labelLarge
            else -> MaterialTheme.typography.labelMedium
        }
        val isFolderPreview = button.action.type == "folder" && button.icon.kind == "none" && !button.children.isNullOrEmpty()
        val icon: @Composable () -> Unit = {
            if (isFolderPreview) FolderPreview(button, iconUrl, fg, glyph * 1.35f)
            else ButtonGlyph(button, iconUrl, tone ?: fg, glyph, live)
        }
        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                icon()
                if (labelVisible) {
                    Text(
                        button.label, style = labelStyle, color = fg.copy(alpha = 0.92f), maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp),
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                icon()
                if (labelVisible) {
                    Text(
                        button.label,
                        style = labelStyle,
                        color = fg.copy(alpha = 0.88f),
                        maxLines = if (tall) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        if (live?.badge != null) {
            // Toned badges (MUTED) use the tone; plain ones (ON) a dark scrim over the button color.
            val badgeBg = tone ?: Color.Black.copy(alpha = 0.38f)
            Text(
                live.badge,
                style = MaterialTheme.typography.labelSmall,
                color = if (tone != null) onColorFor(tone) else Color.White,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(7.dp)
                    .clip(RoundedCornerShape(7.dp)).background(badgeBg).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        } else live?.dot?.let { dot ->
            Box(
                Modifier.align(Alignment.TopStart).padding(9.dp).size(9.dp).clip(CircleShape).background(dot)
                    .border(1.5.dp, bg, CircleShape),
            )
        }
        if (deviceBadge != null && !editMode) {
            Icon(
                deviceBadge, "Runs on another PC", tint = fg.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(14.dp),
            )
        }
        if (editMode) EditBadge(Modifier.align(Alignment.TopEnd))
    }
}

/** Pencil shown on deck items in edit mode. */
@Composable
fun EditBadge(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier.padding(7.dp).size(22.dp).clip(CircleShape).background(p.bg.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Edit, null, tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

@Composable
fun AddTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .clip(TileShape)
            .border(1.5.dp, p.outline, TileShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Add, "Add button", tint = p.textDim, modifier = Modifier.size(28.dp))
    }
}
