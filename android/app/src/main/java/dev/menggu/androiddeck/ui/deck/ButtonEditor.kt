package dev.menggu.androiddeck.ui.deck

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import dev.menggu.androiddeck.data.ButtonToggle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.Location
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.menggu.androiddeck.data.MAX_SPAN
import dev.menggu.androiddeck.data.TileWidget
import dev.menggu.androiddeck.data.ButtonIcon
import dev.menggu.androiddeck.data.DeckAction
import dev.menggu.androiddeck.data.DeckButton
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.data.DeckPage
import dev.menggu.androiddeck.data.SavedServer
import dev.menggu.androiddeck.quick.QuickActions
import dev.menggu.androiddeck.ui.components.Chip
import dev.menggu.androiddeck.ui.components.DeckCard
import dev.menggu.androiddeck.ui.components.DeckTextField
import dev.menggu.androiddeck.ui.components.PrimaryButton
import dev.menggu.androiddeck.ui.components.SecondaryButton
import dev.menggu.androiddeck.ui.components.SectionLabel
import dev.menggu.androiddeck.ui.components.Segmented
import dev.menggu.androiddeck.ui.components.osIcon
import dev.menggu.androiddeck.ui.components.ACCENT_COLOR
import dev.menggu.androiddeck.ui.components.buttonColor
import dev.menggu.androiddeck.ui.theme.LocalPalette
import java.util.Locale
import kotlin.math.roundToInt

private data class ActionType(val type: String, val label: String, val icon: ImageVector)

private val ACTION_TYPES = listOf(
    ActionType("app", "App", Icons.Rounded.Apps),
    ActionType("hotkey", "Hotkey", Icons.Rounded.Keyboard),
    ActionType("media", "Media", Icons.Rounded.MusicNote),
    ActionType("volume", "Volume", Icons.AutoMirrored.Rounded.VolumeUp),
    ActionType("mic", "Mic", Icons.Rounded.Mic),
    ActionType("multi", "Multi", Icons.Rounded.Bolt),
    ActionType("folder", "Folder", Icons.Rounded.Folder),
    ActionType("text", "Text", Icons.Rounded.TextFields),
    ActionType("url", "Website", Icons.Rounded.Language),
    ActionType("command", "Command", Icons.Rounded.Terminal),
    ActionType("system", "System", Icons.Rounded.Lock),
    ActionType("wake", "Wake PC", Icons.Rounded.PowerSettingsNew),
)

/** Action kinds allowed as steps of a multi-action (no folders, no nesting, nothing phone-side). */
private val STEP_TYPES = ACTION_TYPES.filter { it.type !in setOf("multi", "folder", "wake") }

val SWATCHES = listOf(
    null, ACCENT_COLOR, "#5BE49B", "#2ED8C3", "#54A0FF", "#7C83FD", "#B794FF",
    "#FF7EB6", "#FF6B6B", "#FF9F43", "#FECA57", "#E5E7EB", "#2B2F39",
)

private fun defaultAction(type: String): DeckAction = when (type) {
    "hotkey" -> DeckAction(type = "hotkey", keys = emptyList())
    "media" -> DeckAction(type = "media", key = "play_pause")
    "volume" -> DeckAction(type = "volume", op = "up", value = 0.05)
    "mic" -> DeckAction(type = "mic", op = "toggle")
    "text" -> DeckAction(type = "text", text = "")
    "url" -> DeckAction(type = "url", url = "")
    "command" -> DeckAction(type = "command", command = "")
    "system" -> DeckAction(type = "system", op = "lock")
    "multi" -> DeckAction(type = "multi", steps = listOf(DeckAction(type = "hotkey", keys = emptyList())))
    else -> DeckAction(type = type)
}

private fun autoLabel(a: DeckAction, os: String, pcName: (String?) -> String?): String = when (a.type) {
    "app" -> a.name ?: ""
    "hotkey" -> comboLabel(a.keys ?: emptyList(), os)
    "media" -> when (a.key) { "next" -> "Next"; "previous" -> "Prev"; "stop" -> "Stop"; else -> "Play" }
    "volume" -> when (a.op) { "down" -> "Vol −"; "mute" -> "Mute"; "set" -> "Vol ${((a.value ?: 0.5) * 100).roundToInt()}%"; else -> "Vol +" }
    "mic" -> when (a.op) { "mute" -> "Mic off"; "unmute" -> "Mic on"; else -> "Mic" }
    "url" -> a.url?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/')?.take(16) ?: ""
    "system" -> if (a.op == "sleep") "Sleep" else "Lock"
    "command" -> "Run"
    "text" -> "Type"
    "multi" -> "Macro"
    "folder" -> "Folder"
    "wake" -> "Wake ${pcName(a.device) ?: "PC"}".take(20)
    else -> ""
}

