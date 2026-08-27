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
 * scan).
 *
 * Gated on [LocalHapticsEnabled], same as `lightClickable` — but note that inside this app
 * that flag is not purely LightOS's: `ChessTheme` re-provides it as "LightOS's value OR our
 * own fallback", which is what makes haptics work at all on a build Light hasn't signed. See
 * [AppHaptics].
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

/** How long a browse arrow must be held before it starts repeat-stepping. */
private const val HOLD_REPEAT_START_MS = 400L

/**
 * Press-and-hold to repeat [onRepeat] every [intervalMs], for the board and review browse
 * arrows. The single tap stays with the caller's own `lightClickable`; this only adds what
 * happens if the finger stays down.
 *
 * A hold repeats until the finger LIFTS. Moving it neither stops the repeat nor delays it.
 * Getting that right took two goes, and both failure modes are worth remembering:
 *
 *  1. It used to abandon the repeat outright as soon as the finger crossed touch slop
 *     (~25px), because the same long press also armed the bottom bar's move-scrub and one
 *     of them had to yield. On the phone that made holding an arrow unusable — a thumb
 *     resting on a target drifts a few pixels without meaning to, and stepping would just
 *     stop. Scrubbing has since moved to its own bar beside the board, so nothing competes
 *     for this finger and there is nothing left to yield to.
 *  2. Simply deleting that slop check was NOT enough, which is not obvious. The repeat was
 *     driven by `withTimeoutOrNull(...) { awaitPointerEvent() }` — i.e. it fired when no
 *     pointer event had arrived for a while. A finger that keeps moving emits a steady
 *     stream of move events, so the timeout never expired and the repeat never started at
 *     all. "Held still" and "held" are different things, and only the second is wanted.
 *
 * So the schedule is now kept on the WALL CLOCK ([nextFireAt]): pointer events are read only
 * to notice the finger lifting, and consuming one no longer pushes the next step back.
 *
 * Still a passive observer that consumes nothing, so the chained `lightClickable` keeps
 * working: a quick tap ends before [HOLD_REPEAT_START_MS], so nothing repeats and the
 * ordinary click runs. [onRepeatStarted] lets the caller suppress the click that would
 * otherwise fire when the finger finally lifts after a repeat run.
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
        awaitEachGesture {
            // requireUnconsumed = false: the chained clickable claims the gesture, and this
            // only watches it.
            val down = awaitFirstDown(requireUnconsumed = false)
            var repeating = false
            // When the next step is due, on the wall clock — NOT a per-event timeout, so a
            // moving finger can't push it back. See the KDoc.
            var nextFireAt = System.currentTimeMillis() + HOLD_REPEAT_START_MS
            while (true) {
                val remaining = nextFireAt - System.currentTimeMillis()
                // Wait for a pointer event, but no longer than the step is due for. Null
                // means the deadline won: the hold has matured, so step and schedule again.
                val event =
                    if (remaining <= 0L) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                if (event == null) {
                    if (!repeating) {
                        repeating = true
                        currentStarted()
                    }
                    currentRepeat()
                    nextFireAt = System.currentTimeMillis() + currentInterval
                    continue
                }
                // Ends only on lift, or on the pointer vanishing (cancellation) — never on
                // movement.
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
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
