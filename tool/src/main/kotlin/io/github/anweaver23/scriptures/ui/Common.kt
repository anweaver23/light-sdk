package io.github.anweaver23.scriptures.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Height of one list row, in Light grid units. Lazy lists need it to size their scroll bar. */
const val ROW_UNITS = 3f

/**
 * Top bar, a content area that fills the middle, and an optional bottom bar. [bottomContent], when
 * set, replaces [bottomBar] with a custom bar (e.g. the reader's selection actions).
 */
@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)?,
    bottomBar: List<LightBarButton?>? = null,
    rightButton: LightBarButton? = null,
    bottomContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Column(
            Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = onBack?.let { LightBarButton.LightIcon(LightIcons.BACK, onClick = it) },
                center = LightTopBarCenter.Text(title),
                rightButton = rightButton,
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

/** A tappable single-line row: label on the left, optional detail on the right. */
@Composable
fun ListRow(label: String, detail: String? = null, lighten: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            lighten = lighten,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            LightText(
                text = detail,
                variant = LightTextVariant.Fine,
                lighten = true,
                maxLines = 1,
                modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
            )
        }
    }
}

/** Short explanatory copy, used for empty states and instructions. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp()),
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
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEach { item ->
            val modifier = Modifier
                .height(ACTION_BAR_UNITS.gridUnitsAsDp())
                .lightClickable { item.onClick?.invoke() }
                .padding(horizontal = 0.5f.gridUnitsAsDp())
            Box(modifier, contentAlignment = Alignment.Center) {
                when (item) {
                    is LightBarButton.Text -> LightText(item.text, variant = LightTextVariant.Button, maxLines = 1)
                    is LightBarButton.LightIcon -> LightIcon(item.icon, size = item.sizeUnits)
                    is LightBarButton.Icon -> Unit
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
            .padding(vertical = 0.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            LightText(title, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            lines.forEach { (text, maxLines) ->
                LightText(
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

fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
