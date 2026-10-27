package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Volume
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Home grid layout. Units are Light grid units (1/27 of the screen width).

/**
 * Space between tiles, across and down. Tiles are square; their side is whatever the columns leave.
 * Home is "system" UI: it never follows the Display text size.
 */
const val TILE_GAP_UNITS = 1f

/** Books per row. Titles shrink from 30 until no word breaks ([rememberSharedTitleStep]). */
const val TILE_COLUMNS = 2

/** Space above and below the grid. Home is books only: nothing sits under the grid. */
const val GRID_TOP_GAP_UNITS = 0.25f
const val GRID_BOTTOM_GAP_UNITS = 0.5f

/** Width LightScrollView keeps for its bar when it's [LightScrollBarPosition.Outside] (SDK constant). */
private const val SCROLL_GUTTER_UNITS = 2f

class HomeViewModel : LightViewModel<Unit>() {
    val catalog: Catalog = AppGraph.catalog

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
        val catalog = viewModel.catalog

        // On launch, open the scriptures where the user left off (Back returns here).
        LaunchedEffect(Unit) {
            if (AppGraph.resumedReading) return@LaunchedEffect
            AppGraph.resumedReading = true
            val chapter = AppGraph.progress.lastRead.first() ?: return@LaunchedEffect
            val verse = AppGraph.progress.lastReadVerse.first()?.verse
            navigateTo({ ReaderScreen(it, chapter, focusVerse = verse, quietFocus = true) })
        }

        val shelves = catalog.scriptures.volumes.map { volume -> Shelf(volume.title) { openVolume(volume) } } +
            Shelf("Hymns") { navigateTo(::HymnBooksScreen) }

        // ScreenFrame applies the theme too; this outer one is only so the bookmark glyph picks up
        // the theme's content colour.
        AppTheme {
            ScreenFrame(
                title = "Scriptures",
                onBack = null,
                rightButton = pageMenu(),
                bottomBar = navBar(),
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val gap = TILE_GAP_UNITS.gridUnitsAsDp()
                    val columns = TILE_COLUMNS
                    val rows = (shelves.size + columns - 1) / columns
                    // If the squares fit without scrolling, use the full width (no scroll bar gutter);
                    // otherwise leave room for the gutter so the last column isn't clipped.
                    val fullSide = (maxWidth - gap * (columns - 1)) / columns
                    val reserved = (GRID_TOP_GAP_UNITS + GRID_BOTTOM_GAP_UNITS).gridUnitsAsDp()
                    val fits = fullSide * rows + gap * (rows - 1) + reserved <= maxHeight
                    val side = if (fits) fullSide else (maxWidth - SCROLL_GUTTER_UNITS.gridUnitsAsDp() - gap * (columns - 1)) / columns
                    val guess = rememberSharedTitleStep(shelves.map { it.title }, side)
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
                                    BookTile(shelf.title, side, step, stepDown, shelf.open)
                                }
                            }
                        }
                        Spacer(Modifier.height(GRID_BOTTOM_GAP_UNITS.gridUnitsAsDp()))
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