private fun validationError(a: DeckAction, nested: Boolean = false): String? = when (a.type) {
    "app" -> if (a.appId.isNullOrBlank()) "Choose an app" else null
    "hotkey" -> if (a.keys.isNullOrEmpty()) "Pick at least one key" else null
    "text" -> if (a.text.isNullOrEmpty()) "Enter the text to type" else null
    "url" -> if (a.url.isNullOrBlank()) "Enter a web address" else null
    "command" -> if (a.command.isNullOrBlank()) "Enter a command" else null
    "multi" -> when {
        nested -> "Steps cannot be multi-actions"
        a.steps.isNullOrEmpty() -> "Add at least one step"
        else -> a.steps.withIndex().firstNotNullOfOrNull { (i, s) -> validationError(s, true)?.let { "Step ${i + 1}: $it" } }
    }
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonEditor(
    vm: DeckViewModel,
    owner: DeckClient,
    request: EditorRequest,
    pages: List<DeckPage>,
    onSave: (DeckButton, Location, Boolean) -> Unit,
    onDelete: (DeckButton) -> Unit,
    onClose: () -> Unit,
    onDuplicate: (DeckButton) -> Unit = {},
) {
    val p = LocalPalette.current
    val servers by vm.servers.collectAsStateWithLifecycle()
    val isNew = request.button == null
    val pcName: (String?) -> String? = { id -> servers.find { it.id == (id ?: owner.id) }?.name }
    var draft by remember { mutableStateOf(request.button ?: DeckButton(action = DeckAction(type = "app"))) }
    val target = vm.client(draft.action.device) ?: owner
    val os = target.server.os
    // Labels follow the action automatically until the user types their own.
    var labelTouched by remember {
        mutableStateOf(!isNew && draft.label != (draft.widget?.let { widgetLabel(it.type, it.name) } ?: autoLabel(draft.action, os, pcName)))
    }
    val isWidget = draft.widget != null
    // Switching an existing folder to a widget would drop its buttons, so that switch is not offered.
    val canSwitchKind = !(request.button?.action?.type == "folder" && !request.button.children.isNullOrEmpty())
    var targetPage by remember { mutableStateOf(request.location.pageId) }
    // Where the item is saved: a page, or a folder on that page (moving it there).
    var targetFolder by remember { mutableStateOf(request.location.folderId) }
    val inFolder = targetFolder != null
    var picker by remember { mutableStateOf<String?>(null) } // "action" | "icon" | "step:N"
    var confirmDelete by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(request.button?.let { vm.isShortcut(it.id) } ?: false) }

    fun setAction(a: DeckAction, presetName: String? = null) {
        val children = if (a.type == "folder") draft.children ?: emptyList() else null
        val plain = a.type in setOf("folder", "wake") || a.hold == true
        draft = draft.copy(
            action = a,
            children = children,
            toggle = if (plain) null else draft.toggle,
            longAction = if (plain) null else draft.longAction,
            label = if (labelTouched) draft.label else (presetName ?: autoLabel(a, (vm.client(a.device) ?: owner).server.os, pcName)),
        )
    }

    fun setWidget(kind: WidgetKind, target: String? = null, name: String? = null) {
        val resize = draft.widget == null && draft.cols == 1 && draft.rows == 1
        draft = draft.copy(
            widget = TileWidget(kind.type, target, name),
            action = DeckAction(type = "none", device = draft.action.device),
            children = null,
            toggle = null,
            longAction = null,
            cols = if (resize) kind.cols else draft.cols,
            rows = if (resize) kind.rows else draft.rows,
            label = if (labelTouched) draft.label else widgetLabel(kind.type, name),
        )
    }

    val error = draft.widget?.let { w ->
        if (w.type == "mixer" && w.target == null && w.name.isNullOrBlank()) "Choose an app" else null
    } ?: if (isWidget) null else validationError(draft.action)
        ?: draft.toggle?.let { t -> validationError(t.action, nested = true)?.let { "Second action: $it" } }
        ?: draft.longAction?.let { la -> validationError(la, nested = true)?.let { "Long press: $it" } }
    val location = Location(targetPage, if (draft.action.type == "folder") null else targetFolder)

    BackHandler(enabled = picker == null, onBack = onClose)

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = p.text) }
                Text(
                    when {
                        isWidget -> if (isNew) "New widget" else "Edit widget"
                        else -> if (isNew) "New button" else "Edit button"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = p.text,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    "Save", { onSave(draft, location, pinned && draft.action.type != "folder" && !isWidget) },
                    enabled = error == null, modifier = Modifier.padding(end = 8.dp),
                )
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                // Preview at the item's real proportions (cols x rows cells).
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    val gap = 8.dp
                    val cell = minOf(96.dp, (300.dp - gap * (draft.cols - 1)) / draft.cols, (200.dp - gap * (draft.rows - 1)) / draft.rows)
                    val size = Modifier.size(cell * draft.cols + gap * (draft.cols - 1), cell * draft.rows + gap * (draft.rows - 1))
                    if (isWidget) {
                        WidgetTile(draft, target, vm, editMode = true, haptics = false, modifier = size)
                    } else {
                        DeckTile(
                            button = draft,
                            iconUrl = { appId, device -> (vm.client(device) ?: owner).iconUrl(appId) },
                            showLabel = true,
                            editMode = false,
                            flash = null,
                            onClick = {},
                            onLongClick = null,
                            modifier = size,
                        )
                    }
                }
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = p.textDim,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), textAlign = TextAlign.Center)
                }

                val type = draft.action.type
                if (canSwitchKind) {
                    SectionLabel("Type")
                    Segmented(
                        options = listOf(false to "Button", true to "Widget"),
                        selected = isWidget,
                        onSelect = { toWidget ->
                            if (toWidget != isWidget) {
                                if (toWidget) {
                                    setWidget(WIDGET_KINDS.first())
                                } else {
                                    val a = defaultAction("app").copy(device = draft.action.device)
                                    draft = draft.copy(widget = null, action = a, label = if (labelTouched) draft.label else "")
                                }
                            }
                        },
                    )
                }

                if (isWidget) {
                    val w = draft.widget!!
                    SectionLabel("Widget")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                        items(WIDGET_KINDS) { k ->
                            Chip(k.label, selected = w.type == k.type, icon = widgetIcon(k.type), onClick = {
                                if (w.type != k.type) setWidget(k)
                            })
                        }
                    }
                    if (servers.size > 1) {
                        SectionLabel("PC")
                        DevicePicker(servers, owner.id, draft.action.device) { dev ->
                            draft = draft.copy(action = draft.action.copy(device = dev))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    DeckCard(Modifier.fillMaxWidth()) {
                        WidgetConfig(w, target, vm, onApp = { id, name -> setWidget(WIDGET_KINDS.first { it.type == "mixer" }, id, name) })
                    }
                } else {
                SectionLabel("Action")
                val types = if (inFolder) ACTION_TYPES.filter { it.type != "folder" } else ACTION_TYPES
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    items(types) { t ->
                        Chip(t.label, selected = draft.action.type == t.type, icon = t.icon, onClick = {
                            if (draft.action.type != t.type) setAction(defaultAction(t.type).copy(device = draft.action.device))
                        })
                    }
                }

                if (servers.size > 1 && type != "folder" && type != "wake") {
                    SectionLabel("Run on")
                    DevicePicker(servers, owner.id, draft.action.device) { dev ->
                        val a = if (draft.action.type == "app") defaultAction("app").copy(device = dev) else draft.action.copy(device = dev)
                        setAction(a)
                    }
                }
                Spacer(Modifier.height(12.dp))
                DeckCard(Modifier.fillMaxWidth()) {
                    when (type) {
                        "multi" -> MultiConfig(draft.action, target, os, onPickApp = { picker = "step:$it" }) { setAction(it) }
                        "folder" -> Text(
                            "${draft.children?.size ?: 0} buttons inside. Save, then tap the folder on the deck " +
                                "(in edit mode) to add or arrange its buttons.",
                            style = MaterialTheme.typography.bodyMedium, color = p.textDim,
                        )
                        "wake" -> WakeConfig(servers, draft.action.device ?: owner.id) { setAction(draft.action.copy(device = it)) }
                        else -> ActionConfig(draft.action, target, os, onPickApp = { picker = "action" }, onChange = ::setAction)
                    }
                }
                val targetFeatures by target.features.collectAsStateWithLifecycle()
                if (type == "hotkey" && (targetFeatures.hold || draft.action.hold == true)) {
                    Spacer(Modifier.height(8.dp))
                    EditorSwitch(
                        "Hold while pressed",
                        "The keys stay down while your finger is on the button, like push-to-talk.",
                        draft.action.hold == true,
                    ) { on -> setAction(draft.action.copy(hold = if (on) true else null)) }
                }
                if (type !in setOf("folder", "wake") && draft.action.hold != true) {
                    SectionLabel("Two states")
                    EditorSwitch(
                        "Toggle button",
                        "Presses alternate: the action above turns it on, a second action turns it off. The tile lights up while on.",
                        draft.toggle != null,
                    ) { on ->
                        draft = draft.copy(toggle = if (on) ButtonToggle(action = defaultAction(type).copy(device = draft.action.device)) else null)
                    }
                    draft.toggle?.let { t ->
                        Spacer(Modifier.height(8.dp))
                        DeckTextField(
                            value = t.label ?: "",
                            onValueChange = { draft = draft.copy(toggle = t.copy(label = it.take(40).ifBlank { null })) },
                            placeholder = "Label while on (optional)",
                        )
                        Spacer(Modifier.height(10.dp))
                        ActionPicker("While on, a press runs", t.action, target, os, onPickApp = { picker = "toggle" }) { a ->
                            draft = draft.copy(toggle = t.copy(action = a))
                        }
                    }
                    SectionLabel("Long press")
                    EditorSwitch(
                        "Long-press action",
                        "Runs on a long press instead of opening the editor (edit from ⋮ → Edit deck).",
                        draft.longAction != null,
                    ) { on -> draft = draft.copy(longAction = if (on) defaultAction("media") else null) }
                    draft.longAction?.let { la ->
                        Spacer(Modifier.height(10.dp))
                        ActionPicker("A long press runs", la, target, os, onPickApp = { picker = "long" }) { a -> draft = draft.copy(longAction = a) }
                    }
                }
                }

                SectionLabel("Label")
                DeckTextField(
                    value = draft.label,
                    onValueChange = {
                        labelTouched = true
                        draft = draft.copy(label = it.take(40))
                    },
                    placeholder = "Shown under the icon",
                )

                SectionLabel("Size")
                SizePicker(draft.cols, draft.rows) { c, r -> draft = draft.copy(cols = c, rows = r) }

                if (!isWidget) {
                SectionLabel("Icon")
                val iconKind = draft.icon.kind
                Segmented(
                    options = listOf("none" to "Auto", "symbol" to "Symbol", "app" to "App icon"),
                    selected = iconKind,
                    onSelect = { kind ->
                        when (kind) {
                            "none" -> draft = draft.copy(icon = ButtonIcon("none"))
                            "symbol" -> draft = draft.copy(icon = ButtonIcon("symbol", draft.icon.value?.takeIf { Symbols[it] != null }
                                ?: Symbols.forAction(draft.action)))
                            "app" -> picker = "icon"
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                if (iconKind == "symbol") {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Symbols.all.forEach { (name, vector) ->
                            val sel = draft.icon.value == name
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                                    .background(if (sel) p.accent else p.surface)
                                    .clickable { draft = draft.copy(icon = ButtonIcon("symbol", name)) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(vector, name, tint = if (sel) p.onAccent else p.text, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                } else if (iconKind == "app") {
                    SecondaryButton("Choose another app icon", { picker = "icon" }, icon = Icons.Rounded.Apps)
                }

                SectionLabel("Color")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SWATCHES.forEach { hex ->
                        val c = buttonColor(hex, p.accent)
                        // Buttons saved with the original lime count as the highlight color.
                        val sel = draft.color == hex || (hex == ACCENT_COLOR && draft.color.equals("#C6F36B", ignoreCase = true))
                        Box(
                            Modifier.size(38.dp).clip(CircleShape)
                                .background(c ?: p.surface)
                                .border(if (sel) 3.dp else 1.dp, if (sel) p.text else p.outline, CircleShape)
                                .clickable { draft = draft.copy(color = hex) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c == null) Text("–", color = p.textDim)
                        }
                    }
                }
                }

                val folders = if (type == "folder") emptyList()
                else pages.find { it.id == targetPage }?.buttons?.filter { it.action.type == "folder" && it.id != draft.id }.orEmpty()
                if (pages.size > 1 || folders.isNotEmpty()) {
                    SectionLabel("Place")
                    if (pages.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            pages.forEach { pg ->
                                Chip(pg.name, selected = targetPage == pg.id, onClick = {
                                    if (targetPage != pg.id) targetFolder = null
                                    targetPage = pg.id
                                })
                            }
                        }
                    }
                    if (folders.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("On the page", selected = targetFolder == null, onClick = { targetFolder = null })
                            folders.forEach { f ->
                                Chip(f.label.ifBlank { "Folder" }, selected = targetFolder == f.id, icon = Icons.Rounded.Folder, onClick = {
                                    targetFolder = f.id
                                })
                            }
                        }
                    }
                }

                if (type != "folder" && !isWidget) {
                    SectionLabel("Shortcuts")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Widgets, null, tint = p.textDim, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Show in widget & quick tiles", style = MaterialTheme.typography.titleSmall, color = p.text)
                            Text(
                                "Home-screen widget holds ${QuickActions.MAX_SHORTCUTS}; the first ${QuickActions.TILE_COUNT} " +
                                    "also appear as Quick Settings tiles.",
                                style = MaterialTheme.typography.bodySmall, color = p.textDim,
                            )
                        }
                        Switch(
                            checked = pinned, onCheckedChange = { pinned = it },
                            colors = editorSwitchColors(),
                        )
                    }
                }

                if (!isNew) {
                    Spacer(Modifier.height(28.dp))
                    SecondaryButton(
                        if (isWidget) "Duplicate widget" else "Duplicate", { onDuplicate(request.button!!) },
                        icon = Icons.Rounded.ContentCopy,
                    )
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton(
                        when {
                            confirmDelete -> "Tap again to delete"
                            type == "folder" -> "Delete folder and its buttons"
                            isWidget -> "Delete widget"
                            else -> "Delete button"
                        },
                        { if (confirmDelete) onDelete(draft) else confirmDelete = true },
                        icon = Icons.Rounded.Delete,
                        color = p.danger,
                    )
                }
                Spacer(Modifier.height(40.dp))
            }
        }

        val pick = picker
        when {
            pick == "action" -> AppPicker(target, "Choose an app", onPick = { app ->
                setAction(DeckAction(type = "app", appId = app.id, target = app.target, name = app.name, device = draft.action.device))
                if (draft.icon.kind == "app") draft = draft.copy(icon = ButtonIcon("none"))
                picker = null
            }, onClose = { picker = null })
            pick == "toggle" || pick == "long" -> AppPicker(target, "Choose an app", onPick = { app ->
                val a = DeckAction(type = "app", appId = app.id, target = app.target, name = app.name)
                draft = if (pick == "toggle") draft.copy(toggle = draft.toggle?.copy(action = a)) else draft.copy(longAction = a)
                picker = null
            }, onClose = { picker = null })
            pick == "icon" -> AppPicker(target, "Use an app's icon", onPick = { app ->
                draft = draft.copy(icon = ButtonIcon("app", app.id, device = target.id.takeIf { it != owner.id }))
                picker = null
            }, onClose = { picker = null })
            pick != null && pick.startsWith("step:") -> {
                val index = pick.removePrefix("step:").toInt()
                AppPicker(target, "Choose an app", onPick = { app ->
                    val steps = draft.action.steps.orEmpty().toMutableList()
                    if (index in steps.indices) {
                        steps[index] = DeckAction(type = "app", appId = app.id, target = app.target, name = app.name, delay = steps[index].delay)
                        setAction(draft.action.copy(steps = steps))
                    }
                    picker = null
                }, onClose = { picker = null })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePicker(servers: List<SavedServer>, ownerId: String, selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        servers.forEach { s ->
            val isOwner = s.id == ownerId
            Chip(
                if (isOwner) "${s.name} (this deck)" else s.name,
                selected = (selected ?: ownerId) == s.id,
                icon = osIcon(s.os),
                onClick = { onSelect(if (isOwner) null else s.id) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WakeConfig(servers: List<SavedServer>, selected: String, onSelect: (String) -> Unit) {
    val p = LocalPalette.current
    Text("PC to wake", style = MaterialTheme.typography.labelMedium, color = p.textDim)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        servers.forEach { s -> Chip(s.name, selected = selected == s.id, icon = osIcon(s.os), onClick = { onSelect(s.id) }) }
    }
    val s = servers.find { it.id == selected }
    Text(
        when {
            s == null -> ""
            s.mac == null -> "Connect to ${s.name} once so the app can learn its network address."
            else -> "Sends a Wake-on-LAN packet to ${s.mac}. Wake-on-LAN must be enabled in the PC's BIOS/network settings."
        },
        style = MaterialTheme.typography.bodySmall, color = p.textDim, modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun MultiConfig(
    a: DeckAction,
    target: DeckClient,
    os: String,
    onPickApp: (Int) -> Unit,
    onChange: (DeckAction) -> Unit,
) {
    val p = LocalPalette.current
    val steps = a.steps.orEmpty()
    fun update(newSteps: List<DeckAction>) = onChange(a.copy(steps = newSteps))

    Text("Runs these steps in order on ${target.server.name}.", style = MaterialTheme.typography.bodySmall, color = p.textDim)
    steps.forEachIndexed { i, step ->
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surfaceHigh).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Step ${i + 1}", style = MaterialTheme.typography.titleSmall, color = p.text, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (i > 0) update(steps.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Move up", tint = if (i > 0) p.textDim else p.textFaint)
                }
                IconButton(
                    onClick = { if (i < steps.lastIndex) update(steps.toMutableList().apply { add(i + 1, removeAt(i)) }) },
                    enabled = i < steps.lastIndex,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Move down", tint = if (i < steps.lastIndex) p.textDim else p.textFaint)
                }
                IconButton(onClick = { update(steps.filterIndexed { j, _ -> j != i }) }) {
                    Icon(Icons.Rounded.Delete, "Remove step", tint = p.textDim)
                }
            }
            val delay = step.delay ?: 0
            Text(
                if (delay == 0) "Wait before: none" else "Wait before: ${String.format(Locale.US, "%.1f", delay / 1000f)} s",
                style = MaterialTheme.typography.labelMedium, color = p.textDim,
            )
            Slider(
                value = delay / 1000f,
                onValueChange = { v -> update(steps.mapIndexed { j, s -> if (j == i) s.copy(delay = (v * 10).roundToInt() * 100) else s }) },
                valueRange = 0f..5f,
                colors = SliderDefaults.colors(thumbColor = p.text, activeTrackColor = p.accent, inactiveTrackColor = p.surfaceHigher),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(STEP_TYPES) { t ->
                    Chip(t.label, selected = step.type == t.type, icon = t.icon, bg = p.surfaceHigher, onClick = {
                        if (step.type != t.type) {
                            update(steps.mapIndexed { j, s -> if (j == i) defaultAction(t.type).copy(delay = s.delay) else s })
                        }
                    })
                }
            }
            Spacer(Modifier.height(12.dp))
            ActionConfig(step, target, os, onPickApp = { onPickApp(i) }, onChange = { na, _ ->
                update(steps.mapIndexed { j, s -> if (j == i) na.copy(delay = s.delay) else s })
            })
        }
    }
    Spacer(Modifier.height(14.dp))
    if (steps.size < 16) {
        SecondaryButton("Add step", { update(steps + DeckAction(type = "hotkey", keys = emptyList(), delay = 200)) }, icon = Icons.Rounded.Add)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionConfig(
    a: DeckAction,
    client: DeckClient,
    os: String,
    onPickApp: () -> Unit,
    onChange: (DeckAction, String?) -> Unit,
) {
    val p = LocalPalette.current
    val features by client.features.collectAsStateWithLifecycle()
    val hint: @Composable (String) -> Unit = {
        Text(it, style = MaterialTheme.typography.bodySmall, color = p.textDim, modifier = Modifier.padding(top = 10.dp))
    }
    when (a.type) {
        "app" -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onPickApp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(p.surfaceHigh), contentAlignment = Alignment.Center) {
                if (a.appId != null) AsyncImage(model = client.iconUrl(a.appId), contentDescription = null, modifier = Modifier.size(38.dp))
                else Icon(Icons.Rounded.Apps, null, tint = p.textDim)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(a.name ?: "No app selected", style = MaterialTheme.typography.titleMedium, color = p.text)
                Text("Launches on ${client.server.name}", style = MaterialTheme.typography.bodySmall, color = p.textDim)
            }
            Text(if (a.appId == null) "Choose" else "Change", style = MaterialTheme.typography.labelLarge, color = p.text,
                modifier = Modifier.clip(CircleShape).background(p.surfaceHigh).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        "hotkey" -> HotkeyEditor(a.keys ?: emptyList(), os) { keys, preset -> onChange(a.copy(keys = keys), preset) }
        "media" -> Segmented(
            options = listOf("previous" to "", "play_pause" to "", "next" to "", "stop" to ""),
            selected = a.key ?: "play_pause",
            onSelect = { onChange(a.copy(key = it), null) },
            icons = mapOf(
                "previous" to Symbols["skip_previous"]!!, "play_pause" to Symbols["play_pause"]!!,
                "next" to Symbols["skip_next"]!!, "stop" to Symbols["stop"]!!,
            ),
        )
        "volume" -> LevelConfig(a, onChange, muteIcon = Icons.AutoMirrored.Rounded.VolumeOff)
        "mic" -> Column {
            Segmented(
                options = listOf("toggle" to "Toggle", "mute" to "Mute", "unmute" to "Unmute"),
                selected = a.op ?: "toggle",
                // value = 1 makes "Mute" explicit on servers that treat a bare mute as a toggle.
                onSelect = { onChange(a.copy(op = it, value = if (it == "mute") 1.0 else null), null) },
                icons = mapOf("toggle" to Icons.Rounded.SwapVert, "mute" to Icons.Rounded.MicOff, "unmute" to Icons.Rounded.Mic),
            )
            hint(
                if (features.mic) "Mutes the PC's default microphone. The button shows a red dot while muted."
                else "This PC reported no controllable microphone.",
            )
        }
        "text" -> Column {
            DeckTextField(a.text ?: "", { onChange(a.copy(text = it), null) }, "Text to type", singleLine = false, minLines = 3)
            hint("Typed on the PC as if from a keyboard.")
        }
        "url" -> Column {
            DeckTextField(
                a.url ?: "", { onChange(a.copy(url = it.trim()), null) }, "https://example.com",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            hint("Opens in the PC's default browser. App links like steam:// or spotify: work too.")
        }
        "command" -> Column {
            DeckTextField(
                a.command ?: "", { onChange(a.copy(command = it), null) },
                if (os == "windows") "notepad.exe" else "open -a Calculator",
                singleLine = false, monospace = true, minLines = 2,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            hint(
                if (features.commands) "Runs in the PC's shell (${if (os == "windows") "cmd" else "sh"}) without a window."
                else "Command buttons are disabled in this PC's server config."
            )
        }
        "system" -> Segmented(
            options = listOf("lock" to "Lock", "sleep" to "Sleep"),
            selected = a.op ?: "lock",
            onSelect = { onChange(a.copy(op = it), null) },
            icons = mapOf("lock" to Icons.Rounded.Lock, "sleep" to Icons.Rounded.Bedtime),
        )
        else -> Text("Choose an action type above.", color = p.textDim)
    }
}

@Composable
private fun LevelConfig(a: DeckAction, onChange: (DeckAction, String?) -> Unit, muteIcon: ImageVector) {
    val p = LocalPalette.current
    Column {
        Segmented(
            options = listOf("down" to "", "up" to "", "mute" to "", "set" to "Set"),
            selected = a.op ?: "up",
            onSelect = { op ->
                val v = when (op) { "set" -> 0.5; "mute" -> null; else -> 0.05 }
                onChange(a.copy(op = op, value = v), null)
            },
            icons = mapOf(
                "down" to Icons.AutoMirrored.Rounded.VolumeDown,
                "up" to Icons.AutoMirrored.Rounded.VolumeUp,
                "mute" to muteIcon,
                "set" to Icons.Rounded.Tune,
            ),
        )
        if (a.op == "up" || a.op == "down" || a.op == "set") {
            val isSet = a.op == "set"
            val v = (a.value ?: if (isSet) 0.5 else 0.05).toFloat()
            Spacer(Modifier.height(14.dp))
            Text(
                if (isSet) "Level ${(v * 100).roundToInt()}%" else "Step ${(v * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge, color = p.text,
            )
            Slider(
                value = v,
                onValueChange = { nv -> onChange(a.copy(value = (nv * 100).roundToInt() / 100.0), null) },
                valueRange = if (isSet) 0f..1f else 0.01f..0.25f,
                colors = SliderDefaults.colors(thumbColor = p.text, activeTrackColor = p.accent, inactiveTrackColor = p.surfaceHigher),
            )
        }
    }
}

private fun widgetIcon(type: String): ImageVector = when (type) {
    "volume" -> Icons.AutoMirrored.Rounded.VolumeUp
    "mic" -> Icons.Rounded.Mic
    "mixer" -> Icons.Rounded.Tune
    "media" -> Icons.Rounded.MusicNote
    else -> Icons.Rounded.Insights
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetConfig(w: TileWidget, target: DeckClient, vm: DeckViewModel, onApp: (String?, String?) -> Unit) {
    val p = LocalPalette.current
    @Composable
    fun text(s: String) = Text(s, style = MaterialTheme.typography.bodyMedium, color = p.textDim)
    when (w.type) {
        "volume" -> text("Drag to set the volume of ${target.server.name}; tap to mute. Wide sizes slide sideways, tall ones up and down.")
        "mic" -> text("Drag to set the microphone level; tap to mute.")
        "media" -> text("Shows what is playing, with previous, play/pause and next. One cell: tap the art to play or pause.")
        "monitor" -> text("Shows CPU and RAM use, plus GPU and disk when the widget is tall enough.")
        "mixer" -> {
            // List the apps playing audio on that PC while the editor is open.
            DisposableEffect(target.id) {
                val release = vm.holdLive(target.id, stats = false, mixer = true)
                onDispose { release() }
            }
            val mixer by target.mixer.collectAsStateWithLifecycle()
            Text("App", style = MaterialTheme.typography.labelMedium, color = p.textDim)
            Spacer(Modifier.height(8.dp))
            if (mixer.sessions.isEmpty()) {
                text("No app is playing audio on ${target.server.name} right now. Start it, or type its name below.")
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    mixer.sessions.forEach { s ->
                        Chip(s.name, selected = w.target == s.id || (w.target == null && w.name.equals(s.name, ignoreCase = true)), onClick = {
                            onApp(s.id, s.name)
                        })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            DeckTextField(
                value = w.name ?: "",
                onValueChange = { onApp(null, it.take(64).ifBlank { null }) },
                placeholder = "App name, e.g. Discord",
            )
            Spacer(Modifier.height(6.dp))
            Text("The app is found by name again after it restarts.", style = MaterialTheme.typography.bodySmall, color = p.textFaint)
        }
    }
}

/** Tap a cell to make the item that many rows and columns (up to 4 x 4). */
@Composable
private fun SizePicker(cols: Int, rows: Int, onPick: (Int, Int) -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (r in 0 until MAX_SPAN) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (c in 0 until MAX_SPAN) {
                        val on = r < rows && c < cols
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (on) p.accent else p.surfaceHigh)
                                .clickable { onPick(c + 1, r + 1) }
                                .semantics { contentDescription = "${r + 1} by ${c + 1}" },
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text("$rows × $cols", style = MaterialTheme.typography.titleMedium, color = p.text)
            Text("rows × columns", style = MaterialTheme.typography.bodySmall, color = p.textDim)
            Spacer(Modifier.height(8.dp))
            Text(
                "Items wider than the screen's columns are narrowed to fit.",
                style = MaterialTheme.typography.bodySmall, color = p.textFaint,
            )
        }
    }
}

@Composable
private fun EditorSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.textDim)
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = editorSwitchColors(),
        )
    }
}

/** Same switch look as Settings: the "off" thumb stays visible on the dark track. */
@Composable
private fun editorSwitchColors(): androidx.compose.material3.SwitchColors {
    val p = LocalPalette.current
    return SwitchDefaults.colors(
        checkedThumbColor = p.onAccent,
        checkedTrackColor = p.accent,
        uncheckedThumbColor = p.textDim,
        uncheckedTrackColor = p.surfaceHigh,
        uncheckedBorderColor = p.outline,
    )
}

/** Action type chips plus that action's settings, for a toggle's second action or a long press. */
@Composable
private fun ActionPicker(
    title: String,
    action: DeckAction,
    target: DeckClient,
    os: String,
    onPickApp: () -> Unit,
    onChange: (DeckAction) -> Unit,
) {
    val p = LocalPalette.current
    Text(title, style = MaterialTheme.typography.labelMedium, color = p.textDim)
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
        items(STEP_TYPES) { t ->
            Chip(t.label, selected = action.type == t.type, icon = t.icon, onClick = {
                if (action.type != t.type) onChange(defaultAction(t.type))
            })
        }
    }
    Spacer(Modifier.height(10.dp))
    DeckCard(Modifier.fillMaxWidth()) {
        ActionConfig(action, target, os, onPickApp = onPickApp, onChange = { a, _ -> onChange(a) })
    }
}
