package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
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
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HymnBooksScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        ScreenFrame(title = "Hymns", onBack = { goBack() }, rightButton = pageMenu(), bottomBar = navBar()) {
            LightScrollView(Modifier.fillMaxSize()) {
                AppGraph.catalog.hymnBooks.forEach { book ->
                    ListRow(book.title) {
                        navigateTo({ HymnListScreen(it, book.id) })
                    }
                }
            }
        }
    }
}

class HymnListScreen(sealedActivity: SealedLightActivity, private val hymnBookId: String) :
    SimpleLightScreen<Unit>(sealedActivity) {

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val book = AppGraph.catalog.hymnBook(hymnBookId) ?: return

        // Long titles: the denser Subheading list, so more of each shows.
        ScreenFrame(title = book.title, onBack = { goBack() }, rightButton = pageMenu(), bottomBar = navBar()) {
            LightLazyScrollView(Modifier.fillMaxSize(), uniformItemHeightGridUnits = DENSE_ROW_UNITS) {
                items(book.hymns, key = { it.n }) { hymn ->
                    val target = Target.HymnRef(book.id, hymn.n)
                    ListRow("${hymn.n}  ${hymn.title}", variant = DENSE_ROW_TEXT, heightUnits = DENSE_ROW_UNITS) {
                        navigateTo({ HymnScreen(it, target) })
                    }
                }
            }
        }
    }
}

/** A hymn's words (if the user uploaded them) with a button to play its audio. */
class HymnScreen(sealedActivity: SealedLightActivity, private val target: Target.HymnRef) :
    SimpleLightScreen<Unit>(sealedActivity) {

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val hymn = AppGraph.catalog.hymnBook(target.hymnBookId)?.hymn(target.number) ?: return
        val index by AppGraph.library.index.collectAsState()
        val lyricsFile = index.lyrics(target)
        var lyrics by remember(lyricsFile) { mutableStateOf<String?>(null) }
        LaunchedEffect(lyricsFile) {
            lyrics = lyricsFile?.let { withContext(Dispatchers.IO) { runCatching { it.readText() }.getOrNull() } }
        }

        ScreenFrame(
            title = "${hymn.n}. ${hymn.title}",
            reading = true,
            onBack = { goBack() },
            rightButton = pageMenu(),
            // Both recordings: VOCAL and MUSIC ONLY (accompaniment). One: LISTEN plays it.
            bottomBar = index.versions(target).let { versions ->
                fun play(version: Target.HymnRef) = { navigateTo({ PlayerScreen(it, version) }) }
                when (versions.size) {
                    0 -> null
                    1 -> listOf(LightBarButton.Text("LISTEN", onClick = play(versions.single())))
                    else -> listOf(
                        LightBarButton.Text("VOCAL", onClick = play(versions[0])),
                        LightBarButton.Text("MUSIC ONLY", onClick = play(versions[1])),
                    )
                }
            },
        ) {
            LightScrollView(Modifier.fillMaxSize()) {
                val text = lyrics
                if (text != null) {
                    // Hymn words are reading text: Display size, spacing and invert apply.
                    val display by AppGraph.display.collectAsState()
                    BasicText(
                        text = text.trim(),
                        style = readerTextStyle(display.textSize.scale, display.lineSpacing),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Note(
                        "No words saved for this hymn. To add them, upload a text file named " +
                            "\"${hymn.n}.txt\" to Hymn lyrics in the Tool Manager.",
                    )
                    if (index.versions(target).isEmpty()) {
                        Note("No audio yet either. Open the … menu and choose Add audio.")
                    }
                }
            }
        }
    }
}
