package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
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
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
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
        val index by AppGraph.library.index.collectAsState()
        ScreenFrame(title = "Hymns", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                AppGraph.catalog.hymnBooks.forEach { book ->
                    val withAudio = index.audioCount("hymns/${book.id}/")
                    ListRow(book.title, if (withAudio > 0) "$withAudio audio" else null) {
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
        val index by AppGraph.library.index.collectAsState()

        ScreenFrame(title = book.title, onBack = { goBack() }) {
            LightLazyScrollView(Modifier.fillMaxSize(), uniformItemHeightGridUnits = ROW_UNITS) {
                items(book.hymns, key = { it.n }) { hymn ->
                    val target = Target.HymnRef(book.id, hymn.n)
                    val detail = listOfNotNull(
                        "audio".takeIf { index.hasAudio(target) },
                        "words".takeIf { index.lyrics(target) != null },
                    ).joinToString(" + ").ifEmpty { null }
                    ListRow("${hymn.n}  ${hymn.title}", detail) {
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
            onBack = { goBack() },
            bottomBar = if (index.hasAudio(target)) {
                listOf(LightBarButton.Text("Listen", onClick = { navigateTo({ PlayerScreen(it, target) }) }))
            } else {
                null
            },
        ) {
            LightScrollView(Modifier.fillMaxSize()) {
                val text = lyrics
                if (text != null) {
                    LightText(text = text.trim(), variant = LightTextVariant.Paragraph, modifier = Modifier.fillMaxWidth())
                } else {
                    Note(
                        "No words saved for this hymn. To add them, upload a text file named " +
                            "\"${hymn.n}.txt\" to Hymn lyrics in the Tool Manager.",
                    )
                    if (!index.hasAudio(target)) {
                        Note("No audio yet either. See Add audio on the home screen.")
                    }
                }
            }
        }
    }
}
