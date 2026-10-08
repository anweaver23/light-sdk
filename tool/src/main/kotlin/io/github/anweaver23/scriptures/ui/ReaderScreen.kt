package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderViewModel(start: Target.Chapter) : LightViewModel<Unit>() {
    val catalog: Catalog = AppGraph.catalog
    val library = AppGraph.library.index

    private val _chapter = MutableStateFlow(start)
    val chapter: StateFlow<Target.Chapter> = _chapter.asStateFlow()

    private val _verses = MutableStateFlow<List<String>?>(null)
    val verses: StateFlow<List<String>?> = _verses.asStateFlow()

    init {
        load(start)
    }

    fun previous() {
        neighbor(-1)?.let(::load)
    }

    fun next() {
        neighbor(1)?.let(::load)
    }

    fun hasPrevious() = neighbor(-1) != null
    fun hasNext() = neighbor(1) != null

    private fun load(target: Target.Chapter) {
        _chapter.value = target
        _verses.value = null
        viewModelScope.launch {
            _verses.value = withContext(Dispatchers.IO) {
                AppGraph.bookText(target.volumeId, target.bookId).c.getOrNull(target.chapter - 1).orEmpty()
            }
            AppGraph.progress.setLastRead(target)
        }
    }

    /** The chapter before or after this one, crossing into the neighbouring book of the same volume. */
    private fun neighbor(delta: Int): Target.Chapter? {
        val current = _chapter.value
        val books = catalog.scriptures.volume(current.volumeId)?.books ?: return null
        val bookIndex = books.indexOfFirst { it.id == current.bookId }
        val book = books.getOrNull(bookIndex) ?: return null
        val chapter = current.chapter + delta
        return when {
            chapter in 1..book.chapters -> current.copy(chapter = chapter)
            delta < 0 -> books.getOrNull(bookIndex - 1)?.let { current.copy(bookId = it.id, chapter = it.chapters) }
            else -> books.getOrNull(bookIndex + 1)?.let { current.copy(bookId = it.id, chapter = 1) }
        }
    }
}

class ReaderScreen(sealedActivity: SealedLightActivity, private val start: Target.Chapter) :
    LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    init {
        AppGraph.init(lightContext)
    }

    override val viewModelClass = ReaderViewModel::class.java
    override fun createViewModel() = ReaderViewModel(start)

    @Composable
    override fun Content() {
        val chapter by viewModel.chapter.collectAsState()
        val verses by viewModel.verses.collectAsState()
        val index by viewModel.library.collectAsState()
        val hasAudio = index.hasAudio(chapter)

        ScreenFrame(
            title = viewModel.catalog.label(chapter),
            onBack = { goBack() },
            bottomBar = listOf(
                LightBarButton.Text("Prev", onClick = viewModel::previous.takeIf { viewModel.hasPrevious() }),
                if (hasAudio) LightBarButton.Text("Listen", onClick = { navigateTo({ PlayerScreen(it, chapter) }) }) else null,
                LightBarButton.Text("Next", onClick = viewModel::next.takeIf { viewModel.hasNext() }),
            ),
        ) {
            // A fresh scroll position for every chapter.
            key(chapter) {
                LightScrollView(Modifier.fillMaxSize()) {
                    verses?.forEachIndexed { i, verse ->
                        LightText(
                            text = "${i + 1}  $verse",
                            variant = LightTextVariant.Paragraph,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 0.75f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
}
