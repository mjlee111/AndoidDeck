package dev.menggu.androiddeck.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.menggu.androiddeck.ui.components.Chip
import dev.menggu.androiddeck.ui.components.DeckTextField
import dev.menggu.androiddeck.ui.components.MonoStyle
import dev.menggu.androiddeck.ui.theme.LocalPalette
import java.util.Locale

val MODIFIERS = listOf("ctrl", "alt", "shift", "meta")

private val NAV_KEYS = listOf(
    "enter", "esc", "tab", "space", "backspace", "delete", "insert", "home", "end",
    "page_up", "page_down", "up", "down", "left", "right", "print_screen",
)

fun keyLabel(key: String, os: String): String = when (key) {
    "ctrl" -> if (os == "macos") "⌃ Control" else "Ctrl"
    "alt" -> if (os == "macos") "⌥ Option" else "Alt"
    "shift" -> if (os == "macos") "⇧ Shift" else "Shift"
    "meta" -> when (os) {
        "macos" -> "⌘ Cmd"
        "windows" -> "Win"
        else -> "Super"
    }
    "enter" -> "Enter"
    "esc" -> "Esc"
    "tab" -> "Tab"
    "space" -> "Space"
    "backspace" -> "Backspace"
    "delete" -> "Del"
    "insert" -> "Ins"
    "home" -> "Home"
    "end" -> "End"
    "page_up" -> "PgUp"
    "page_down" -> "PgDn"
    "up" -> "↑"
    "down" -> "↓"
    "left" -> "←"
    "right" -> "→"
    "print_screen" -> "PrtSc"
    else -> if (key.length == 1) key.uppercase(Locale.ROOT) else key.uppercase(Locale.ROOT).replace('_', ' ')
}

fun comboLabel(keys: List<String>, os: String): String = sortedCombo(keys).joinToString(" + ") { keyLabel(it, os) }

fun sortedCombo(keys: List<String>): List<String> =
    MODIFIERS.filter { it in keys } + keys.filter { it !in MODIFIERS }

