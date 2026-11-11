package dev.menggu.androiddeck.ui.connect

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.data.ConnState
import dev.menggu.androiddeck.data.DEFAULT_PORT
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.data.SavedServer
import dev.menggu.androiddeck.data.shortFingerprint
import dev.menggu.androiddeck.ui.components.MonoStyle
import dev.menggu.androiddeck.ui.components.DeckTextField
import dev.menggu.androiddeck.ui.components.IconBadge
import dev.menggu.androiddeck.ui.components.PrimaryButton
import dev.menggu.androiddeck.ui.components.SecondaryButton
import dev.menggu.androiddeck.ui.components.SectionLabel
import dev.menggu.androiddeck.ui.components.StatusDot
import dev.menggu.androiddeck.ui.components.connLabel
import dev.menggu.androiddeck.ui.components.osIcon
import dev.menggu.androiddeck.ui.components.osLabel
import dev.menggu.androiddeck.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/** [fp] is the certificate fingerprint the PC announced (discovery) or presented (manual add). */
data class PairTarget(val host: String, val port: Int, val name: String, val os: String, val fp: String?)

/**
 * Paired PCs + PCs found on the network. Used as the first-run screen ([onboarding]) and as
 * the device manager opened from the main screen.
 */
@Composable
fun DevicesScreen(vm: DeckViewModel, onboarding: Boolean, onClose: () -> Unit) {
    BackHandler(enabled = !onboarding, onBack = onClose)
    DisposableEffect(Unit) {
        vm.setDevicesScreenOpen(true)
        onDispose { vm.setDevicesScreenOpen(false) }
    }
    val p = LocalPalette.current
    val servers by vm.servers.collectAsStateWithLifecycle()
    val clients by vm.clients.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val found by vm.newServers.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    var pairTarget by remember { mutableStateOf<PairTarget?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var forgetTarget by remember { mutableStateOf<SavedServer?>(null) }
    var autoPrompted by rememberSaveable { mutableStateOf(false) }

    // First run with exactly one PC around: go straight to the PIN prompt.
    LaunchedEffect(found, servers.isEmpty()) {
        if (onboarding && !autoPrompted && servers.isEmpty() && found.size == 1 && pairTarget == null) {
            autoPrompted = true
            val d = found.first()
            pairTarget = PairTarget(d.host, d.info.port, d.info.name, d.info.os, d.info.fp)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(p.bg),
        contentPadding = WindowInsets.safeDrawing.asPaddingValues().let {
            PaddingValues(start = 20.dp, end = 20.dp, top = it.calculateTopPadding() + 8.dp, bottom = it.calculateBottomPadding() + 24.dp)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                if (!onboarding) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose, modifier = Modifier.padding(start = 0.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = p.text)
                        }
                        Text("Devices", style = MaterialTheme.typography.titleLarge, color = p.text)
                    }
                } else {
                    Spacer(Modifier.height(36.dp))
                    Text("AndroidDeck", style = MaterialTheme.typography.headlineLarge, color = p.text)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Control every PC on your network from one phone.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = p.textDim,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        if (servers.isNotEmpty()) {
            item { Box(Modifier.widthIn(max = 560.dp)) { SectionLabel("Paired · ${servers.size}") } }
            items(servers, key = { it.id }) { s ->
                PairedRow(
                    server = s,
                    client = clients[s.id],
                    selected = selected?.id == s.id,
                    onOpen = {
                        val st = clients[s.id]?.state?.value
                        if (st == ConnState.Unauthorized) pairTarget = PairTarget(s.host, s.port, s.name, s.os, s.fp)
                        else {
                            vm.select(s.id)
                            onClose()
                        }
                    },
                    onForget = { forgetTarget = s },
                    onWake = { vm.wake(s.id) },
                )
            }
        }

        item {
            Box(Modifier.widthIn(max = 560.dp)) {
                SectionLabel("On this network") {
                    if (scanning) {
                        CircularProgressIndicator(color = p.textDim, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    } else {
                        Icon(Icons.Rounded.Refresh, "Scan again", tint = p.textDim,
                            modifier = Modifier.size(18.dp).clip(CircleShape).clickable { vm.scanNow() })
                    }
                }
            }
        }
        if (found.isEmpty()) {
            item { SearchingCard(hasPaired = servers.isNotEmpty()) }
        } else {
            items(found, key = { "new-" + it.info.id }) { d ->
                RowCard(onClick = { pairTarget = PairTarget(d.host, d.info.port, d.info.name, d.info.os, d.info.fp) }) {
                    IconBadge(osIcon(d.info.os))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(d.info.name, style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${osLabel(d.info.os)} · ${d.host}", style = MaterialTheme.typography.bodySmall, color = p.textDim)
                    }
                    Text("Pair", style = MaterialTheme.typography.labelLarge, color = p.onAccent,
                        modifier = Modifier.clip(CircleShape).background(p.accent).padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }

        item {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 16.dp)) {
                SecondaryButton("Add by IP address", { showManual = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
                Spacer(Modifier.height(28.dp))
                SetupHint()
            }
        }
    }

    pairTarget?.let { t ->
        PairDialog(t, vm, onDismiss = { pairTarget = null }, onPaired = {
            pairTarget = null
            if (!onboarding) onClose()
        })
    }
    if (showManual) {
        ManualAddDialog(vm, onDismiss = { showManual = false }, onFound = { t ->
            showManual = false
            pairTarget = t
        })
    }
    forgetTarget?.let { s ->
        ConfirmDialog(
            title = "Forget ${s.name}?",
            text = "Its deck stays on the PC. You'll need the PIN to pair again.",
            confirm = "Forget",
            onConfirm = { vm.forget(s.id); forgetTarget = null },
            onDismiss = { forgetTarget = null },
        )
    }
}

@Composable
private fun RowCard(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(p.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PairedRow(
    server: SavedServer,
    client: DeckClient?,
    selected: Boolean,
    onOpen: () -> Unit,
    onForget: () -> Unit,
    onWake: () -> Unit,
) {
    val p = LocalPalette.current
    val state = client?.state?.collectAsStateWithLifecycle()?.value
    RowCard(onClick = onOpen) {
        Box {
            IconBadge(osIcon(server.os))
            StatusDot(state, Modifier.align(Alignment.BottomEnd).border(2.dp, p.surface, CircleShape), size = 12.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(server.name, style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Check, "Current", tint = p.textDim, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                "${connLabel(state)} · ${server.host}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state == ConnState.Unauthorized) p.danger else p.textDim,
            )
        }
        if (state is ConnState.Offline && server.mac != null) {
            Text(
                "Wake",
                style = MaterialTheme.typography.labelLarge,
                color = p.onAccent,
                modifier = Modifier.clip(CircleShape).background(p.accent).clickable(onClick = onWake)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        IconButton(onClick = onForget) { Icon(Icons.Rounded.Close, "Forget", tint = p.textDim) }
    }
}

@Composable
private fun SearchingCard(hasPaired: Boolean) {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "scan")
    val pulse by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .border(1.dp, p.outline, RoundedCornerShape(22.dp)).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Wifi, null, tint = p.accent, modifier = Modifier.size(28.dp).alpha(pulse))
        Spacer(Modifier.height(10.dp))
        Text(if (hasPaired) "No new PCs found" else "Looking for PCs…", style = MaterialTheme.typography.titleSmall, color = p.text)
        Spacer(Modifier.height(4.dp))
        Text(
            if (hasPaired) "Start the server on another PC to add it — it shows up here automatically."
            else "Start the AndroidDeck server on your PC and keep this phone on the same Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = p.textDim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SetupHint() {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Text("SET UP A PC", style = MaterialTheme.typography.labelSmall, color = p.textDim)
        Spacer(Modifier.height(8.dp))
        Text(
            "1  Run AndroidDeck Server on the PC (or the start script)\n2  Keep this phone on the same network\n3  Enter the 6-digit PIN it shows",
            style = MaterialTheme.typography.bodySmall,
            color = p.textDim,
            lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.3f,
        )
    }
}

@Composable
private fun PairDialog(target: PairTarget, vm: DeckViewModel, onDismiss: () -> Unit, onPaired: () -> Unit) {
    val p = LocalPalette.current
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        if (busy || pin.length != 6) return
        val fp = target.fp
        if (fp == null) {
            error = "This PC runs an old server without encryption. Update it first."
            return
        }
        busy = true
        error = null
        scope.launch {
            vm.pair(target.host, target.port, fp, pin)
                .onSuccess { onPaired() }
                .onFailure {
                    error = it.message
                    pin = ""
                    busy = false
                }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(p.surface).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(osIcon(target.os), size = 52.dp)
            Spacer(Modifier.height(14.dp))
            Text("Pair with ${target.name}", style = MaterialTheme.typography.titleLarge, color = p.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                "Enter the 6-digit PIN from the PC: click the AndroidDeck tray icon, or start AndroidDeck Server again, to open its control panel.",
                style = MaterialTheme.typography.bodyMedium,
                color = p.textDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).background(p.surfaceHigh).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("FINGERPRINT", style = MaterialTheme.typography.labelSmall, color = p.textDim)
                Text(shortFingerprint(target.fp), style = MaterialTheme.typography.titleSmall.merge(MonoStyle), color = p.text)
                Text("Must match the fingerprint in the PC's control panel", style = MaterialTheme.typography.labelSmall, color = p.textFaint)
            }
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = pin,
                onValueChange = { v ->
                    val digits = v.filter(Char::isDigit).take(6)
                    pin = digits
                    if (digits.length == 6) submit()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                cursorBrush = SolidColor(p.text),
                modifier = Modifier.focusRequester(focus),
                decorationBox = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(6) { i ->
                            val ch = pin.getOrNull(i)?.toString() ?: ""
                            val active = i == pin.length && !busy
                            Box(
                                Modifier.size(width = 40.dp, height = 52.dp).clip(RoundedCornerShape(12.dp))
                                    .background(p.surfaceHigh)
                                    .border(1.5.dp, if (active) p.text.copy(alpha = 0.7f) else p.surfaceHigh, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(ch, style = MaterialTheme.typography.titleLarge, color = p.text)
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.height(20.dp)) {
                when {
                    busy -> CircularProgressIndicator(color = p.textDim, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    error != null -> Text(error ?: "", style = MaterialTheme.typography.bodySmall, color = p.danger, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", onDismiss, Modifier.weight(1f))
                PrimaryButton("Pair", ::submit, Modifier.weight(1f), enabled = pin.length == 6 && !busy, icon = Icons.Rounded.Link)
            }
        }
    }
}

@Composable
private fun ManualAddDialog(vm: DeckViewModel, onDismiss: () -> Unit, onFound: (PairTarget) -> Unit) {
    val p = LocalPalette.current
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(DEFAULT_PORT.toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(28.dp)).background(p.surface).padding(24.dp)) {
            Text("Add by IP address", style = MaterialTheme.typography.titleLarge, color = p.text)
            Spacer(Modifier.height(6.dp))
            Text("Shown as “Address” in the PC's control panel.", style = MaterialTheme.typography.bodySmall, color = p.textDim)
            Spacer(Modifier.height(16.dp))
            DeckTextField(host, { host = it.trim() }, "192.168.0.10",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            Spacer(Modifier.height(10.dp))
            DeckTextField(port, { port = it.filter(Char::isDigit).take(5) }, "Port",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(Modifier.height(10.dp))
            if (error != null) Text(error ?: "", style = MaterialTheme.typography.bodySmall, color = p.danger)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", onDismiss, Modifier.weight(1f))
                PrimaryButton(
                    if (busy) "Checking…" else "Connect",
                    {
                        val h = host.substringBefore(':').trim()
                        val pt = port.toIntOrNull() ?: DEFAULT_PORT
                        busy = true
                        error = null
                        scope.launch {
                            vm.probe(h, pt)
                                .onSuccess { (info, fp) -> onFound(PairTarget(h, pt, info.name, info.os, fp)) }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                    Modifier.weight(1f),
                    enabled = host.isNotBlank() && !busy,
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(28.dp)).background(p.surface).padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = p.text)
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = p.textDim)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", onDismiss, Modifier.weight(1f))
                SecondaryButton(confirm, onConfirm, Modifier.weight(1f), color = p.danger)
            }
        }
    }
}
