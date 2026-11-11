package dev.menggu.androiddeck.ui.deck

import dev.menggu.androiddeck.ui.components.ACCENT_COLOR
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.Location
import dev.menggu.androiddeck.data.ConnState
import dev.menggu.androiddeck.data.DeckButton
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.data.DeckPage
import dev.menggu.androiddeck.data.Settings
import dev.menggu.androiddeck.ui.components.Chip
import dev.menggu.androiddeck.ui.components.DeckTextField
import dev.menggu.androiddeck.ui.components.PrimaryButton
import dev.menggu.androiddeck.ui.components.SecondaryButton
import dev.menggu.androiddeck.ui.components.osIcon
import dev.menggu.androiddeck.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/** Request to open the button editor. button == null means "new button at location". */
data class EditorRequest(val location: Location, val button: DeckButton?)

@Composable
fun DeckScreen(
    vm: DeckViewModel,
    client: DeckClient,
    editMode: Boolean,
    onEdit: (EditorRequest) -> Unit,
) {
    val profile by client.profile.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val flashes by vm.flashes.collectAsStateWithLifecycle()
    val view = LocalView.current
    // A distinct buzz when an action fails, so a press can be judged without looking.
    val failed = remember { mutableSetOf<String>() }
    LaunchedEffect(flashes) {
        val now = flashes.filterValues { !it }.keys
        if (settings.haptics && (now - failed).isNotEmpty()) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
            )
        }
        failed.clear()
        failed.addAll(now)
    }
    val servers by vm.servers.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    val current = profile
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = p.textDim, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        return
    }
    val pages = current.pages
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    var pageDialog by remember { mutableStateOf<DeckPage?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var scrollToPage by remember { mutableStateOf<String?>(null) }
    var openFolder by remember { mutableStateOf<Pair<String, String>?>(null) } // pageId to folderId

    LaunchedEffect(scrollToPage, pages.size) {
        val target = scrollToPage ?: return@LaunchedEffect
        val idx = pages.indexOfFirst { it.id == target }
        if (idx >= 0) {
            pager.animateScrollToPage(idx)
            scrollToPage = null
        }
    }
    LaunchedEffect(pager.currentPage) {
        if (openFolder?.first != pages.getOrNull(pager.currentPage)?.id) openFolder = null
    }

    val iconUrl: IconUrl = { appId, device -> (vm.client(device) ?: client).iconUrl(appId) }
    val folder = openFolder?.let { (pageId, folderId) ->
        pages.find { it.id == pageId }?.buttons?.find { it.id == folderId && it.action.type == "folder" }
    }
    if (openFolder != null && folder == null) openFolder = null
    BackHandler(enabled = folder != null) { openFolder = null }
    // Back from a later page returns to the first one before it leaves the deck.
    BackHandler(enabled = folder == null && !editMode && pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(0) }
    }

    // One grid for every page (plus a reserved "+" cell), so sizes never change between pages or
    // when edit mode starts.
    val basis = remember(pages) { pages.map { pg -> pg.buttons.map { Span(it.cols, it.rows) } + Span(1, 1) } }

    // Swiping between pages briefly names the page, since the tabs are hidden outside edit mode.
    var toast by remember { mutableStateOf<String?>(null) }
    var lastSettled by remember { mutableIntStateOf(pager.settledPage) }
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage != lastSettled) {
            lastSettled = pager.settledPage
            if (!editMode && pages.size > 1) {
                toast = "${pages.getOrNull(pager.settledPage)?.name ?: ""} · ${pager.settledPage + 1}/${pages.size}"
                delay(1300)
                toast = null
            }
        }
    }

    fun press(b: DeckButton, pageId: String) {
        if (b.action.type == "folder") openFolder = pageId to b.id else vm.press(b, client)
    }

    Column(Modifier.fillMaxSize()) {
        if (folder != null) {
            val pageId = openFolder!!.first
            FolderHeader(
                folder = folder,
                editMode = editMode,
                onBack = { openFolder = null },
                onEditFolder = { onEdit(EditorRequest(Location(pageId), folder)) },
            )
            DeckGrid(
                key = folder.id,
                buttons = folder.children.orEmpty(),
                basis = listOf(folder.children.orEmpty().map { Span(it.cols, it.rows) } + Span(1, 1)),
                reserveTop = 0.dp,
                vm = vm,
                client = client,
                settings = settings,
                editMode = editMode,
                flashes = flashes,
                iconUrl = iconUrl,
                onPress = { press(it, pageId) },
                onEdit = { onEdit(EditorRequest(Location(pageId, folder.id), it)) },
                onReorder = { vm.reorder(Location(pageId, folder.id), it) },
                onDragging = { dragging = it },
                emptyHint = "This folder is empty.\nTap ⋮ → Edit deck, then + to add buttons.",
            )
            return@Column
        }
        // Pages are swiped; their tabs only appear in edit mode, where pages are added and managed.
        if (editMode) {
            PageTabs(
                pages = pages,
                current = pager.currentPage.coerceAtMost(pages.lastIndex),
                editMode = true,
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                onPageOptions = { pageDialog = it },
                onAddPage = { scrollToPage = vm.addPage("Page ${pages.size + 1}") },
            )
        }
        Box(Modifier.weight(1f)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !dragging,
                key = { pages.getOrNull(it)?.id ?: it },
            ) { index ->
                val page = pages.getOrNull(index) ?: return@HorizontalPager
                DeckGrid(
                    key = page.id,
                    buttons = page.buttons,
                    basis = basis,
                    // Outside edit mode, keep the room the page tabs take in edit mode.
                    reserveTop = if (editMode) 0.dp else PAGE_TABS_HEIGHT,
                    vm = vm,
                    client = client,
                    settings = settings,
                    editMode = editMode,
                    flashes = flashes,
                    iconUrl = iconUrl,
                    onPress = { press(it, page.id) },
                    onEdit = { b ->
                        // In edit mode a folder opens so its contents can be edited; long-press edits it.
                        if (b != null && b.action.type == "folder" && editMode) openFolder = page.id to b.id
                        else onEdit(EditorRequest(Location(page.id), b))
                    },
                    onLongEdit = { onEdit(EditorRequest(Location(page.id), it)) },
                    onReorder = { vm.reorder(Location(page.id), it) },
                    onDragging = { dragging = it },
                    emptyHint = "This page is empty.\nTap ⋮ → Edit deck to add buttons.",
                )
            }
            if (!editMode && pages.size > 1) {
                // Sits in the grid's bottom margin, so it takes no space from the buttons.
                PageDots(pages.size, pager.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp))
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = toast != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            ) {
                Text(
                    toast ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = p.text,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(p.surfaceHigher)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    pageDialog?.let { page ->
        PageDialog(
            page = page,
            canDelete = pages.size > 1,
            index = pages.indexOfFirst { it.id == page.id },
            count = pages.size,
            otherPcs = servers.filter { it.id != client.id }.map { it.id to it.name },
            onDismiss = { pageDialog = null },
            onRename = { vm.renamePage(page.id, it) },
            onMove = { delta ->
                vm.movePage(page.id, delta)
                scrollToPage = page.id
            },
            onCopyTo = { vm.copyPageTo(page.id, it) },
            onDelete = {
                vm.deletePage(page.id)
                pageDialog = null
            },
        )
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val sel = i == current
            val w by animateDpAsState(if (sel) 16.dp else 6.dp, label = "dot")
            Box(Modifier.size(width = w, height = 6.dp).clip(CircleShape).background(if (sel) p.text else p.textFaint))
        }
    }
}

