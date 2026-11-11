package dev.menggu.androiddeck.ui.main

import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.data.ConnState
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.ui.audio.AudioScreen
import dev.menggu.androiddeck.ui.components.StatusDot
import dev.menggu.androiddeck.ui.deck.ButtonEditor
import dev.menggu.androiddeck.ui.deck.DeckScreen
import dev.menggu.androiddeck.ui.deck.EditorRequest
import dev.menggu.androiddeck.ui.monitor.MonitorScreen
import dev.menggu.androiddeck.ui.settings.SettingsSheet
import dev.menggu.androiddeck.ui.theme.LocalPalette
import dev.menggu.androiddeck.ui.touch.TouchScreen
import kotlinx.coroutines.launch

/** Swipe order. The trackpad comes last: it takes every touch, so swiping out of it is harder. */
enum class Tab(val label: String, val icon: ImageVector) {
    Deck("Deck", Icons.Rounded.GridView),
    Audio("Audio", Icons.AutoMirrored.Rounded.VolumeUp),
    System("System", Icons.Rounded.Insights),
    Touch("Touch", Icons.Rounded.Mouse),
}

@Composable
fun MainScreen(vm: DeckViewModel, onOpenDevices: () -> Unit, covered: Boolean = false) {
    val p = LocalPalette.current
    val client by vm.selected.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Screens are pages: swipe between them (the deck's own pages come first, then Audio, System, Touch).
    val pager = rememberPagerState { Tab.entries.size }
    val tab = Tab.entries[pager.currentPage]
    var editMode by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tab) {
        if (tab != Tab.Deck) editMode = false
    }
    // Live streams follow the screen that is actually showing (not one mid-swipe or covered).
    val settled = Tab.entries[pager.settledPage]
    LaunchedEffect(settled, covered) {
        vm.setMonitorVisible(settled == Tab.System && !covered)
        vm.setAudioVisible(settled == Tab.Audio && !covered)
    }
    LaunchedEffect(settings.lockDeck) { if (settings.lockDeck) editMode = false }
    // Back: leave edit mode, then return to the deck, before leaving the app.
    BackHandler(enabled = editMode || pager.currentPage != 0) {
        if (editMode) editMode = false else scope.launch { pager.animateScrollToPage(0) }
    }
    // First run: nudge the pager so the swipe navigation is discovered.
    var swipeHint by remember { mutableStateOf(false) }
    LaunchedEffect(client?.id) {
        if (client != null && !vm.settings.value.swipeHintShown) {
            delay(900)
            swipeHint = true
            runCatching {
                pager.animateScrollBy(160f)
                pager.animateScrollBy(-160f)
            }
            vm.updateSettings { it.copy(swipeHintShown = true) }
            delay(2800)
            swipeHint = false
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        // Equal side margins: clears the camera cutout (left or right in landscape) and a side
        // navigation bar, without pushing the content off the screen's center.
        val side = symmetricSideInset()
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = side),
        ) {
            TopBar(
                vm = vm,
                current = client,
                pager = pager,
                showEdit = tab == Tab.Deck && !settings.lockDeck,
                locked = settings.lockDeck,
                onToggleLock = { vm.updateSettings { it.copy(lockDeck = !it.lockDeck) } },
                editMode = editMode,
                onToggleEdit = { editMode = !editMode },
                onSettings = { showSettings = true },
                onOpenDevices = onOpenDevices,
            )
            val c = client
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (c != null) {
                    key(c.id) {
                        HorizontalPager(
                            state = pager,
                            userScrollEnabled = !editMode,
                            key = { Tab.entries[it] },
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            when (Tab.entries[page]) {
                                Tab.Deck -> DeckScreen(vm, c, editMode, onEdit = { editor = it })
                                Tab.Audio -> AudioScreen(vm, c)
                                Tab.Touch -> TouchScreen(vm, c)
                                Tab.System -> MonitorScreen(vm, c)
                            }
                        }
                    }
                    // Over the content, so a disconnect never resizes the deck.
                    ConnectionBanner(vm, c, onOpenDevices, Modifier.align(Alignment.TopCenter))
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = swipeHint,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                ) {
                    Text(
                        "Swipe left and right for Audio, System and Touch",
                        style = MaterialTheme.typography.labelLarge,
                        color = p.text,
                        modifier = Modifier.clip(CircleShape).background(p.surfaceHigher).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom)))
        }

        val c = client
        val req = editor
        if (c != null && req != null) {
            val profile by c.profile.collectAsStateWithLifecycle()
            ButtonEditor(
                vm = vm,
                owner = c,
                request = req,
                pages = profile?.pages ?: emptyList(),
                onSave = { button, location, pinned ->
                    vm.saveButton(button, location)
                    if (!vm.setShortcut(button, c.id, pinned)) {
                        vm.message("The widget is full. Remove a button from it first.")
                    }
                    editor = null
                },
                onDelete = {
                    vm.deleteButton(it.id)
                    editor = null
                },
                onClose = { editor = null },
                onDuplicate = {
                    vm.duplicateButton(it.id)
                    vm.message("Duplicated")
                    editor = null
                },
            )
        }
    }

    if (showSettings) {
        SettingsSheet(vm, client, onDismiss = { showSettings = false }, onOpenDevices = {
            showSettings = false
            onOpenDevices()
        })
    }
}

