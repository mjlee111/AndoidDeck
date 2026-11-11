package dev.menggu.androiddeck.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Local slider value that follows the PC unless the user is dragging (or just let go),
 * so remote updates never make the thumb jump back under the finger.
 */
class FollowedLevel(initial: Float) {
    var shown by mutableFloatStateOf(initial)
    var dragging by mutableStateOf(false)
    var holdUntil by mutableLongStateOf(0L)
    var lastSent = 0L

    fun follow(remote: Float?) {
        if (remote != null && !dragging && System.currentTimeMillis() > holdUntil) shown = remote
    }

    fun drag(v: Float, send: (Float) -> Unit) {
        dragging = true
        shown = v
        val now = System.currentTimeMillis()
        if (now - lastSent > 60) {
            lastSent = now
            send(v)
        }
    }

    fun release(send: (Float) -> Unit) {
        send(shown)
        holdUntil = System.currentTimeMillis() + 1200
        dragging = false
    }
}