@Composable
private fun FolderHeader(folder: DeckButton, editMode: Boolean, onBack: () -> Unit, onEditFolder: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close folder", tint = p.text) }
        Text(
            folder.label.ifBlank { "Folder" },
            style = MaterialTheme.typography.titleMedium,
            color = p.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (editMode) {
            IconButton(onClick = onEditFolder) { Icon(Icons.Rounded.Edit, "Edit folder", tint = p.textDim) }
        }
    }
}

/** Live state for toggle-style buttons, read from the PC the button targets. */
@Composable
private fun liveFor(button: DeckButton, target: DeckClient?): TileLive? {
    val p = LocalPalette.current
    if (target == null) return null
    val a = button.action
    val auto = button.icon.kind == "none"
    // Swap the glyph for the default or plain mic icon; other custom icons stay as chosen.
    val micGlyph = auto || (button.icon.kind == "symbol" && button.icon.value in setOf("mic", "mic_off"))
    return when {
        a.type == "mic" && a.op in setOf(null, "toggle", "mute", "unmute") -> {
            val mic by target.mic.collectAsStateWithLifecycle()
            when {
                !mic.available -> null
                mic.muted == true ->
                    TileLive(symbol = if (micGlyph) "mic_off" else null, badge = "MUTED", tone = p.danger, state = "Muted")
                else -> TileLive(symbol = if (micGlyph) "mic" else null, dot = p.ok, state = "Live")
            }
        }
        a.type == "volume" && a.op == "mute" -> {
            val vol by target.volume.collectAsStateWithLifecycle()
            if (vol.muted == true) TileLive(badge = "MUTED", tone = p.danger, state = "Muted") else null
        }
        a.type == "media" && (a.key ?: "play_pause") == "play_pause" -> {
            val media by target.media.collectAsStateWithLifecycle()
            if (media.available && media.playing) {
                TileLive(symbol = if (auto) "pause" else null, dot = p.ok, state = "Playing")
            } else null
        }
        else -> null
    }
}

