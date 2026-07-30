package com.andyweaver.chess.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

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
