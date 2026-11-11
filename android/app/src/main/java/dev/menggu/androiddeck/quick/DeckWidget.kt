package dev.menggu.androiddeck.quick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
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
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.menggu.androiddeck.data.Net
import androidx.compose.ui.graphics.Color
import dev.menggu.androiddeck.data.Prefs
import dev.menggu.androiddeck.data.Shortcut
import dev.menggu.androiddeck.ui.components.contentColorFor
import dev.menggu.androiddeck.ui.components.buttonColor
import dev.menggu.androiddeck.ui.deck.Symbols
import dev.menggu.androiddeck.ui.deck.appIconId
import dev.menggu.androiddeck.ui.theme.DarkPalette
import dev.menggu.androiddeck.ui.theme.accentColor
import kotlin.math.max

private val INDEX = ActionParameters.Key<Int>("shortcut_index")

/** Home-screen widget: a grid of the buttons marked "Show in widget & quick tiles". */
class DeckWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = Prefs(context)
        val shortcuts = prefs.loadShortcuts()
        // The widget is always dark; it follows the app's highlight color.
        val accent = accentColor(prefs.loadSettings().accent, dark = true)
        val glyphs = shortcuts.map { glyphFor(context, it, accent) }
        provideContent { WidgetBody(shortcuts, glyphs, accent) }
    }

    private suspend fun glyphFor(context: Context, s: Shortcut, accent: Color): Bitmap? {
        val button = s.button
        val bg = buttonColor(button.color, accent)
        val tint = (if (bg != null) contentColorFor(bg) else DarkPalette.text).toArgb()
        val appId = button.appIconId()
        if (appId != null) {
            val servers = Prefs(context).loadServers()
            val owner = servers.find { it.id == (button.icon.device ?: button.action.device ?: s.serverId) }
            if (owner != null) {
                Net.register(owner)
                val url = "${Net.baseUrl(owner.host, owner.port)}/api/icon/$appId"
                val result = runCatching {
                    SingletonImageLoader.get(context).execute(
                        ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build(),
                    )
                }.getOrNull()
                if (result is SuccessResult) return result.image.toBitmap()
            }
        }
        val name = if (button.icon.kind == "symbol") button.icon.value else Symbols.forAction(button.action)
        val vector = Symbols[name] ?: Symbols[Symbols.forAction(button.action)] ?: return null
        return vector.toBitmap(96, tint)
    }
}

@Composable
private fun WidgetBody(shortcuts: List<Shortcut>, glyphs: List<Bitmap?>, accent: Color) {
    val size = LocalSize.current
    val p = DarkPalette
    Column(
        GlanceModifier.fillMaxSize().background(ColorProvider(p.bg)).cornerRadius(24.dp).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (shortcuts.isEmpty()) {
            Text(
                "Open AndroidDeck, edit a button and turn on “Show in widget”.",
                style = TextStyle(color = ColorProvider(p.textDim), fontSize = 13.sp, textAlign = TextAlign.Center),
                modifier = GlanceModifier.padding(12.dp),
            )
            return@Column
        }
        val columns = max(1, ((size.width.value - 16) / 76).toInt()).coerceAtMost(shortcuts.size)
        val rows = shortcuts.chunked(columns)
        val tileHeight = ((size.height.value - 16 - (rows.size - 1) * 6) / rows.size).coerceIn(48f, 110f)
        rows.forEachIndexed { r, row ->
            if (r > 0) Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                row.forEachIndexed { c, s ->
                    if (c > 0) Spacer(GlanceModifier.width(6.dp))
                    val index = r * columns + c
                    WidgetTile(s, glyphs.getOrNull(index), index, tileHeight, accent)
                }
            }
        }
    }
}

@Composable
private fun RowScope.WidgetTile(s: Shortcut, glyph: Bitmap?, index: Int, height: Float, accent: Color) {
    val p = DarkPalette
    val bg = buttonColor(s.button.color, accent)
    val fg = if (bg != null) contentColorFor(bg) else p.text
    Box(
        GlanceModifier
            .defaultWeight()
            .height(height.dp)
            .cornerRadius(16.dp)
            .background(ColorProvider(bg ?: p.surface))
            .clickable(actionRunCallback<RunShortcutAction>(actionParametersOf(INDEX to index))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (glyph != null) {
                val iconSize = (height * 0.42f).coerceAtMost(40f).dp
                Image(ImageProvider(glyph), contentDescription = s.button.label, modifier = GlanceModifier.size(iconSize))
            }
            if (s.button.label.isNotBlank() && height >= 64) {
                Text(
                    s.button.label,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(fg), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

class RunShortcutAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val index = parameters[INDEX] ?: return
        val shortcut = Prefs(context).loadShortcuts().getOrNull(index) ?: return
        QuickActions.runAndReport(context, shortcut)
    }
}

class DeckWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DeckWidget()
}

/** Rasterizes a Material icon for RemoteViews, which cannot draw Compose vectors. */
fun ImageVector.toBitmap(sizePx: Int, color: Int): Bitmap {
    val bmp = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bmp)
    canvas.scale(sizePx / viewportWidth, sizePx / viewportHeight)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    fun draw(group: VectorGroup) {
        for (node in group) {
            when (node) {
                is VectorPath -> {
                    val path = node.pathData.toPath().asAndroidPath()
                    path.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                        android.graphics.Path.FillType.EVEN_ODD
                    } else {
                        android.graphics.Path.FillType.WINDING
                    }
                    canvas.drawPath(path, paint)
                }
                is VectorGroup -> draw(node)
            }
        }
    }
    draw(root)
    return bmp
}