@Composable
private fun PageTabs(
    pages: List<DeckPage>,
    current: Int,
    editMode: Boolean,
    onSelect: (Int) -> Unit,
    onPageOptions: (DeckPage) -> Unit,
    onAddPage: () -> Unit,
) {
    val p = LocalPalette.current
    if (pages.size <= 1 && !editMode) {
        Spacer(Modifier.height(4.dp))
        return
    }
    LazyRow(
        Modifier.fillMaxWidth().height(PAGE_TABS_HEIGHT),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(pages, key = { _, it -> it.id }) { i, page ->
            val sel = i == current
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (sel) p.surfaceHigh else p.bg)
                    .clickable { if (sel && editMode) onPageOptions(page) else onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    page.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sel) p.text else p.textDim,
                    maxLines = 1,
                )
                if (sel && editMode) {
                    Spacer(Modifier.size(6.dp))
                    Text("•••", style = MaterialTheme.typography.labelSmall, color = p.textDim)
                }
            }
        }
        if (editMode) {
            item(key = "__add_page") {
                Row(
                    Modifier.clip(CircleShape).clickable(onClick = onAddPage).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, tint = p.textDim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Page", style = MaterialTheme.typography.labelLarge, color = p.textDim)
                }
            }
        }
    }
}

private class DragState {
    var gridCoords: LayoutCoordinates? = null
    val slots = mutableStateMapOf<String, Rect>()
    var draggingId by mutableStateOf<String?>(null)
    var finger by mutableStateOf(Offset.Zero)
    var grab: Offset = Offset.Zero
}

