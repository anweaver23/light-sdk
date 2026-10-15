package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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

/** Width of the spine band at the book's left edge. */
const val BOOK_SPINE_UNITS = 0.4f

/** Inner padding between the outline and the title. */
const val BOOK_TITLE_PADDING_UNITS = 0.4f

/** Title font sizes to try, largest first, in design px. The largest whose longest word fits wins. */
val BOOK_TITLE_SIZES_PX = listOf(20f, 18f, 16f, 14f)

/**
 * A portrait book drawn in thin strokes: an outlined cover with a spine band down the left
 * edge (a second vertical line), and the title centred on the cover.
 */
@Composable
fun BookTile(title: String, width: Dp, height: Dp, onClick: () -> Unit) {
    val content = LightThemeTokens.colors.content
    val stroke = BOOK_STROKE_PX.designVerticalPxToDp()
    val spine = BOOK_SPINE_UNITS.gridUnitsAsDp()
    val padding = BOOK_TITLE_PADDING_UNITS.gridUnitsAsDp()
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .size(width, height)
            .lightClickable(onClick = onClick)
            .drawBehind {
                val w = stroke.toPx()
                val half = w / 2
                drawRect(
                    color = content,
                    topLeft = Offset(half, half),
                    size = Size(size.width - w, size.height - w),
                    style = Stroke(width = w),
                )
                val x = spine.toPx()
                drawLine(content, Offset(x, 0f), Offset(x, size.height), strokeWidth = w)
            },
        contentAlignment = Alignment.Center,
    ) {
        val baseStyle = LightThemeTokens.typography.detail.copy(color = content, textAlign = TextAlign.Center)
        val textWidthPx = with(density) { (width - spine - stroke - padding * 2).toPx() }
        val fontSize = fittingFontSize(title, baseStyle, textWidthPx)
        val lineHeight = fontSize * LINE_HEIGHT_FACTOR
        BasicText(
            text = title,
            style = baseStyle.copy(fontSize = fontSize, lineHeight = lineHeight),
            modifier = Modifier
                .padding(start = spine + stroke)
                .padding(padding),
        )
    }
}

private const val LINE_HEIGHT_FACTOR = 1.2f

/** The largest of [BOOK_TITLE_SIZES_PX] at which every word of [title] fits on one line of [widthPx]. */
@Composable
private fun fittingFontSize(title: String, style: TextStyle, widthPx: Float): TextUnit {
    val measurer = rememberTextMeasurer()
    val sizes = BOOK_TITLE_SIZES_PX.map { it.designVerticalPxToSp() }
    return remember(title, widthPx, sizes) {
        val words = title.split(' ').filter { it.isNotEmpty() }
        sizes.firstOrNull { size ->
            words.all { word -> measurer.measure(word, style.copy(fontSize = size)).size.width <= widthPx }
        } ?: sizes.last()
    }
}