fun hotkeyPresets(os: String): List<Pair<String, List<String>>> = when (os) {
    "macos" -> listOf(
        "Copy" to listOf("meta", "c"), "Paste" to listOf("meta", "v"), "Cut" to listOf("meta", "x"),
        "Undo" to listOf("meta", "z"), "Redo" to listOf("meta", "shift", "z"), "Save" to listOf("meta", "s"),
        "Find" to listOf("meta", "f"), "New tab" to listOf("meta", "t"), "Close tab" to listOf("meta", "w"),
        "Reopen tab" to listOf("meta", "shift", "t"), "Switch app" to listOf("meta", "tab"),
        "Spotlight" to listOf("meta", "space"), "Screenshot" to listOf("meta", "shift", "4"),
        "Screen record" to listOf("meta", "shift", "5"), "Mission Control" to listOf("ctrl", "up"),
        "Force quit" to listOf("meta", "alt", "esc"), "Lock screen" to listOf("ctrl", "meta", "q"),
        "Quit app" to listOf("meta", "q"), "Emoji" to listOf("ctrl", "meta", "space"),
    )
    "windows" -> listOf(
        "Copy" to listOf("ctrl", "c"), "Paste" to listOf("ctrl", "v"), "Cut" to listOf("ctrl", "x"),
        "Undo" to listOf("ctrl", "z"), "Redo" to listOf("ctrl", "y"), "Save" to listOf("ctrl", "s"),
        "Find" to listOf("ctrl", "f"), "New tab" to listOf("ctrl", "t"), "Close tab" to listOf("ctrl", "w"),
        "Reopen tab" to listOf("ctrl", "shift", "t"), "Switch app" to listOf("alt", "tab"),
        "Close window" to listOf("alt", "f4"), "Show desktop" to listOf("meta", "d"),
        "Snip" to listOf("meta", "shift", "s"), "Task Manager" to listOf("ctrl", "shift", "esc"),
        "Explorer" to listOf("meta", "e"), "Run" to listOf("meta", "r"), "Lock" to listOf("meta", "l"),
        "Clipboard" to listOf("meta", "v"), "Emoji" to listOf("meta", "."), "Game Bar" to listOf("meta", "g"),
        "Next desktop" to listOf("ctrl", "meta", "right"), "Prev desktop" to listOf("ctrl", "meta", "left"),
    )
    else -> listOf(
        "Copy" to listOf("ctrl", "c"), "Paste" to listOf("ctrl", "v"), "Cut" to listOf("ctrl", "x"),
        "Undo" to listOf("ctrl", "z"), "Redo" to listOf("ctrl", "shift", "z"), "Save" to listOf("ctrl", "s"),
        "Find" to listOf("ctrl", "f"), "New tab" to listOf("ctrl", "t"), "Close tab" to listOf("ctrl", "w"),
        "Reopen tab" to listOf("ctrl", "shift", "t"), "Switch app" to listOf("alt", "tab"),
        "Close window" to listOf("alt", "f4"), "Activities" to listOf("meta"),
        "Terminal" to listOf("ctrl", "alt", "t"), "Screenshot" to listOf("print_screen"),
        "Show desktop" to listOf("meta", "d"), "Lock" to listOf("meta", "l"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HotkeyEditor(keys: List<String>, os: String, onChange: (List<String>, String?) -> Unit) {
    val p = LocalPalette.current
    val mods = keys.filter { it in MODIFIERS }
    val main = keys.firstOrNull { it !in MODIFIERS }
    var showFn by remember { mutableStateOf(main?.matches(Regex("f(1[3-9]|2[0-4])")) == true) }

    fun setMain(k: String?) = onChange(sortedCombo(mods + listOfNotNull(k)), null)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Live combo preview as keycaps.
        FlowRow(
            Modifier.fillMaxWidth().heightIn(min = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (keys.isEmpty()) {
                Text("Pick keys below", style = MaterialTheme.typography.bodyMedium, color = p.textFaint,
                    modifier = Modifier.padding(top = 14.dp))
            }
            sortedCombo(keys).forEachIndexed { i, k ->
                if (i > 0) Text("+", color = p.textDim, modifier = Modifier.padding(top = 12.dp))
                Text(
                    keyLabel(k, os),
                    style = MaterialTheme.typography.titleMedium.merge(MonoStyle),
                    color = p.text,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(p.surfaceHigher)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        Text("Modifiers", style = MaterialTheme.typography.labelMedium, color = p.textDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MODIFIERS.forEach { m ->
                Chip(keyLabel(m, os), selected = m in mods, onClick = {
                    val next = if (m in mods) mods - m else mods + m
                    onChange(sortedCombo(next + listOfNotNull(main)), null)
                })
            }
        }

        Text("Key", style = MaterialTheme.typography.labelMedium, color = p.textDim)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeckTextField(
                value = if (main != null && main.length == 1) main.uppercase(Locale.ROOT) else "",
                onValueChange = { t ->
                    val c = t.lastOrNull { !it.isWhitespace() }
                    setMain(c?.lowercaseChar()?.toString())
                },
                placeholder = "Letter / digit",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                modifier = Modifier.width(170.dp),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NAV_KEYS.forEach { k -> Chip(keyLabel(k, os), selected = main == k, onClick = { setMain(if (main == k) null else k) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val fn = (1..(if (showFn) 24 else 12)).map { "f$it" }
            fn.forEach { k -> Chip(keyLabel(k, os), selected = main == k, onClick = { setMain(if (main == k) null else k) }) }
            if (!showFn) Chip("F13–F24", selected = false, onClick = { showFn = true })
        }

        Text("Presets", style = MaterialTheme.typography.labelMedium, color = p.textDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            hotkeyPresets(os).forEach { (name, combo) ->
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sortedCombo(combo) == sortedCombo(keys)) p.onAccent else p.text,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (sortedCombo(combo) == sortedCombo(keys)) p.accent else p.surfaceHigh)
                        .clickable { onChange(sortedCombo(combo), name) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}