@Composable
private fun DeckGrid(
    key: String,
    buttons: List<DeckButton>,
    vm: DeckViewModel,
    client: DeckClient,
    settings: Settings,
    editMode: Boolean,
    flashes: Map<String, Boolean>,
    iconUrl: IconUrl,
    onPress: (DeckButton) -> Unit,
    onEdit: (DeckButton?) -> Unit,
    onReorder: (List<DeckButton>) -> Unit,
    onDragging: (Boolean) -> Unit,
    emptyHint: String,
    /** Spans of every page of this deck (with a reserved "+" cell): the shared grid basis. */
    basis: List<List<Span>>,
    /** Height kept free above the grid when sizing it (the edit-mode page tabs). */
    reserveTop: Dp,
    onLongEdit: (DeckButton) -> Unit = { onEdit(it) },
) {
    val p = LocalPalette.current
    val view = LocalView.current
    val servers by vm.servers.collectAsStateWithLifecycle()
    val toggled by vm.toggled.collectAsStateWithLifecycle()
    val drag = remember { DragState() }
    var order by remember(key) { mutableStateOf(buttons) }
    LaunchedEffect(buttons) {
        if (drag.draggingId == null) order = buttons
    }
    val fixedTile = when (settings.tileSize) {
        0 -> 76.dp
        2 -> 128.dp
        else -> 100.dp
    }

    fun haptic() {
        if (settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun endDrag() {
        if (drag.draggingId != null) {
            drag.draggingId = null
            onDragging(false)
            if (order.map { it.id } != buttons.map { it.id }) onReorder(order)
        }
    }

    // Long-press drag to reorder (edit mode); shared by both grid layouts.
    val dragInput = Modifier
        .onGloballyPositioned { drag.gridCoords = it }
        .pointerInput(editMode, key) {
            if (!editMode) return@pointerInput
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    val coords = drag.gridCoords ?: return@detectDragGesturesAfterLongPress
                    val root = coords.localToRoot(local)
                    val hit = order.firstOrNull { b -> drag.slots[b.id]?.contains(root) == true }
                        ?: return@detectDragGesturesAfterLongPress
                    drag.draggingId = hit.id
                    drag.finger = root
                    drag.grab = root - (drag.slots[hit.id]?.topLeft ?: root)
                    onDragging(true)
                    if (settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                },
                onDrag = { change, amount ->
                    val id = drag.draggingId ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    drag.finger += amount
                    val target = order.firstOrNull { b ->
                        b.id != id && drag.slots[b.id]?.let { r ->
                            r.deflate(r.width * 0.18f).contains(drag.finger)
                        } == true
                    }
                    if (target != null) {
                        val from = order.indexOfFirst { it.id == id }
                        val to = order.indexOfFirst { it.id == target.id }
                        if (from >= 0 && to >= 0) {
                            order = order.toMutableList().apply { add(to, removeAt(from)) }
                            haptic()
                        }
                    }
                },
                onDragEnd = { endDrag() },
                onDragCancel = { endDrag() },
            )
        }

    @Composable
    fun Tile(b: DeckButton, modifier: Modifier) {
        val isDragging = drag.draggingId == b.id
        val target = vm.targetOf(b.action, client)
        val targetState = target?.state?.collectAsStateWithLifecycle()?.value
        val needsPc = b.action.type !in setOf("none", "folder", "wake")
        val otherPc = b.action.device?.takeIf { it != client.id }?.let { d -> servers.find { it.id == d } }
        val slot = Modifier
            .onGloballyPositioned { c ->
                val r = Rect(c.positionInRoot(), c.size.toSize())
                if (drag.slots[b.id] != r) drag.slots[b.id] = r
            }
            .graphicsLayer {
                if (isDragging) {
                    val s = drag.slots[b.id]
                    if (s != null) {
                        translationX = drag.finger.x - drag.grab.x - s.left
                        translationY = drag.finger.y - drag.grab.y - s.top
                    }
                    scaleX = 1.05f
                    scaleY = 1.05f
                    alpha = 0.92f
                }
            }
        if (b.widget != null) {
            Box(modifier.then(slot)) {
                WidgetTile(b, target ?: client, vm, editMode, settings.haptics, Modifier.fillMaxSize())
                if (editMode) {
                    // Widgets are edited like buttons: tap to edit, long-press to drag.
                    Box(Modifier.matchParentSize().clip(TileShape).clickable { onEdit(b) })
                    EditBadge(Modifier.align(Alignment.TopEnd))
                }
            }
            return
        }
        val toggle = b.toggle
        val on = toggle != null && b.id in toggled
        // A two-state button that is on shows its "on" label and lights up (button color or highlight).
        val shown = if (on) b.copy(label = toggle!!.label ?: b.label, color = toggle.color ?: b.color ?: ACCENT_COLOR) else b
        val pushToTalk = !editMode && b.action.type == "hotkey" && b.action.hold == true
        val long = b.longAction
        DeckTile(
            button = shown,
            iconUrl = iconUrl,
            showLabel = settings.showLabels,
            editMode = editMode,
            flash = flashes[b.id],
            live = liveFor(b, target) ?: if (on) TileLive(badge = "ON", state = "On") else null,
            deviceBadge = otherPc?.let { osIcon(it.os) },
            offline = needsPc && targetState != ConnState.Connected,
            onClick = {
                if (editMode) onEdit(b) else if (!pushToTalk) {
                    haptic()
                    onPress(b)
                }
            },
            onLongClick = when {
                editMode || pushToTalk -> null
                long != null -> ({
                    if (settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    vm.pressLong(b, client)
                })
                settings.lockDeck -> null
                else -> ({ onLongEdit(b) })
            },
            onLongClickLabel = if (long != null) "Run long-press action" else if (!settings.lockDeck) "Edit" else null,
            onPressChange = if (pushToTalk) ({ down ->
                if (down) haptic()
                vm.hold(b, client, down)
            }) else null,
            modifier = modifier.then(slot),
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(start = GRID_EDGE, end = GRID_EDGE, top = GRID_EDGE / 2, bottom = GRID_EDGE)) {
        // Buttons and widgets take cols x rows cells; the edit-mode "+" takes one.
        val spans = remember(order, editMode) {
            order.map { Span(it.cols, it.rows) } + if (editMode) listOf(Span(1, 1)) else emptyList()
        }
        val fitHeight = (maxHeight - reserveTop).coerceAtLeast(FIT_MIN_TILE)
        val deckFit = remember(maxWidth, fitHeight, basis, settings.autoFit, fixedTile) {
            fitDeck(maxWidth, fitHeight, basis, settings.autoFit, fixedTile)
        }
        val fit = remember(deckFit, spans, maxHeight) { pageFit(spans, deckFit, maxHeight) }
        val cells: @Composable () -> Unit = {
            order.forEach { b ->
                key(b.id) {
                    Box(Modifier.zIndex(if (drag.draggingId == b.id) 1f else 0f), propagateMinConstraints = true) {
                        Tile(b, Modifier)
                    }
                }
            }
            if (editMode) {
                Box(propagateMinConstraints = true) { AddTile(onClick = { onEdit(null) }) }
            }
        }
        if (fit.fits) {
            // Whole page on screen, centered (a short last row is centered too).
            SpanGrid(fit, Modifier.align(Alignment.Center).then(dragInput), cells)
        } else {
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SpanGrid(fit, Modifier.then(dragInput), cells)
                Spacer(Modifier.height(16.dp))
            }
        }
        if (order.isEmpty() && !editMode) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.GridView, null, tint = p.textFaint, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(emptyHint, style = MaterialTheme.typography.bodyMedium, color = p.textDim, textAlign = TextAlign.Center)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageDialog(
    page: DeckPage,
    canDelete: Boolean,
    index: Int,
    count: Int,
    otherPcs: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onMove: (Int) -> Unit,
    onCopyTo: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalPalette.current
    var name by remember(page.id) { mutableStateOf(page.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = {
        if (name.isNotBlank() && name != page.name) onRename(name)
        onDismiss()
    }) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(p.surface).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Page", style = MaterialTheme.typography.titleLarge, color = p.text)
            DeckTextField(name, { name = it.take(32) }, "Page name")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Left", { onMove(-1) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    color = if (index > 0) p.text else p.textFaint)
                SecondaryButton("Right", { onMove(1) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    color = if (index < count - 1) p.text else p.textFaint)
            }
            if (otherPcs.isNotEmpty()) {
                Text("Copy this page to", style = MaterialTheme.typography.labelMedium, color = p.textDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    otherPcs.forEach { (id, pcName) ->
                        Chip(pcName, selected = false, icon = Icons.Rounded.ContentCopy, onClick = { onCopyTo(id) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canDelete) {
                    SecondaryButton(
                        if (confirmDelete) "Tap again to delete" else "Delete",
                        { if (confirmDelete) onDelete() else confirmDelete = true },
                        icon = Icons.Rounded.Delete,
                        color = p.danger,
                    )
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton("Done", {
                    if (name.isNotBlank() && name != page.name) onRename(name)
                    onDismiss()
                })
            }
        }
    }
}

private val GRID_EDGE = 12.dp
private val PAGE_TABS_HEIGHT = 40.dp
