package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Zen mode: the reading pages (chapters, hymns) without their top and bottom bars. A double tap turns
 * it on or off, with no confirmation. It's one setting for the session, so it carries from chapter to
 * chapter and between the reader and hymns. Highlighting and swiping still work.
 */
object Zen {
    var on by mutableStateOf(false)

    fun toggle() {
        on = !on
    }
}

/**
 * Calls [onDoubleTap] for two quick taps close together. It only watches (Initial pass, nothing
 * consumed), so the content's own taps, long presses, scrolling and swipes carry on as normal.
 */
fun Modifier.doubleTap(onDoubleTap: () -> Unit): Modifier = pointerInput(Unit) {
    var lastUp = 0L
    var lastX = 0f
    var lastY = 0f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > slop) break
            if (change.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) break
            if (change.changedToUpIgnoreConsumed()) {
                val near = kotlin.math.abs(down.position.x - lastX) < slop * 4 &&
                    kotlin.math.abs(down.position.y - lastY) < slop * 4
                if (lastUp > 0 && down.uptimeMillis - lastUp < viewConfiguration.doubleTapTimeoutMillis && near) {
                    lastUp = 0L
                    onDoubleTap()
                } else {
                    lastUp = change.uptimeMillis
                    lastX = down.position.x
                    lastY = down.position.y
                }
                break
            }
        }
    }
}
