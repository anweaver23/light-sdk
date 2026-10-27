package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.data.AppGraph

/**
 * Picks a chapter (volume, book, chapter) or a hymn (hymnbook, hymn) and returns it with
 * goBack(target). Back steps up one level; backing out of the top level returns nothing.
 */
class TargetPickerScreen(sealedActivity: SealedLightActivity, private val mode: Mode) :
    SimpleLightScreen<Target>(sealedActivity) {

    enum class Mode { Chapter, Hymn }

    init {
        AppGraph.init(lightContext)
    }

    // Selections so far: volume id then book id, or a hymnbook id.
    private var path by mutableStateOf(emptyList<String>())

    /**
     * The top bar's back steps up a level before leaving the picker. (The system back gesture
     * goes straight to LightActivity and closes the whole picker; the SDK doesn't route it here.)
     */
    override fun goBack(result: Target?) {
        if (result == null && path.isNotEmpty()) {
            path = path.dropLast(1)
        } else {
            super.goBack(result)
        }
    }

    @Composable
    override fun Content() {
        val catalog = AppGraph.catalog
        val back = { goBack() }

        when (mode) {
            Mode.Chapter -> {
                val volume = path.getOrNull(0)?.let(catalog.scriptures::volume)
                val book = path.getOrNull(1)?.let(catalog.scriptures::book)
                when {
                    volume == null -> ScreenFrame("Pick a volume", back) {
                        LightScrollView(Modifier.fillMaxSize()) {
                            catalog.scriptures.volumes.forEach { v ->
                                // D&C is a single book, so skip straight to its sections.
                                ListRow(v.title) { path = listOfNotNull(v.id, v.books.singleOrNull()?.id) }
                            }
                        }
                    }
                    book == null -> ScreenFrame(volume.title, back) {
                        LightScrollView(Modifier.fillMaxSize()) {
                            volume.books.forEach { b ->
                                ListRow(b.title) {
                                    if (b.chapters == 1) {
                                        goBack(Target.Chapter(volume.id, b.id, 1))
                                    } else {
                                        path = path + b.id
                                    }
                                }
                            }
                        }
                    }
                    else -> ScreenFrame(book.title, back) {
                        NumberGrid((1..book.chapters).toList(), isMarked = { false }) { chapter ->
                            goBack(Target.Chapter(volume.id, book.id, chapter))
                        }
                    }
                }
            }
            Mode.Hymn -> {
                val hymnBook = path.getOrNull(0)?.let(catalog::hymnBook)
                if (hymnBook == null) {
                    ScreenFrame("Pick a hymnbook", back) {
                        LightScrollView(Modifier.fillMaxSize()) {
                            catalog.hymnBooks.forEach { b -> ListRow(b.title) { path = listOf(b.id) } }
                        }
                    }
                } else {
                    ScreenFrame(hymnBook.title, back) {
                        LightLazyScrollView(Modifier.fillMaxSize(), uniformItemHeightGridUnits = ROW_UNITS) {
                            items(hymnBook.hymns, key = { it.n }) { hymn ->
                                ListRow("${hymn.n}  ${hymn.title}") { goBack(Target.HymnRef(hymnBook.id, hymn.n)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
