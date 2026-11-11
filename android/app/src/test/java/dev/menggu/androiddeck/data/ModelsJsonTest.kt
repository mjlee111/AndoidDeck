package dev.menggu.androiddeck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsJsonTest {
    @Test
    fun toggleHoldLongPressAndSizeSurviveJson() {
        val b = DeckButton(
            label = "Stream",
            cols = 2, rows = 3,
            action = DeckAction(type = "hotkey", keys = listOf("f13"), hold = true),
            toggle = ButtonToggle(DeckAction(type = "hotkey", keys = listOf("f14")), label = "Live"),
            longAction = DeckAction(type = "media", key = "next"),
            widget = null,
        )
        val back = DeckJson.decodeFromString(DeckButton.serializer(), DeckJson.encodeToString(DeckButton.serializer(), b))
        assertEquals(b, back)
    }

    @Test
    fun olderProfilesStillLoad() {
        val old = """{"id":"x","label":"Mic","action":{"type":"mic","op":"toggle"}}"""
        val b = DeckJson.decodeFromString(DeckButton.serializer(), old)
        assertEquals(1, b.cols)
        assertEquals(1, b.rows)
        assertNull(b.widget)
        assertNull(b.toggle)
    }

    @Test
    fun liveStateFromTheServerParses() {
        val json = """{"name":"PC","volume":{"available":true,"level":0.37,"muted":true},"mic":{"available":false},
            "media":{"available":true,"playing":false,"title":"Song","artist":null,"source":"spotify"},"usage":{"cpu":12.5,"mem":71.0}}"""
        val s = DeckJson.decodeFromString(LiveState.serializer(), json)
        assertEquals(0.37, s.volume.level!!, 1e-9)
        assertEquals("Song", s.media.title)
        assertEquals(71.0, s.usage!!.mem, 1e-9)
    }
}
