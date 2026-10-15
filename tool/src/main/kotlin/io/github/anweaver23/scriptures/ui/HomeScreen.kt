package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.min
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
import com.thelightphone.sdk.ui.LightSurfaceScheme
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.R
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Volume
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Home grid layout. Units are Light grid units (1/27 of the screen width).

/** Book proportions, width / height. A little squatter than a real 2:3 book so titles fit. */
const val TILE_ASPECT = 0.75f

/**
 * Smallest tile height. Below this the grid scrolls instead of shrinking. With both quiet rows
 * showing, tiles come out at about 5.9 units, so on the LP3 the home screen never scrolls.
 */
const val TILE_MIN_HEIGHT_UNITS = 5f

/** Space between the two books in a row. */
const val TILE_COLUMN_GAP_UNITS = 2.5f

/** Space between rows of books. */
const val TILE_ROW_GAP_UNITS = 0.5f

/** Space above the grid and between the grid and the quiet rows below it. */
const val GRID_TOP_GAP_UNITS = 0.25f
const val GRID_BOTTOM_GAP_UNITS = 0.5f

/** Height of the secondary rows (Resume listening, Add audio). Shorter than a [ListRow] on purpose. */
const val QUIET_ROW_UNITS = 2.25f

private const val GRID_COLUMNS = 2
private val ROUNDING_SLACK = 2.dp

class HomeViewModel : LightViewModel<Unit>() {
    val catalog: Catalog = AppGraph.catalog
    val lastPlayed = AppGraph.progress.lastPlayed.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pending = AppGraph.importer.pending

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
        val pending by viewModel.pending.collectAsState()
        val catalog = viewModel.catalog
        val colors by LightThemeController.colors.collectAsState()

        val shelves = catalog.scriptures.volumes.map { volume -> Shelf(volume.title) { openVolume(volume) } } +
            Shelf("Hymns") { navigateTo(::HymnBooksScreen) }

        // ScreenFrame applies the theme too; this outer one is only so the bar can pick the
        // bookmark glyph's colour from the surface scheme.
        LightTheme(colors = colors) {
            val bookmarkIcon = if (LightThemeTokens.surfaceScheme == LightSurfaceScheme.Dark) {
                R.drawable.ic_bookmark_white
            } else {
                R.drawable.ic_bookmark_black
            }
            ScreenFrame(
                title = "Scriptures",
                onBack = null,
                bottomBar = listOf(
                    LightBarButton.LightIcon(LightIcons.SEARCH, onClick = { navigateTo(::SearchScreen) }),
                    LightBarButton.Icon(
                        painter = painterResource(bookmarkIcon),
                        onClick = { navigateTo({ BookmarksScreen(it) }) },
                        contentDescription = "Bookmarks",
                    ),
                    LightBarButton.LightIcon(
                        LightIcons.PENCIL,
                        onClick = { navigateTo({ AnnotationsScreen(it) }) },
                        contentDescription = "Annotations",
                    ),
                ),
            ) {
                val quietRows = 1 + (if (lastPlayed != null) 1 else 0)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val tile = tileSize(maxWidth, maxHeight, quietRows)
                    val guess = rememberSharedTitleStep(shelves.map { it.title }, tile.width)
                    // One title size for every tile. If a tile's rendered title still breaks
                    // mid-word, all tiles step down together.
                    var step by remember(guess) { mutableIntStateOf(guess) }
                    val stepDown = { if (step < BOOK_TITLE_SIZES_PX.lastIndex) step++ }
                    // Normally everything fits, nothing scrolls, and no gutter is reserved, so the
                    // books stay centred. If it can't fit, reserve the gutter so the bar never
                    // draws over the rows.
                    val barPosition = if (tile.scrolls) LightScrollBarPosition.Outside else LightScrollBarPosition.Inside
                    LightScrollView(Modifier.fillMaxSize(), scrollBarPosition = barPosition) {
                        Spacer(Modifier.height(GRID_TOP_GAP_UNITS.gridUnitsAsDp()))
                        shelves.chunked(GRID_COLUMNS).forEachIndexed { i, row ->
                            if (i > 0) Spacer(Modifier.height(TILE_ROW_GAP_UNITS.gridUnitsAsDp()))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(
                                    TILE_COLUMN_GAP_UNITS.gridUnitsAsDp(),
                                    Alignment.CenterHorizontally,
                                ),
                            ) {
                                row.forEach { shelf ->
                                    BookTile(shelf.title, tile.width, tile.height, step, stepDown, shelf.open)
                                }
                                // Keep a lone last book in the left column.
                                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.width(tile.width)) }
                            }
                        }
                        Spacer(Modifier.height(GRID_BOTTOM_GAP_UNITS.gridUnitsAsDp()))
                        lastPlayed?.let { target ->
                            QuietRow("Resume listening", catalog.label(target)) {
                                navigateTo({ PlayerScreen(it, request = null) })
                            }
                        }
                        QuietRow("Add audio", if (pending.isEmpty()) null else "${pending.size} to review") {
                            navigateTo(::AddAudioScreen)
                        }
                    }
                }
            }
        }
    }

    /**
     * Tile (width, height): as tall as possible so the grid and the quiet rows fit without
     * scrolling, but never shorter than [TILE_MIN_HEIGHT_UNITS] or wider than a column allows.
     */
    private class TileSize(val width: Dp, val height: Dp, val scrolls: Boolean)

    @Composable
    private fun tileSize(maxWidth: Dp, maxHeight: Dp, quietRows: Int): TileSize {
        val rows = 3
        val reserved = (GRID_TOP_GAP_UNITS + GRID_BOTTOM_GAP_UNITS + quietRows * QUIET_ROW_UNITS +
            (rows - 1) * TILE_ROW_GAP_UNITS).gridUnitsAsDp()
        // A couple of dp of slack so pixel rounding can't tip a fitting layout into scrolling.
        val fitHeight = (maxHeight - reserved - ROUNDING_SLACK) / rows
        val columnWidth = (maxWidth - TILE_COLUMN_GAP_UNITS.gridUnitsAsDp()) / GRID_COLUMNS
        val minHeight = TILE_MIN_HEIGHT_UNITS.gridUnitsAsDp()
        val height = min(fitHeight, columnWidth / TILE_ASPECT).coerceAtLeast(minHeight)
        return TileSize(width = height * TILE_ASPECT, height = height, scrolls = fitHeight < minHeight)
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
