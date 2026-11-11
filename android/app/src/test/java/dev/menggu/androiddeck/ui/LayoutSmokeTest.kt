package dev.menggu.androiddeck.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.menggu.androiddeck.data.DeckAction
import dev.menggu.androiddeck.data.DeckButton
import dev.menggu.androiddeck.ui.components.AdaptiveSections
import dev.menggu.androiddeck.ui.components.Section
import dev.menggu.androiddeck.ui.deck.DeckTile
import dev.menggu.androiddeck.ui.deck.TileLive
import dev.menggu.androiddeck.ui.theme.DarkPalette
import dev.menggu.androiddeck.ui.theme.DeckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders layouts at Galaxy Z Fold cover sizes and checks placement, without a PC server. */
@RunWith(RobolectricTestRunner::class)
class LayoutSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    // Heights close to the Audio screen's sections (master, mic, media, apps).
    private val heights = listOf(165, 76, 188, 172)

    private fun sections() = heights.mapIndexed { i, h ->
        Section("s$i") {
            Box(Modifier.fillMaxWidth().height(h.dp).testTag("s$i")) { Text("Section $i") }
        }
    }

    private fun renderSections() {
        rule.setContent {
            DeckTheme(1) {
                AdaptiveSections(sections(), Modifier.fillMaxSize(), minColumnWidth = 280.dp, maxColumnWidth = 520.dp, gap = 22.dp)
            }
        }
    }

    @Test
    @Config(qualifiers = "w751dp-h410dp-land")
    fun landscapeCoverSplitsSectionsIntoTwoColumns() {
        renderSections()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val b = heights.indices.map { rule.onNodeWithTag("s$it").getUnclippedBoundsInRoot() }
        assertTrue("two columns", b[2].left >= b[0].right)
        b.forEach {
            assertTrue("inside width", it.left >= root.left && it.right <= root.right)
            assertTrue("fits height", it.bottom <= root.bottom)
        }
    }

    @Test
    @Config(qualifiers = "w416dp-h610dp-port")
    fun portraitCoverStacksSectionsInOneColumn() {
        renderSections()
        val lefts = heights.indices.map { rule.onNodeWithTag("s$it").getUnclippedBoundsInRoot().left }
        assertEquals(1, lefts.toSet().size)
    }

    private val mic = DeckButton(label = "Mic", action = DeckAction(type = "mic", op = "toggle"))

    @Test
    fun mutedTileShowsABadgeAndState() {
        rule.setContent {
            DeckTheme(1) {
                DeckTile(
                    button = mic, iconUrl = { _, _ -> null }, showLabel = true, editMode = false, flash = null,
                    onClick = {}, onLongClick = null, modifier = Modifier.size(72.dp),
                    live = TileLive(symbol = "mic_off", badge = "MUTED", tone = DarkPalette.danger, state = "Muted"),
                )
            }
        }
        rule.onNodeWithText("MUTED").assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Muted")).assertExists()
    }

    @Test
    fun offlineTileSaysSo() {
        rule.setContent {
            DeckTheme(1) {
                DeckTile(
                    button = mic, iconUrl = { _, _ -> null }, showLabel = true, editMode = false, flash = false,
                    onClick = {}, onLongClick = null, modifier = Modifier.size(168.dp), offline = true,
                )
            }
        }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "PC not connected")).assertExists()
    }
}
