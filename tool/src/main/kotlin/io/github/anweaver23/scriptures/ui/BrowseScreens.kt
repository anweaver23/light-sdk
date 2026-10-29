package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.data.AppGraph
import io.github.anweaver23.scriptures.data.AudioLibrary

/** Number of files under a key prefix, e.g. how many chapters of Alma have audio. */
fun AudioLibrary.Index.audioCount(prefix: String): Int =
    // A hymn's two recordings count once.
    audio.keys.filter { it.startsWith(prefix) }.map { it.removeSuffix("/accompaniment") }.distinct().size

/**
 * The books of one volume. Swiping turns to the next or previous volume (no wrapping round). A volume
 * of one book (the Doctrine and Covenants) shows that book's chapters instead, as home opens it.
 */
class BookListScreen(sealedActivity: SealedLightActivity, volumeId: String) :
    SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack; one per volume. */
    private val scrolls = ScrollMemory()

    private var volumeId by mutableStateOf(volumeId)

    private fun neighbor(direction: Int): String? {
        val volumes = AppGraph.catalog.scriptures.volumes
        return volumes.getOrNull(volumes.indexOfFirst { it.id == volumeId } + direction)?.id
    }

    private val pages = PageSlider(
        canTurn = { neighbor(it) != null },
        turn = { direction -> neighbor(direction)?.let { this.volumeId = it } },
    )


    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val volume = AppGraph.catalog.scriptures.volume(volumeId) ?: return
        val index by AppGraph.library.index.collectAsState()

        ScreenFrame(title = volume.title, onBack = { goBack() }, rightButton = nowPlayingButton(), bottomBar = navBar()) {
            SwipePages(pages) {
            key(volume.id) {
            val onlyBook = volume.books.singleOrNull()
            if (onlyBook != null) {
                ChapterNumbers(volume.id, onlyBook.id, scrolls.scroll(volume.id))
            } else LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll(volume.id)) {
                volume.books.forEach { book ->
                    val withAudio = index.audioCount("scriptures/${volume.id}/${book.id}/")
                    ListRow(book.title, if (withAudio > 0) "$withAudio audio" else null) {
                        if (book.chapters == 1) {
                            navigateTo({ ReaderScreen(it, Target.Chapter(volume.id, book.id, 1)) })
                        } else {
                            navigateTo({ ChapterGridScreen(it, volume.id, book.id) })
                        }
                    }
                }
            }
            }
            }
        }
    }
}

/**
 * Chapter numbers in a grid. Underlined numbers have audio. Swiping turns to the next or previous
 * book of the same volume (not into the next volume, and no wrapping round).
 */
class ChapterGridScreen(
    sealedActivity: SealedLightActivity,
    private val volumeId: String,
    bookId: String,
) : SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack; one per book. */
    private val scrolls = ScrollMemory()

    private var bookId by mutableStateOf(bookId)

    private fun neighbor(direction: Int): String? {
        val books = AppGraph.catalog.scriptures.volume(volumeId)?.books.orEmpty()
        return books.getOrNull(books.indexOfFirst { it.id == bookId } + direction)?.id
    }

    private val pages = PageSlider(
        canTurn = { neighbor(it) != null },
        turn = { direction -> neighbor(direction)?.let { this.bookId = it } },
    )


    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val book = AppGraph.catalog.scriptures.book(bookId) ?: return
        val index by AppGraph.library.index.collectAsState()

        ScreenFrame(title = book.title, onBack = { goBack() }, rightButton = nowPlayingButton(), bottomBar = navBar()) {
            SwipePages(pages) {
                key(book.id) { ChapterNumbers(volumeId, book.id, scrolls.scroll(book.id)) }
            }
        }
    }
}

private const val GRID_COLUMNS = 5

/** A book's chapter numbers; one opens the reader there. Underlined numbers have audio. */
@Composable
private fun SimpleLightScreen<*>.ChapterNumbers(volumeId: String, bookId: String, scrollState: ScrollState) {
    val book = AppGraph.catalog.scriptures.book(bookId) ?: return
    val index by AppGraph.library.index.collectAsState()
    NumberGrid(
        numbers = (1..book.chapters).toList(),
        scrollState = scrollState,
        isMarked = { index.hasAudio(Target.Chapter(volumeId, bookId, it)) },
    ) { chapter ->
        navigateTo({ ReaderScreen(it, Target.Chapter(volumeId, bookId, chapter)) })
    }
}


@Composable
fun NumberGrid(
    numbers: List<Int>,
    isMarked: (Int) -> Boolean,
    scrollState: ScrollState = rememberScrollState(),
    onClick: (Int) -> Unit,
) {
    LightScrollView(Modifier.fillMaxSize(), scrollState = scrollState) {
        numbers.chunked(GRID_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                row.forEach { number ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(ROW_UNITS.gridUnitsAsDp())
                            .lightClickable { onClick(number) },
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        AppText(
                            text = number.toString(),
                            variant = ROW_TEXT,
                            align = TextAlign.Center,
                            underline = isMarked(number),
                        )
                    }
                }
                // Keep the last row's cells the same width as the rows above.
                repeat(GRID_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
