package dev.menggu.androiddeck.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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

enum class Tab(val label: String, val icon: ImageVector) {
    Deck("Deck", Icons.Rounded.GridView),
    Audio("Audio", Icons.AutoMirrored.Rounded.VolumeUp),
    Touch("Touch", Icons.Rounded.Mouse),
    System("System", Icons.Rounded.Insights),
}

@Composable
fun MainScreen(vm: DeckViewModel, onOpenDevices: () -> Unit) {
    val p = LocalPalette.current
    val client by vm.selected.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Deck) }
    var editMode by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorRequest?>(null) }

    LaunchedEffect(tab) {
        vm.setMonitorVisible(tab == Tab.System)
        if (tab != Tab.Deck) editMode = false
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            TopBar(
                vm = vm,
                current = client,
                showEdit = tab == Tab.Deck,
                editMode = editMode,
                onToggleEdit = { editMode = !editMode },
                onSettings = { showSettings = true },
                onOpenDevices = onOpenDevices,
            )
            client?.let { ConnectionBanner(vm, it, onOpenDevices) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val c = client
                if (c != null) {
                    key(c.id) {
                        when (tab) {
                            Tab.Deck -> DeckScreen(vm, c, editMode, onEdit = { editor = it })
                            Tab.Audio -> AudioScreen(vm, c)
                            Tab.Touch -> TouchScreen(vm, c)
                            Tab.System -> MonitorScreen(vm, c)
                        }
                    }
                }
            }
            BottomNav(tab, onSelect = { tab = it })
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
    showEdit: Boolean,
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

    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
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
        if (editMode) {
            Row(
                Modifier
                    .padding(end = 4.dp)
                    .clip(CircleShape)
                    .background(p.accent)
                    .clickable(onClick = onToggleEdit)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

@Composable
private fun ConnectionBanner(vm: DeckViewModel, client: DeckClient, onOpenDevices: () -> Unit) {
    val p = LocalPalette.current
    val state by client.state.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val canWake = state is ConnState.Offline && servers.find { it.id == client.id }?.mac != null
    AnimatedVisibility(
        visible = state != ConnState.Connected && state != ConnState.Idle,
        enter = expandVertically(),
        exit = shrinkVertically(),
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
                        .clickable { vm.wake(client.id) }.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun BottomNav(tab: Tab, onSelect: (Tab) -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(CircleShape).background(p.surface).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { t ->
                val sel = t == tab
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (sel) p.accent else p.surface)
                        .clickable { onSelect(t) }
                        .padding(horizontal = if (sel) 18.dp else 16.dp, vertical = 10.dp)
                        .animateContentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, t.label, tint = if (sel) p.onAccent else p.textDim, modifier = Modifier.size(20.dp))
                    if (sel) {
                        Spacer(Modifier.width(8.dp))
                        Text(t.label, style = MaterialTheme.typography.labelLarge, color = p.onAccent)
                    }
                }
            }
        }
    }
}
