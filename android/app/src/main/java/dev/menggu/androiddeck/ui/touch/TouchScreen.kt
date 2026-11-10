package dev.menggu.androiddeck.ui.touch

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.ui.theme.LocalPalette
import kotlin.math.max

/** Never-sent prefix so a backspace on an "empty" field is still observable. */
private const val SENTINEL = "  "

private val SPECIAL_KEYS = listOf(
    "Esc" to listOf("esc"), "Tab" to listOf("tab"), "Enter" to listOf("enter"), "⌫" to listOf("backspace"),
    "Del" to listOf("delete"), "←" to listOf("left"), "↑" to listOf("up"), "↓" to listOf("down"), "→" to listOf("right"),
    "Home" to listOf("home"), "End" to listOf("end"), "Win" to listOf("meta"),
)

@Composable
fun TouchScreen(vm: DeckViewModel, client: DeckClient) {
    val p = LocalPalette.current
    val view = LocalView.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val features by client.features.collectAsStateWithLifecycle()
    var keyboardOn by remember { mutableStateOf(false) }

    fun tick() {
        if (settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        if (features.mouse == null && client.isConnected) {
            Text(
                "Mouse control isn't available on ${client.server.name}. On Linux Wayland, install ydotool.",
                style = MaterialTheme.typography.bodySmall, color = p.danger,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Trackpad(
            client = client,
            speed = settings.pointerSpeed,
            scrollSpeed = settings.scrollSpeed,
            natural = settings.naturalScroll,
            onClickFeedback = ::tick,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MouseButton("Left", "Hold to drag", Modifier.weight(1f), onDown = { tick(); client.mouseButton("left", true) },
                onUp = { client.mouseButton("left", false) })
            MouseButton("Right", null, Modifier.weight(1f), onDown = { tick(); client.mouseButton("right", true) },
                onUp = { client.mouseButton("right", false) })
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (keyboardOn) p.accent else p.surface)
                    .clickable { keyboardOn = !keyboardOn },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (keyboardOn) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
                    if (keyboardOn) "Hide keyboard" else "Type on the PC",
                    tint = if (keyboardOn) p.onAccent else p.text,
                )
            }
            Spacer(Modifier.width(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(SPECIAL_KEYS) { (label, keys) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = p.text,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(p.surface)
                            .clickable { tick(); client.keyCombo(keys) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
        if (keyboardOn) LiveKeyboard(client)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun Trackpad(
    client: DeckClient,
    speed: Float,
    scrollSpeed: Float,
    natural: Boolean,
    onClickFeedback: () -> Unit,
    modifier: Modifier,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current.density
    val tapSlop = 10f * density
    val cfg by rememberUpdatedState(Triple(speed, scrollSpeed, natural))
    val feedback by rememberUpdatedState(onClickFeedback)

    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(p.surface)
            .pointerInput(client.id) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val startTime = first.uptimeMillis
                    var maxPointers = 1
                    var travelled = 0f
                    var moveAcc = Offset.Zero
                    var scrollAcc = Offset.Zero
                    var lastSend = 0L
                    var sawTwo = false
                    var endTime = startTime

                    fun flush(now: Long, force: Boolean) {
                        if (!force && now - lastSend < 16) return
                        lastSend = now
                        if (moveAcc != Offset.Zero) {
                            client.mouseMove(moveAcc.x, moveAcc.y)
                            moveAcc = Offset.Zero
                        }
                        if (scrollAcc != Offset.Zero) {
                            client.scroll(scrollAcc.x, scrollAcc.y)
                            scrollAcc = Offset.Zero
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        endTime = event.changes.first().uptimeMillis
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        maxPointers = max(maxPointers, pressed.size)
                        val (ptrSpeed, scrSpeed, nat) = cfg
                        if (pressed.size >= 2) {
                            sawTwo = true
                            val d = pressed.fold(Offset.Zero) { acc, c -> acc + c.positionChange() } / pressed.size.toFloat()
                            travelled += d.getDistance()
                            // ~22 dp of finger travel per wheel step.
                            val sx = d.x / density / 22f * scrSpeed
                            val sy = d.y / density / 22f * scrSpeed
                            scrollAcc += if (nat) Offset(-sx, sy) else Offset(sx, -sy)
                        } else if (!sawTwo) {
                            // After a two-finger gesture, ignore the last finger so the pointer doesn't jump.
                            val d = pressed[0].positionChange()
                            val dist = d.getDistance()
                            travelled += dist
                            val dp = dist / density
                            val accel = 1f + (dp / 6f).coerceAtMost(2f) * 0.6f
                            moveAcc += d / density * (1.4f * ptrSpeed * accel)
                        }
                        event.changes.forEach { it.consume() }
                        flush(event.changes.first().uptimeMillis, force = false)
                    }
                    flush(0, force = true)
                    if (travelled < tapSlop && endTime - startTime < 300) {
                        feedback()
                        client.click(if (maxPointers >= 2) "right" else "left")
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Icons.Rounded.TouchApp, null, tint = p.textFaint, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                "Drag to move · Tap to click\nTwo fingers: scroll · Two-finger tap: right click",
                style = MaterialTheme.typography.bodySmall,
                color = p.textFaint,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MouseButton(label: String, sub: String?, modifier: Modifier, onDown: () -> Unit, onUp: () -> Unit) {
    val p = LocalPalette.current
    var pressed by remember { mutableStateOf(false) }
    Column(
        modifier
            .height(60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (pressed) p.surfaceHigher else p.surface)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    onDown()
                    tryAwaitRelease()
                    onUp()
                    pressed = false
                })
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = p.text)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = p.textFaint)
    }
}

/**
 * Invisible text field that forwards typing to the PC. Text is sent only once the IME commits it
 * (composition == null), so Korean syllables arrive complete; edits are sent as backspaces + text.
 */
@Composable
private fun LiveKeyboard(client: DeckClient) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    var committed by remember { mutableStateOf(SENTINEL) }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    BasicTextField(
        value = value,
        onValueChange = { next ->
            value = next
            if (next.composition != null) return@BasicTextField
            val old = committed
            val new = next.text
            val common = old.commonPrefixWith(new).length
            repeat(old.length - common) { client.keyCombo(listOf("backspace")) }
            val inserted = new.substring(common)
            if (inserted.isNotEmpty()) client.typeText(inserted)
            if (new.length < SENTINEL.length || !new.startsWith(SENTINEL) || new.length > 64) {
                value = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
                committed = SENTINEL
            } else {
                committed = new
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus),
    )
}
