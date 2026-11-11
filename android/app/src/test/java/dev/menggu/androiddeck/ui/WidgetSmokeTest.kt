package dev.menggu.androiddeck.ui

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.menggu.androiddeck.DeckViewModel
import dev.menggu.androiddeck.data.DeckAction
import dev.menggu.androiddeck.data.DeckButton
import dev.menggu.androiddeck.data.DeckClient
import dev.menggu.androiddeck.data.SavedServer
import dev.menggu.androiddeck.data.TileWidget
import dev.menggu.androiddeck.quick.controlAction
import dev.menggu.androiddeck.ui.deck.WidgetTile
import dev.menggu.androiddeck.ui.theme.DeckTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every widget renders at one cell, wide, tall and 2 x 2, with a PC that is not connected. */
@RunWith(RobolectricTestRunner::class)
class WidgetSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun allWidgetsRenderAtEverySize() {
        val vm = DeckViewModel(ApplicationProvider.getApplicationContext<Application>())
        val pc = DeckClient(SavedServer("pc", "Studio-PC", "127.0.0.1", 9, "linux", "token"), CoroutineScope(Dispatchers.Unconfined))
        val types = listOf("volume", "mic", "mixer", "media", "monitor")
        val sizes = listOf(100 to 100, 320 to 100, 100 to 210, 210 to 210)
        rule.setContent {
            DeckTheme(1) {
                androidx.compose.foundation.layout.Column {
                    types.forEach { t ->
                        sizes.forEach { (w, h) ->
                            val b = DeckButton(label = "$t $w", action = DeckAction(type = "none"), widget = TileWidget(t, name = "Discord"))
                            WidgetTile(b, pc, vm, editMode = false, haptics = false, modifier = Modifier.size(w.dp, h.dp))
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("volume 320").assertExists()
    }

    @Test
    fun homeScreenControlsSendTheRightActions() {
        val a: (String) -> dev.menggu.androiddeck.data.DeckAction? = { controlAction(it) }
        org.junit.Assert.assertEquals("toggle", a("vol_mute")?.op)
        org.junit.Assert.assertEquals("toggle", a("mic")?.op)
        org.junit.Assert.assertEquals("play_pause", a("play")?.key)
        org.junit.Assert.assertEquals(0.05, a("vol_up")?.value ?: 0.0, 1e-9)
        org.junit.Assert.assertNull(a("unknown"))
    }
}
