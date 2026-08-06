package com.andyweaver.chess.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LocalHapticsEnabled

/**
 * Which Compose haptic the board's tap feedback uses. `VirtualKey` is the platform's
 * "tapped a key/target" haptic, the closest analogue to the SDK's own 45ms one-shot.
 * Single knob: swap for `KeyboardTap` (lighter) or `Confirm` (heavier) if the LP3's
 * slow motor reads wrong on-device.
 */
private val TAP_HAPTIC = HapticFeedbackType.VirtualKey

/**
 * Fires a tap haptic on finger-DOWN, matching LightOS's own timing.
 *
 * Almost everything in this app taps through the SDK's `Modifier.lightClickable`, which
 * already does this. The exception is a board square, which layers tap and long-press on
 * ONE `combinedClickable` recognizer (see `SquareCell`) and so can't route through the
 * SDK's clickable — and `combinedClickable` exposes no down callback. This supplies it as
 * a passive observer: it consumes nothing, so it coexists with whatever recognizer is
 * chained after it. That's exactly how `lightClickable` itself is built (a `pointerInput`
 * down-watcher paired with `.clickable`).
 *
 * Uses Compose's [LocalHapticFeedback] rather than the SDK's `LightHapticFeedback`, whose
 * only entry point needs an Android `Context` — which tool code cannot legally obtain
 * (`LocalContext` and `android.content.Context` are both blocked by the plugin's source
 * scan). Gated on [LocalHapticsEnabled] so it honours the device-wide LightOS haptics
 * preference, same as `lightClickable`.
 */
@Composable
fun Modifier.tapHaptic(enabled: Boolean = true): Modifier {
    val active = enabled && LocalHapticsEnabled.current
    val haptics = LocalHapticFeedback.current
    if (!active) return this
    return this.pointerInput(Unit) {
        awaitEachGesture {
            // Fire on finger-down like LightOS, and don't require the event to be
            // unconsumed — this only observes, it never claims the gesture.
            awaitFirstDown(requireUnconsumed = false)
            haptics.performHapticFeedback(TAP_HAPTIC)
        }
    }
}

/**
 * How long a browse arrow must be held before it starts repeat-stepping.
 *
 * Deliberately BELOW Compose's ~500ms long-press timeout, which is what arms the bottom
 * bar's move-scrub drag: holding an arrow should read as "step faster", and a hold that
 * turns into a drag hands over to the scrub (see [holdRepeat], which bails out on slop).
 */
private const val HOLD_REPEAT_START_MS = 400L

/**
 * Press-and-hold to repeat [onRepeat] every [intervalMs], for the board and review browse
 * arrows. The single tap stays with the caller's own `lightClickable`; this only adds what
 * happens if the finger stays down.
 *
 * Coexisting with move-scrubbing is the whole difficulty, and is why this is a passive
 * observer that consumes nothing:
 *  - a quick tap ends before [HOLD_REPEAT_START_MS], so nothing repeats and the ordinary
 *    click runs;
 *  - a hold that stays put repeats, and the parent bar's `detectDragGesturesAfterLongPress`
 *    emits nothing without an actual drag;
 *  - a hold that MOVES past touch slop abandons repeating, leaving the gesture to the
 *    parent's scrub — so the two never fight over the same finger.
 *
 * [onRepeatStarted] lets the caller suppress the click that would otherwise fire when the
 * finger finally lifts after a repeat run.
 */
@Composable
fun Modifier.holdRepeat(
    enabled: Boolean,
    intervalMs: Long,
    onRepeatStarted: () -> Unit = {},
    onRepeat: () -> Unit,
): Modifier {
    if (!enabled) return this
    val currentRepeat by rememberUpdatedState(onRepeat)
    val currentStarted by rememberUpdatedState(onRepeatStarted)
    val currentInterval by rememberUpdatedState(intervalMs)
    return this.pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            // requireUnconsumed = false: the chained clickable claims the gesture, and this
            // only watches it.
            val down = awaitFirstDown(requireUnconsumed = false)
            val origin = down.position
            var repeating = false
            while (true) {
                // No event within the deadline means the finger is still down and still
                // still — i.e. the hold matured, so step once and re-arm at the interval.
                val event = withTimeoutOrNull(
                    if (repeating) currentInterval else HOLD_REPEAT_START_MS,
                ) { awaitPointerEvent() }
                if (event == null) {
                    if (!repeating) {
                        repeating = true
                        currentStarted()
                    }
                    currentRepeat()
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if ((change.position - origin).getDistance() > slop) break
            }
        }
    }
}

/**
 * A player's name with their Elo rendered right after it, at the SAME size and
 * weight as the name ("name · 1500") so the rating reads as part of the name line
 * rather than a dimmed afterthought. Used on the home game list, incoming
 * challenges, the new-game friend list, and game history.
 *
 * The name shrinks/ellipsizes to fit (weight, fill = false) so the rating stays
 * visible immediately after it rather than being pushed to the far edge. Pass a
 * `Modifier.weight(1f)` from the parent row so the whole block takes the available
 * space beside any trailing controls.
 */
@Composable
fun NameWithRating(
    name: String,
    rating: Int?,
    modifier: Modifier = Modifier,
    nameVariant: LightTextVariant = LightTextVariant.Subheading,
    // Lichess's "?" marker for a not-yet-established rating. Only meaningful where the
    // caller actually has the provisional flag (e.g. the following list's `perfs` block) —
    // defaults to false where it isn't available (flat game/challenge ratings).
    prov: Boolean = false,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        LightText(
            text = name,
            variant = nameVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (rating != null) {
            LightText(
                text = " · $rating${if (prov) "?" else ""}",
                variant = nameVariant,
                maxLines = 1,
            )
        }
    }
}
