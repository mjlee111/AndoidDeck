package dev.menggu.androiddeck.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import dev.menggu.androiddeck.data.shortFingerprint
import dev.menggu.androiddeck.quick.QuickActions
import dev.menggu.androiddeck.ui.components.PrimaryButton
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.ui.components.DeckCard
import dev.menggu.androiddeck.ui.components.SecondaryButton
import dev.menggu.androiddeck.ui.components.SectionLabel
import dev.menggu.androiddeck.ui.components.Segmented
import dev.menggu.androiddeck.ui.components.connLabel
import dev.menggu.androiddeck.ui.components.osLabel
import dev.menggu.androiddeck.ui.connect.ConfirmDialog
import dev.menggu.androiddeck.ui.theme.LocalPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: DeckViewModel, client: DeckClient?, onDismiss: () -> Unit, onOpenDevices: () -> Unit) {
    val p = LocalPalette.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmForget by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = vm.exportDeck()
        if (uri != null && json != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
                .onSuccess { vm.message("Deck exported") }
                .onFailure { vm.message("Export failed: ${it.message}") }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                .onSuccess { pendingImport = it }
                .onFailure { vm.message("Couldn't read that file") }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.surface,
        contentColor = p.text,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = p.text)

            SectionLabel("Button size")
            Segmented(
                options = listOf(0 to "Small", 1 to "Medium", 2 to "Large"),
                selected = settings.tileSize,
                onSelect = { v -> vm.updateSettings { it.copy(tileSize = v) } },
            )
            SectionLabel("Theme")
            Segmented(
                options = listOf(0 to "System", 1 to "Dark", 2 to "Light"),
                selected = settings.theme,
                onSelect = { v -> vm.updateSettings { it.copy(theme = v) } },
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow("Show labels", "Text under each button", settings.showLabels) { v -> vm.updateSettings { it.copy(showLabels = v) } }
            ToggleRow("Haptic feedback", "Vibrate on button press", settings.haptics) { v -> vm.updateSettings { it.copy(haptics = v) } }
            ToggleRow("Keep screen on", "Useful when the phone sits on a stand", settings.keepScreenOn) { v ->
                vm.updateSettings { it.copy(keepScreenOn = v) }
            }

            SectionLabel("Trackpad")
            SliderRow("Pointer speed", settings.pointerSpeed, 0.3f..3f) { v -> vm.updateSettings { it.copy(pointerSpeed = v) } }
            SliderRow("Scroll speed", settings.scrollSpeed, 0.3f..3f) { v -> vm.updateSettings { it.copy(scrollSpeed = v) } }
            ToggleRow("Natural scrolling", "Content follows your fingers", settings.naturalScroll) { v ->
                vm.updateSettings { it.copy(naturalScroll = v) }
            }

            SectionLabel("Widget & quick tiles")
            Text(
                "${shortcuts.size} of ${QuickActions.MAX_SHORTCUTS} buttons pinned. Pin a button in its editor " +
                    "(Show in widget & quick tiles), then add the “Deck buttons” widget to your home screen or " +
                    "the Deck 1–3 tiles to Quick Settings.",
                style = MaterialTheme.typography.bodySmall, color = p.textDim,
            )

            if (client != null) {
                val state by client.state.collectAsStateWithLifecycle()
                val info by client.info.collectAsStateWithLifecycle()
                val features by client.features.collectAsStateWithLifecycle()
                SectionLabel("This PC")
                DeckCard(Modifier.fillMaxWidth()) {
                    InfoLine("Name", client.server.name)
                    InfoLine("Status", connLabel(state))
                    InfoLine("Address", "${client.server.host}:${client.server.port}")
                    InfoLine("System", osLabel(client.server.os) + (info?.osVersion?.let { " $it" } ?: ""))
                    InfoLine("Server", info?.version ?: "–")
                    InfoLine("Encryption", "TLS · ${shortFingerprint(client.server.fp)}")
                    InfoLine("Hotkeys", features.keys ?: "unavailable")
                    InfoLine("Mouse", features.mouse ?: "unavailable")
                    InfoLine("Volume", if (features.volume) "supported" else "media keys only")
                    InfoLine("Mic / mixer", "${if (features.mic) "yes" else "no"} / ${if (features.mixer) "yes" else "no"}")
                    InfoLine("Wake-on-LAN", vm.servers.value.find { it.id == client.id }?.mac ?: "not learned yet")
                }
                SectionLabel("Deck backup · ${client.server.name}")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Export", {
                        exporter.launch("androiddeck-${client.server.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")
                    }, Modifier.weight(1f), icon = Icons.Rounded.Upload)
                    SecondaryButton("Import", { importer.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        Modifier.weight(1f), icon = Icons.Rounded.Download)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Devices", onOpenDevices, Modifier.weight(1f), icon = Icons.Rounded.Devices)
                    SecondaryButton("Forget PC", { confirmForget = true }, Modifier.weight(1f), color = p.danger)
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    pendingImport?.let { json ->
        ImportDialog(
            pcName = client?.server?.name ?: "this PC",
            onAdd = { vm.importDeck(json, replace = false); pendingImport = null },
            onReplace = { vm.importDeck(json, replace = true); pendingImport = null },
            onDismiss = { pendingImport = null },
        )
    }
    if (confirmForget && client != null) {
        ConfirmDialog(
            title = "Forget ${client.server.name}?",
            text = "Its deck stays on the PC. You'll need the PIN to pair again.",
            confirm = "Forget",
            onConfirm = {
                vm.forget(client.id)
                confirmForget = false
                onDismiss()
            },
            onDismiss = { confirmForget = false },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.textDim)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.onAccent,
                checkedTrackColor = p.accent,
                uncheckedThumbColor = p.textDim,
                uncheckedTrackColor = p.surfaceHigh,
                uncheckedBorderColor = p.outline,
            ),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = p.textDim, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = p.text, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.text, modifier = Modifier.weight(1f))
            Text("${"%.1f".format(Locale.US, value)}×", style = MaterialTheme.typography.labelLarge, color = p.textDim)
        }
        Slider(
            value = value,
            onValueChange = { onChange((it * 10).roundToInt() / 10f) },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = p.text, activeTrackColor = p.accent, inactiveTrackColor = p.surfaceHigher),
        )
    }
}

@Composable
private fun ImportDialog(pcName: String, onAdd: () -> Unit, onReplace: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(28.dp)).background(p.surface).padding(24.dp)) {
            Text("Import deck", style = MaterialTheme.typography.titleLarge, color = p.text)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add the file's pages to $pcName's deck, or replace the whole deck? App buttons only work " +
                    "if the same apps are installed on this PC.",
                style = MaterialTheme.typography.bodyMedium, color = p.textDim,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Replace", onReplace, Modifier.weight(1f), color = p.danger)
                PrimaryButton("Add pages", onAdd, Modifier.weight(1f))
            }
        }
    }
}
