package io.github.anweaver23.scriptures.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

// LightOS list metrics, measured from LightOS Settings on the LP3 (1080x1240): row text is Heading
// (38 design px; "Notifications" draws 59 px tall), rows are 200 px apart (5 grid units), and the text
// starts 80 px in (2 grid units: the frame's 1 plus LIST_INSET_UNITS). Lists, menus and pickers are
// "system" UI: they never follow the Display settings.

/** Row text: labels and their dimmed values both at this size. */
val ROW_TEXT = LightTextVariant.Heading

/** Height of one list row, in grid units. Lazy lists also need it to size their scroll bar. */
const val ROW_UNITS = 5f

/** How far list text sits in from the frame's own padding, in grid units. */
const val LIST_INSET_UNITS = 1f

/**
 * Top bar, a content area that fills the middle, and an optional bottom bar. [bottomContent], when
 * set, replaces [bottomBar] with a custom bar (e.g. the reader's selection actions). [reading]
 * screens (the reader, a note) take Display's invert colours; everything else follows LightOS.
 */
@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)?,
    bottomBar: List<LightBarButton?>? = null,
    rightButton: LightBarButton? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    reading: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppTheme(reading = reading) {
        Column(
            Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = onBack?.let { LightBarButton.LightIcon(LightIcons.BACK, onClick = it) },
                center = LightTopBarCenter.Text(title),
                rightButton = rightButton,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                content = content,
            )
            when {
                bottomContent != null -> bottomContent()
                bottomBar != null -> LightBottomBar(items = bottomBar)
            }
        }
    }
}

/**
 * A tappable single-line row: label on the left, optional value dimmed on the right (LightOS style).
 * [underline] marks it as a link (e.g. a root word in Define).
 */
@Composable
fun ListRow(
    label: String,
    detail: String? = null,
    lighten: Boolean = false,
    underline: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(start = LIST_INSET_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AppText(
            text = label,
            variant = ROW_TEXT,
            lighten = lighten,
            underline = underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            AppText(
                text = detail,
                variant = ROW_TEXT,
                lighten = true,
                maxLines = 1,
                modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
            )
        }
    }
}

/** One choice in a picker list; the current one is underlined, as LightOS pickers show it. */
@Composable
fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(start = LIST_INSET_UNITS.gridUnitsAsDp()),
        contentAlignment = Alignment.CenterStart,
    ) {
        AppText(text = label, variant = ROW_TEXT, underline = selected, maxLines = 1)
    }
}

/** Short explanatory copy, used for empty states and instructions. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    AppText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = LIST_INSET_UNITS.gridUnitsAsDp(), top = 0.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
    )
}

/** Height of [ActionBar], matching [LightBottomBar]. */
const val ACTION_BAR_UNITS = 4f

/**
 * A bottom bar for more than three actions, which [LightBottomBar] doesn't allow with text.
 * Items are spread evenly; each is a [LightBarButton.Text] or [LightBarButton.LightIcon].
 */
@Composable
fun ActionBar(items: List<LightBarButton>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ACTION_BAR_UNITS.gridUnitsAsDp())
            // Tight padding: four items in Button type (wide letter-spacing) only just fit 1080px.
            .padding(horizontal = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEach { item ->
            val modifier = Modifier
                .height(ACTION_BAR_UNITS.gridUnitsAsDp())
                .lightClickable { item.onClick?.invoke() }
                .padding(horizontal = 0.25f.gridUnitsAsDp())
            Box(modifier, contentAlignment = Alignment.Center) {
                when (item) {
                    is LightBarButton.Text -> LightText(item.text, variant = LightTextVariant.Button, maxLines = 1)
                    is LightBarButton.LightIcon -> LightIcon(item.icon, size = item.sizeUnits)
                    is LightBarButton.Icon -> Image(
                        painter = item.painter,
                        contentDescription = item.contentDescription,
                        modifier = Modifier.size(item.sizeUnits.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

/** A tappable row of two or more lines; the whole row opens [onClick]. Used by the study lists. */
@Composable
fun StackRow(
    title: String,
    lines: List<Pair<String, Int>>,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(start = LIST_INSET_UNITS.gridUnitsAsDp(), top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AppText(title, variant = ROW_TEXT, maxLines = 1, overflow = TextOverflow.Ellipsis)
            lines.forEach { (text, maxLines) ->
                AppText(
                    text = text,
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 1f.gridUnitsAsDp())) { trailing() }
        }
    }
}

/**
 * One of our own white vector icons, tinted to the theme's content colour like a [LightIcons] glyph,
 * for use in a [LightBarButton.Icon] (which draws painters untinted). One drawable serves both themes.
 */
@Composable
fun tintedIcon(@DrawableRes id: Int): Painter {
    val painter = painterResource(id)
    val color = LightThemeTokens.colors.content
    return remember(painter, color) { TintedPainter(painter, color) }
}

private class TintedPainter(private val inner: Painter, color: Color) : Painter() {
    private val filter = ColorFilter.tint(color)
    override val intrinsicSize: Size get() = inner.intrinsicSize
    override fun DrawScope.onDraw() {
        with(inner) { draw(size, colorFilter = filter) }
    }
}

/**
 * Asks before something can't be undone: [message] centred, then [confirmLabel] in the middle of the
 * bar and a close icon on the right, as LightOS confirmations are. Returns true on confirm; close or
 * BACK returns nothing. A screen rather than an overlay so
 * hardware BACK behaves (see the study sub-views decision in CLAUDE.md).
 */
class ConfirmScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val message: String,
    private val confirmLabel: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        ScreenFrame(
            title = title,
            onBack = { goBack() },
            bottomBar = listOf(
                null,
                LightBarButton.Text(confirmLabel, onClick = { goBack(true) }),
                LightBarButton.LightIcon(LightIcons.CLOSE, onClick = { goBack() }, contentDescription = "Cancel"),
            ),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AppText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
