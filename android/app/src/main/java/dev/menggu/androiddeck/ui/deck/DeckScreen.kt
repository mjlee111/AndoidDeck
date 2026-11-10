package dev.menggu.androiddeck.ui.deck

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.Location
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
                emptyHint = "This folder is empty.\nTap the pencil above, then + to add buttons.",
            )
            return@Column
        }
        PageTabs(
            pages = pages,
            current = pager.currentPage.coerceAtMost(pages.lastIndex),
            editMode = editMode,
            onSelect = { scope.launch { pager.animateScrollToPage(it) } },
            onPageOptions = { pageDialog = it },
            onAddPage = { scrollToPage = vm.addPage("Page ${pages.size + 1}") },
        )
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
            userScrollEnabled = !dragging,
            key = { pages.getOrNull(it)?.id ?: it },
        ) { index ->
            val page = pages.getOrNull(index) ?: return@HorizontalPager
            DeckGrid(
                key = page.id,
                buttons = page.buttons,
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
                emptyHint = "This page is empty.\nTap the pencil above to add buttons.",
            )
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
    return when {
        a.type == "mic" && a.op in setOf(null, "toggle", "mute", "unmute") -> {
            val mic by target.mic.collectAsStateWithLifecycle()
            when {
                !mic.available -> null
                mic.muted == true -> TileLive(symbol = if (auto) "mic_off" else null, dot = p.danger)
                else -> TileLive(symbol = if (auto) "mic" else null, dot = p.ok)
            }
        }
        a.type == "volume" && a.op == "mute" -> {
            val vol by target.volume.collectAsStateWithLifecycle()
            if (vol.muted == true) TileLive(dot = p.danger) else null
        }
        a.type == "media" && (a.key ?: "play_pause") == "play_pause" -> {
            val media by target.media.collectAsStateWithLifecycle()
            if (media.available && media.playing) TileLive(symbol = if (auto) "pause" else null, dot = p.ok) else null
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
        Modifier.fillMaxWidth().height(48.dp),
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
    onLongEdit: (DeckButton) -> Unit = { onEdit(it) },
) {
    val p = LocalPalette.current
    val view = LocalView.current
    val servers by vm.servers.collectAsStateWithLifecycle()
    val drag = remember { DragState() }
    var order by remember(key) { mutableStateOf(buttons) }
    LaunchedEffect(buttons) {
        if (drag.draggingId == null) order = buttons
    }
    val minTile = when (settings.tileSize) {
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

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minTile),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
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
                },
        ) {
            itemsIndexed(order, key = { _, b -> b.id }) { _, b ->
                val isDragging = drag.draggingId == b.id
                val target = vm.targetOf(b.action, client)
                val otherPc = b.action.device?.takeIf { it != client.id }?.let { d -> servers.find { it.id == d } }
                DeckTile(
                    button = b,
                    iconUrl = iconUrl,
                    showLabel = settings.showLabels,
                    editMode = editMode,
                    flash = flashes[b.id],
                    live = liveFor(b, target),
                    deviceBadge = otherPc?.let { osIcon(it.os) },
                    onClick = {
                        if (editMode) onEdit(b) else {
                            haptic()
                            onPress(b)
                        }
                    },
                    onLongClick = if (editMode) null else ({ onLongEdit(b) }),
                    modifier = Modifier
                        .then(if (isDragging) Modifier.zIndex(1f) else Modifier.animateItem())
                        .onGloballyPositioned { c ->
                            val r = Rect(c.positionInRoot(), c.size.toSize())
                            if (drag.slots[b.id] != r) drag.slots[b.id] = r
                        }
                        .graphicsLayer {
                            if (isDragging) {
                                val slot = drag.slots[b.id]
                                if (slot != null) {
                                    translationX = drag.finger.x - drag.grab.x - slot.left
                                    translationY = drag.finger.y - drag.grab.y - slot.top
                                }
                                scaleX = 1.08f
                                scaleY = 1.08f
                                alpha = 0.92f
                            }
                        },
                )
            }
            if (editMode) {
                item(key = "__add") {
                    AddTile(onClick = { onEdit(null) }, modifier = Modifier.animateItem())
                }
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
