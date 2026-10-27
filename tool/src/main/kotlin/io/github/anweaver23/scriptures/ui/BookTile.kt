package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

// Book tile look. Units are Light grid units (1/27 of the screen width) unless noted.

/** Outline thickness, in design px (the same scale as the text input's 3px underline). */
const val BOOK_STROKE_PX = 2.5f

/** Gap between the outline and the title, on each side. */
const val BOOK_TITLE_PADDING_UNITS = 0.25f

/**
 * Title font sizes to try at the default text size, largest first, in design px. Each is multiplied
 * by the text size setting's scale. All tiles share one size: the largest at which no title breaks
 * inside a word. The last step is used even if something still breaks.
 */
val BOOK_TITLE_SIZES_PX = listOf(22f, 20f, 18f, 16f, 15f, 14f, 13f, 12f, 11f, 10f)

private const val LINE_HEIGHT_FACTOR = 1.2f

/** Horizontal insets around the title inside a tile of any width. */
private class TitleInsets(val start: Dp, val end: Dp)

@Composable
private fun titleInsets(): TitleInsets {
    val stroke = BOOK_STROKE_PX.designVerticalPxToDp()
    val padding = BOOK_TITLE_PADDING_UNITS.gridUnitsAsDp()
    return TitleInsets(
        start = stroke + padding,
        end = stroke + padding,
    )
}

@Composable
private fun titleStyle(step: Int, scale: Float): TextStyle {
    val size: TextUnit = (BOOK_TITLE_SIZES_PX[step.coerceIn(BOOK_TITLE_SIZES_PX.indices)] * scale).designVerticalPxToSp()
    return LightThemeTokens.typography.detail.copy(
        color = LightThemeTokens.colors.content,
        textAlign = TextAlign.Center,
        fontSize = size,
        lineHeight = size * LINE_HEIGHT_FACTOR,
    )
}

/**
 * The first step of [BOOK_TITLE_SIZES_PX] at which none of [titles] breaks inside a word in a tile
 * [tileWidth] wide. Measured with the real line breaker against the title's real width
 * constraint. It's a first guess: [BookTile] re-checks its rendered layout and reports a mid-word
 * break (e.g. if the font finished loading after this ran).
 */
@Composable
fun rememberSharedTitleStep(titles: List<String>, tileWidth: Dp, scale: Float): Int {
    val measurer = rememberTextMeasurer()
    val insets = titleInsets()
    val density = LocalDensity.current
    val widthPx = with(density) { (tileWidth - insets.start - insets.end).roundToPx() }.coerceAtLeast(1)
    val styles = BOOK_TITLE_SIZES_PX.indices.map { titleStyle(it, scale) }
    return remember(titles, widthPx, styles) {
        styles.indexOfFirst { style ->
            titles.none { title ->
                breaksMidWord(measurer.measure(title, style, constraints = Constraints(maxWidth = widthPx)))
            }
        }.let { if (it < 0) styles.lastIndex else it }
    }
}

/**
 * A square book [side] across, drawn as a single thin outline, with the title centred at size
 * [step] × [scale].
 * [onMidWordBreak] fires if the rendered title still breaks inside a word, so the caller can
 * step every tile down together.
 */
@Composable
fun BookTile(
    title: String,
    side: Dp,
    step: Int,
    scale: Float,
    onMidWordBreak: () -> Unit,
    onClick: () -> Unit,
) {
    // The outline is the lighter secondary colour, so it frames the title without competing with it.
    val outline = LightThemeTokens.colors.contentSecondary
    val stroke = BOOK_STROKE_PX.designVerticalPxToDp()
    val insets = titleInsets()

    Box(
        modifier = Modifier
            .size(side)
            .lightClickable(onClick = onClick)
            .drawBehind {
                val w = stroke.toPx()
                val half = w / 2
                drawRect(
                    color = outline,
                    topLeft = Offset(half, half),
                    size = Size(size.width - w, size.height - w),
                    style = Stroke(width = w),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = title,
            style = titleStyle(step, scale),
            onTextLayout = { if (breaksMidWord(it)) onMidWordBreak() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = insets.start, end = insets.end),
        )
    }
}

private fun breaksMidWord(layout: TextLayoutResult): Boolean =
    breaksMidWord(layout.layoutInput.text.text, (0 until layout.lineCount).map { layout.getLineEnd(it) })

/** True when any line (other than the last) ends between two letters/digits of the same word. */
internal fun breaksMidWord(text: String, lineEnds: List<Int>): Boolean =
    lineEnds.dropLast(1).any { end ->
        end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
    }
