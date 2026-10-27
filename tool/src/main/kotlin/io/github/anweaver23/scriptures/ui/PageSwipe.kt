package io.github.anweaver23.scriptures.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A sideways drag turns the page when released past this share of the width. */
private const val SWIPE_MIN_FRACTION = 0.2f

/** A drag counts as sideways (a page turn, not a scroll) when it's this many times wider than tall. */
private const val SWIPE_RATIO = 1.5f

/** How much the page gives when there's no page that way. */
private const val EDGE_RESISTANCE = 0.25f

/** Page slide timings: off screen, then the new page in. */
private const val PAGE_OUT_MS = 160
private const val PAGE_IN_MS = 220

/**
 * Pages that turn by swiping, as chapters and hymns do: the page follows the finger; released far
 * enough it slides off and the next one slides in from the other side, otherwise it springs back, so a
 * drag started by accident can be slid back. Keep one per screen (as a field, so it outlasts
 * recomposition) and run it in a composition scope (`rememberCoroutineScope`), which has the frame
 * clock animations need.
 *
 * [canTurn] says whether there's a page in a direction (1 = next, -1 = previous); [turn] moves there
 * and returns once the new page is ready to slide in.
 */
class PageSlider(
    private val canTurn: (Int) -> Boolean,
    private val turn: suspend (Int) -> Unit,
) {
    /** How far the page is slid sideways, in px. Read it in `graphicsLayer { translationX = … }`. */
    val offset = Animatable(0f)

    /** Follows the finger. Past the first or last page the page only gives a little. */
    fun drag(scope: CoroutineScope, dx: Float) {
        val direction = if (dx < 0) 1 else -1
        val target = if (canTurn(direction)) dx else dx * EDGE_RESISTANCE
        scope.launch { offset.snapTo(target) }
    }

    fun release(scope: CoroutineScope, dx: Float, width: Float) {
        val direction = if (dx < 0) 1 else -1
        scope.launch {
            if (abs(dx) > width * SWIPE_MIN_FRACTION && canTurn(direction)) {
                slide(direction, width)
            } else {
                offset.animateTo(0f, spring())
            }
        }
    }

    /** Turns without a drag (e.g. playback moved on to the next hymn), with the same slide. */
    fun slide(scope: CoroutineScope, direction: Int, width: Float) {
        scope.launch { slide(direction, width) }
    }

    private suspend fun slide(direction: Int, width: Float) {
        offset.animateTo(-direction * width, tween(PAGE_OUT_MS))
        turn(direction)
        offset.snapTo(direction * width)
        offset.animateTo(0f, tween(PAGE_IN_MS))
    }
}

/**
 * Sideways page drags, read in the Initial pass so they win over the content's own tap / long-press /
 * scroll handling. A drag is claimed once it passes touch slop mostly sideways and before the
 * long-press timeout (holding still first means selecting text, so it's left alone); from then on
 * it's consumed, so nothing scrolls. Off while [enabled] is false (a selection is active).
 * Put it on a frame that doesn't move; only the page inside it slides.
 */
fun Modifier.pageSwipe(
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: (dx: Float, width: Float) -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        var dragging = false
        var dx = 0f
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            if (change == null) {
                if (dragging) onRelease(dx, size.width.toFloat())
                break
            }
            val d = change.position - down.position
            if (!dragging) {
                if (change.changedToUpIgnoreConsumed() || change.isConsumed) break
                if (change.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) break
                if (d.getDistance() <= slop) continue
                if (abs(d.x) < SWIPE_RATIO * abs(d.y)) break
                dragging = true
            }
            change.consume()
            dx = d.x
            if (change.changedToUpIgnoreConsumed()) {
                onRelease(dx, size.width.toFloat())
                break
            }
            onDrag(dx)
        }
    }
}
