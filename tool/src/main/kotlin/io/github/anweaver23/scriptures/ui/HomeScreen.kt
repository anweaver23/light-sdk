package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.andyweaver.scriptures.R
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Volume
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Home grid layout. Units are Light grid units (1/27 of the screen width).

/**
 * Space between tiles, across and down. Tiles are square; their side is whatever the columns leave,
 * and the column count comes from the text size setting ([TextSize.homeColumns]).
 */
const val TILE_GAP_UNITS = 1f

/** Space above the grid and between the grid and the quiet rows below it. */
const val GRID_TOP_GAP_UNITS = 0.25f
const val GRID_BOTTOM_GAP_UNITS = 0.5f

/** Width LightScrollView keeps for its bar when it's [LightScrollBarPosition.Outside] (SDK constant). */
private const val SCROLL_GUTTER_UNITS = 2f

/** Height of the secondary row under the grid (Resume listening). Shorter than a [ListRow] on purpose. */
const val QUIET_ROW_UNITS = 2.25f

class HomeViewModel : LightViewModel<Unit>() {
    val catalog: Catalog = AppGraph.catalog
    val lastPlayed = AppGraph.progress.lastPlayed.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val textSize = AppGraph.textSize

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Pick up anything uploaded through the Tool Manager while the tool was closed.
        AppGraph.scope.launch { AppGraph.importer.importAll() }
    }
}

/** One tile on the home grid. */
private data class Shelf(val title: String, val open: () -> Unit)

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    override val viewModelClass = HomeViewModel::class.java
    override fun createViewModel() = HomeViewModel()

    @Composable
    override fun Content() {
        val lastPlayed by viewModel.lastPlayed.collectAsState()
        val textSize by viewModel.textSize.collectAsState()
        val catalog = viewModel.catalog
        val colors by LightThemeController.colors.collectAsState()

        val shelves = catalog.scriptures.volumes.map { volume -> Shelf(volume.title) { openVolume(volume) } } +
            Shelf("Hymns") { navigateTo(::HymnBooksScreen) }

        // ScreenFrame applies the theme too; this outer one is only so the bookmark glyph picks up
        // the theme's content colour.
        LightTheme(colors = colors) {
            ScreenFrame(
                title = "Scriptures",
                onBack = null,
                rightButton = pageMenu(
                    listOf(
                        MenuItem("Annotations", MenuIcon.Light(LightIcons.PENCIL)) {
                            navigateTo({ AnnotationsScreen(it) })
                        },
                    ),
                ),
                bottomBar = listOf(
                    LightBarButton.LightIcon(
                        LightIcons.SETTINGS,
                        onClick = { navigateTo(::SettingsScreen) },
                        contentDescription = "Settings",
                    ),
                    LightBarButton.LightIcon(LightIcons.SEARCH, onClick = { navigateTo(::SearchScreen) }),
                    LightBarButton.Icon(
                        painter = tintedIcon(R.drawable.ic_bookmark),
                        onClick = { navigateTo({ BookmarksScreen(it) }) },
                        contentDescription = "Bookmarks",
                    ),
                ),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = textSize.homeColumns
                    val gap = TILE_GAP_UNITS.gridUnitsAsDp()
                    val rows = (shelves.size + columns - 1) / columns
                    // If the squares fit without scrolling, use the full width (no scroll bar gutter);
                    // otherwise leave room for the gutter so the last column isn't clipped.
                    val fullSide = (maxWidth - gap * (columns - 1)) / columns
                    val reserved = (GRID_TOP_GAP_UNITS + GRID_BOTTOM_GAP_UNITS + (if (lastPlayed != null) QUIET_ROW_UNITS else 0f))
                        .gridUnitsAsDp()
                    val fits = fullSide * rows + gap * (rows - 1) + reserved <= maxHeight
                    val side = if (fits) fullSide else (maxWidth - SCROLL_GUTTER_UNITS.gridUnitsAsDp() - gap * (columns - 1)) / columns
                    val guess = rememberSharedTitleStep(shelves.map { it.title }, side, textSize.scale)
                    // One title size for every tile. If a tile's rendered title still breaks
                    // mid-word, all tiles step down together.
                    var step by remember(guess) { mutableIntStateOf(guess) }
                    val stepDown = { if (step < BOOK_TITLE_SIZES_PX.lastIndex) step++ }
                    LightScrollView(
                        Modifier.fillMaxSize(),
                        scrollBarPosition = if (fits) LightScrollBarPosition.Inside else LightScrollBarPosition.Outside,
                    ) {
                        Spacer(Modifier.height(GRID_TOP_GAP_UNITS.gridUnitsAsDp()))
                        shelves.chunked(columns).forEachIndexed { i, row ->
                            if (i > 0) Spacer(Modifier.height(gap))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                row.forEach { shelf ->
                                    BookTile(shelf.title, side, step, textSize.scale, stepDown, shelf.open)
                                }
                            }
                        }
                        Spacer(Modifier.height(GRID_BOTTOM_GAP_UNITS.gridUnitsAsDp()))
                        lastPlayed?.let { target ->
                            QuietRow("Resume listening", catalog.label(target)) {
                                navigateTo({ PlayerScreen(it, request = null) })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openVolume(volume: Volume) {
        val onlyBook = volume.books.singleOrNull()
        if (onlyBook != null) {
            navigateTo({ ChapterGridScreen(it, volume.id, onlyBook.id) })
        } else {
            navigateTo({ BookListScreen(it, volume.id) })
        }
    }
}

/** A low-key row under the grid: dimmed label, optional dimmed detail on the right. */
@Composable
private fun QuietRow(label: String, detail: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QUIET_ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Fine,
            lighten = true,
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
