package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.data.AppGraph
import io.github.anweaver23.scriptures.data.AudioLibrary

/** Number of files under a key prefix, e.g. how many chapters of Alma have audio. */
fun AudioLibrary.Index.audioCount(prefix: String): Int = audio.keys.count { it.startsWith(prefix) }

/** The books of one volume. */
class BookListScreen(sealedActivity: SealedLightActivity, private val volumeId: String) :
    SimpleLightScreen<Unit>(sealedActivity) {

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val volume = AppGraph.catalog.scriptures.volume(volumeId) ?: return
        val index by AppGraph.library.index.collectAsState()

        ScreenFrame(title = volume.title, onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
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

/** Chapter numbers in a grid. Underlined numbers have audio. */
class ChapterGridScreen(
    sealedActivity: SealedLightActivity,
    private val volumeId: String,
    private val bookId: String,
) : SimpleLightScreen<Unit>(sealedActivity) {

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val book = AppGraph.catalog.scriptures.book(bookId) ?: return
        val index by AppGraph.library.index.collectAsState()

        ScreenFrame(title = book.title, onBack = { goBack() }) {
            if (index.audioCount("scriptures/$volumeId/$bookId/") > 0) {
                Note("Underlined ${book.chapterLabel.lowercase()}s have audio.")
            }
            NumberGrid(
                numbers = (1..book.chapters).toList(),
                isMarked = { index.hasAudio(Target.Chapter(volumeId, bookId, it)) },
            ) { chapter ->
                navigateTo({ ReaderScreen(it, Target.Chapter(volumeId, bookId, chapter)) })
            }
        }
    }
}

private const val GRID_COLUMNS = 5

@Composable
fun NumberGrid(numbers: List<Int>, isMarked: (Int) -> Boolean, onClick: (Int) -> Unit) {
    LightScrollView(Modifier.fillMaxSize()) {
        numbers.chunked(GRID_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                row.forEach { number ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(ROW_UNITS.gridUnitsAsDp())
                            .lightClickable { onClick(number) },
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = number.toString(),
                            variant = LightTextVariant.Copy,
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
