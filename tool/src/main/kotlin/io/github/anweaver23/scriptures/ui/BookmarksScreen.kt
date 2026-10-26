package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.core.verseLabel
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.launch

/**
 * Manual bookmarks, newest first. "Remove bookmarks" in the menu shows a remove control on each.
 * (Where you left off isn't listed: the app reopens there on launch.)
 */
class BookmarksScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val catalog = AppGraph.catalog
        val bookmarks by AppGraph.study.bookmarks.collectAsState()
        var editing by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { AppGraph.study.load() }
        val sorted = bookmarks.sortedByDescending { it.createdAt }

        ScreenFrame(
            title = "Bookmarks",
            onBack = { goBack() },
            rightButton = if (editing && sorted.isNotEmpty()) {
                LightBarButton.Text("DONE", onClick = { editing = false })
            } else {
                pageMenu(
                    listOfNotNull(
                        MenuItem("Remove bookmarks") { editing = true }
                            .takeIf { sorted.isNotEmpty() },
                    ),
                )
            },
        ) {
            LightScrollView(Modifier.fillMaxSize()) {
                if (sorted.isEmpty()) {
                    Note("Open the … menu in the reader and choose Bookmark to mark where you are.")
                }
                sorted.forEach { bookmark ->
                    StackRow(
                        title = catalog.verseLabel(bookmark.ref),
                        lines = emptyList(),
                        trailing = if (editing) {
                            {
                                LightIcon(
                                    LightIcons.CLOSE,
                                    contentDescription = "Remove",
                                    modifier = Modifier.lightClickable {
                                        AppGraph.scope.launch { AppGraph.study.deleteBookmark(bookmark.id) }
                                    },
                                )
                            }
                        } else {
                            null
                        },
                    ) {
                        navigateTo({ ReaderScreen(it, bookmark.ref.chapterTarget, focusVerse = bookmark.ref.verse) })
                    }
                }
            }
        }
    }
}
