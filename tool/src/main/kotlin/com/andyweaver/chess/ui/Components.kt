package com.andyweaver.chess.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/**
 * A player's name with their Elo rendered *less prominently* right after it —
 * the rating uses the smaller [LightTextVariant.Detail] and is dimmed, so the
 * name stays the focus. Used on the home game list and the new-game friend list.
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
                text = " · $rating",
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
            )
        }
    }
}
