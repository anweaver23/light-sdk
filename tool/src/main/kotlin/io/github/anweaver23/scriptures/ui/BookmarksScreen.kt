package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightScrollView
import io.github.anweaver23.scriptures.core.Bookmark
import io.github.anweaver23.scriptures.core.VerseRef
import io.github.anweaver23.scriptures.core.verseLabel
import io.github.anweaver23.scriptures.data.AppGraph
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Manual bookmarks, newest first. EDIT (bottom bar, as in LightOS Notes) puts a remove mark on each,
 * which asks before removing. Opened from the reader, [here] is the verse at the top of the screen and
 * the first row adds a bookmark there, or removes the one already in that chapter.
 * (Where you left off isn't listed: the app reopens there on launch.)
 */
class BookmarksScreen(sealedActivity: SealedLightActivity, private val here: VerseRef? = null) :
    SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()

    // On the screen, not in remember: the confirm screen takes this one out of composition.
    private var editing by mutableStateOf(false)

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val catalog = AppGraph.catalog
        val bookmarks by AppGraph.study.bookmarks.collectAsState()
        LaunchedEffect(Unit) { AppGraph.study.load() }
        val sorted = bookmarks.sortedByDescending { it.createdAt }
        if (sorted.isEmpty()) editing = false

        ScreenFrame(
            title = "Bookmarks",
            onBack = { goBack() },
            rightButton = nowPlayingButton(),
            bottomBar = if (sorted.isEmpty()) {
                null
            } else {
                listOf(null, LightBarButton.Text(if (editing) "DONE" else "EDIT", onClick = { editing = !editing }), null)
            },
        ) {
            LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
                if (here != null && !editing) HereRow(here, sorted)
                if (sorted.isEmpty()) {
                    Note("Tap the bookmark under a chapter to mark where you are.")
                }
                sorted.forEach { bookmark ->
                    StackRow(
                        title = catalog.verseLabel(bookmark.ref),
                        lines = listOf(formatDate(bookmark.createdAt) to 1),
                        leading = if (editing) {
                            { RemoveMark(catalog.verseLabel(bookmark.ref)) { confirmRemove(bookmark) } }
                        } else {
                            null
                        },
                    ) {
                        if (editing) {
                            confirmRemove(bookmark)
                        } else {
                            navigateTo({ ReaderScreen(it, bookmark.ref.chapterTarget, focusVerse = bookmark.ref.verse) })
                        }
                    }
                }
            }
        }
    }

    /**
     * Add a bookmark at [here], or, when its chapter already has one, remove the one nearest it, so
     * two trips undo each other. Either way it goes straight back to the reader.
     */
    @Composable
    private fun HereRow(here: VerseRef, bookmarks: List<Bookmark>) {
        val label = AppGraph.catalog.verseLabel(here)
        val nearest = bookmarks.filter { it.ref.chapterTarget == here.chapterTarget }.minByOrNull { abs(it.ref.verse - here.verse) }
        if (nearest != null) {
            StackRow(title = "Remove bookmark", lines = listOf(AppGraph.catalog.verseLabel(nearest.ref) to 1)) {
                AppGraph.scope.launch { AppGraph.study.deleteBookmark(nearest.id) }
                goBack()
            }
        } else {
            StackRow(title = "Add bookmark", lines = listOf(label to 1)) {
                AppGraph.scope.launch { AppGraph.study.addBookmark(here) }
                goBack()
            }
        }
    }

    private fun confirmRemove(bookmark: Bookmark) {
        navigateTo({
            ConfirmScreen(
                it,
                title = "Remove bookmark",
                message = "Remove the bookmark at ${AppGraph.catalog.verseLabel(bookmark.ref)}?",
                confirmLabel = "REMOVE",
            )
        }) { confirmed ->
            if (confirmed) AppGraph.scope.launch { AppGraph.study.deleteBookmark(bookmark.id) }
        }
    }
}