/**
 * Compact top bar: the current PC as one chip (tap to switch), and a single overflow menu
 * for Edit / Settings / Devices, so the deck keeps the vertical space on short screens.
 */
@Composable
private fun TopBar(
    vm: DeckViewModel,
    current: DeckClient?,
    pager: PagerState,
    showEdit: Boolean,
    locked: Boolean,
    onToggleLock: () -> Unit,
    editMode: Boolean,
    onToggleEdit: () -> Unit,
    onSettings: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    val p = LocalPalette.current
    val servers by vm.servers.collectAsStateWithLifecycle()
    val clients by vm.clients.collectAsStateWithLifecycle()
    val newServers by vm.newServers.collectAsStateWithLifecycle()
    val state = current?.state?.collectAsStateWithLifecycle()?.value
    var pcMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Wide (landscape): page indicator centered. Narrow: next to the menu, so the PC name keeps its room.
        val centered = maxWidth >= 560.dp
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                // Swiping the bar also changes screens: an exit from the trackpad, which takes every touch.
                .scrollable(
                    state = pager,
                    orientation = Orientation.Horizontal,
                    enabled = !editMode,
                    reverseDirection = true,
                    flingBehavior = PagerDefaults.flingBehavior(pager),
                )
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(p.surfaceHigh)
                        .clickable { pcMenu = true }
                        .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(state)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        current?.server?.name ?: "No PC",
                        style = MaterialTheme.typography.titleSmall,
                        color = p.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Rounded.KeyboardArrowDown, "Switch PC", tint = p.textDim, modifier = Modifier.size(20.dp))
                    if (newServers.isNotEmpty()) {
                        Box(Modifier.padding(start = 2.dp).size(8.dp).clip(CircleShape).background(p.accent))
                    }
                }
                DropdownMenu(
                    expanded = pcMenu,
                    onDismissRequest = { pcMenu = false },
                    containerColor = p.surfaceHigh,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    servers.forEach { s ->
                        val st = clients[s.id]?.state?.collectAsStateWithLifecycle()?.value
                        DropdownMenuItem(
                            text = { Text(s.name, style = MaterialTheme.typography.labelLarge, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { StatusDot(st) },
                            trailingIcon = { if (s.id == current?.id) Icon(Icons.Rounded.Check, "Current", tint = p.textDim) },
                            onClick = {
                                vm.select(s.id)
                                pcMenu = false
                            },
                        )
                    }
                    HorizontalDivider(color = p.outline)
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (newServers.isEmpty()) "Add or manage PCs" else "Add PC · ${newServers.size} new",
                                style = MaterialTheme.typography.labelLarge,
                                color = p.text,
                            )
                        },
                        leadingIcon = { Icon(Icons.Rounded.Add, null, tint = p.textDim) },
                        onClick = {
                            pcMenu = false
                            onOpenDevices()
                        },
                    )
                }
            }
            // Where you are among the swipeable screens; tap an icon to jump there.
            if (!editMode) PageIndicator(pager, Modifier.padding(horizontal = if (centered) 0.dp else 4.dp))
            Box(if (centered) Modifier.weight(1f) else Modifier, contentAlignment = Alignment.CenterEnd) {
                if (editMode) {
                    Row(
                        Modifier
                            .padding(end = 4.dp)
                            .heightIn(min = 40.dp)
                            .clip(CircleShape)
                            .background(p.accent)
                            .clickable(onClick = onToggleEdit)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Check, null, tint = p.onAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Done", style = MaterialTheme.typography.labelLarge, color = p.onAccent)
                    }
                } else {
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = p.text) }
                        DropdownMenu(
                            expanded = moreMenu,
                            onDismissRequest = { moreMenu = false },
                            containerColor = p.surfaceHigh,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            if (showEdit) {
                                DropdownMenuItem(
                                    text = { Text("Edit deck", style = MaterialTheme.typography.labelLarge, color = p.text) },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = p.textDim) },
                                    onClick = {
                                        moreMenu = false
                                        onToggleEdit()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(if (locked) "Unlock deck" else "Lock deck", style = MaterialTheme.typography.labelLarge, color = p.text)
                                },
                                leadingIcon = { Icon(if (locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null, tint = p.textDim) },
                                onClick = {
                                    moreMenu = false
                                    onToggleLock()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings", style = MaterialTheme.typography.labelLarge, color = p.text) },
                                leadingIcon = { Icon(Icons.Rounded.Tune, null, tint = p.textDim) },
                                onClick = {
                                    moreMenu = false
                                    onSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Devices", style = MaterialTheme.typography.labelLarge, color = p.text) },
                                leadingIcon = { Icon(Icons.Rounded.Devices, null, tint = p.textDim) },
                                onClick = {
                                    moreMenu = false
                                    onOpenDevices()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(pager: PagerState, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Tab.entries.forEachIndexed { i, t ->
            val sel = pager.currentPage == i
            val bg by animateColorAsState(if (sel) p.accent else Color.Transparent, label = "page")
            val fg by animateColorAsState(if (sel) p.onAccent else p.textDim, label = "pageIcon")
            // 40 x 48 dp touch target around a 34 x 28 dp pill.
            Box(
                Modifier
                    .size(width = 40.dp, height = 48.dp)
                    .selectable(selected = sel, role = Role.Tab) { scope.launch { pager.animateScrollToPage(i) } },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 34.dp, height = 28.dp).clip(CircleShape).background(bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(t.icon, t.label, tint = fg, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

/** max(left, right) of the bars and cutout, so both sides get the same margin. */
@Composable
private fun symmetricSideInset(): Dp {
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    return with(density) { maxOf(insets.getLeft(density, dir), insets.getRight(density, dir)).toDp() }
}

@Composable
private fun ConnectionBanner(vm: DeckViewModel, client: DeckClient, onOpenDevices: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val state by client.state.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val canWake = state is ConnState.Offline && servers.find { it.id == client.id }?.mac != null
    AnimatedVisibility(
        visible = state != ConnState.Connected && state != ConnState.Idle,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        val unauthorized = state == ConnState.Unauthorized
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (unauthorized) p.danger.copy(alpha = 0.15f) else p.surface)
                .clickable(enabled = unauthorized, onClick = onOpenDevices)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!unauthorized) {
                CircularProgressIndicator(color = p.textDim, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                when (state) {
                    ConnState.Unauthorized -> "${client.server.name} no longer accepts this phone. Tap to pair again."
                    ConnState.Connecting -> "Connecting to ${client.server.name}…"
                    else -> "${client.server.name} is offline — retrying automatically"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (unauthorized) p.danger else p.textDim,
                modifier = Modifier.weight(1f),
            )
            if (canWake) {
                Text(
                    "Wake",
                    style = MaterialTheme.typography.labelLarge,
                    color = p.onAccent,
                    modifier = Modifier.padding(start = 8.dp).clip(CircleShape).background(p.accent)
                        .clickable { vm.wake(client.id) }.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}
