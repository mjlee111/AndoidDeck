package dev.menggu.androiddeck.ui.deck

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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.menggu.androiddeck.data.DeckButton
import dev.menggu.androiddeck.ui.components.contentColorFor
import dev.menggu.androiddeck.ui.components.parseHexColor
import dev.menggu.androiddeck.ui.theme.LocalPalette

val TileShape = RoundedCornerShape(22.dp)

/** Resolves an app icon URL on a given PC (device null = the deck's own PC). */
typealias IconUrl = (appId: String, device: String?) -> String?

/** Live state shown on a tile, e.g. microphone muted or media playing. */
data class TileLive(val symbol: String? = null, val dot: Color? = null)

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
                            val cTint = parseHexColor(child.color) ?: p.text
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
) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    val custom = parseHexColor(button.color)
    val bg = custom ?: p.surface
    val fg = if (custom != null) contentColorFor(custom) else p.text
    val ringTarget = when (flash) {
        true -> p.ok
        false -> p.danger
        null -> Color.Transparent
    }
    val ring by animateColorAsState(ringTarget, tween(if (flash == null) 450 else 90), label = "ring")

    BoxWithConstraints(
        modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(TileShape)
            .background(bg)
            .border(2.dp, ring, TileShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val labelVisible = showLabel && button.label.isNotBlank()
        val glyph = maxWidth * if (labelVisible) 0.34f else 0.42f
        val labelStyle = if (maxWidth < 84.dp) {
            MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing)
        } else {
            MaterialTheme.typography.labelMedium
        }
        val isFolderPreview = button.action.type == "folder" && button.icon.kind == "none" && !button.children.isNullOrEmpty()
        Column(
            Modifier.fillMaxSize().padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (isFolderPreview) FolderPreview(button, iconUrl, fg, glyph * 1.35f)
            else ButtonGlyph(button, iconUrl, fg, glyph, live)
            if (labelVisible) {
                Text(
                    button.label,
                    style = labelStyle,
                    color = fg.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        live?.dot?.let { dot ->
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
        if (editMode) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape)
                    .background(p.bg.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Edit, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
fun AddTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .aspectRatio(1f)
            .clip(TileShape)
            .border(1.5.dp, p.outline, TileShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Add, "Add button", tint = p.textDim, modifier = Modifier.size(28.dp))
    }
}
