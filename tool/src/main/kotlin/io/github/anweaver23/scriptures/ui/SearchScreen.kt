package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.HymnHit
import io.github.anweaver23.scriptures.core.RefMatch
import io.github.anweaver23.scriptures.core.References
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.core.Search
import io.github.anweaver23.scriptures.core.SearchHit
import io.github.anweaver23.scriptures.core.SearchResults
import io.github.anweaver23.scriptures.core.verseLabel
import io.github.anweaver23.scriptures.data.AppGraph
import io.github.anweaver23.scriptures.data.SearchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Most results listed; the rest are counted in an "and N more" note. */
const val SEARCH_RESULT_LIMIT = Search.DEFAULT_LIMIT

/** Space under each result. */
private const val RESULT_GAP_UNITS = 0.75f

class SearchViewModel : LightViewModel<String>() {
    val catalog: Catalog = AppGraph.catalog

    sealed interface State {
        /** Typing the query. */
        data object Editing : State
        data class Searching(val query: String, val refs: List<RefMatch>) : State
        data class Done(
            val query: String,
            val refs: List<RefMatch>,
            val hymns: List<HymnHit>,
            val results: SearchResults,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Editing)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Bumped every time the editor opens, so it gets a fresh keyboard. */
    private val _editorSession = MutableStateFlow(0)
    val editorSession: StateFlow<Int> = _editorSession.asStateFlow()

    private var job: Job? = null

    /** The query to pre-fill the editor with. */
    var lastQuery: String = ""
        private set

    /** Results to return to if the user backs out of editing a new query. */
    private var previous: State? = null

    fun submit(raw: CharSequence) {
        val query = raw.toString().trim()
        lastQuery = query
        // A reference ("genesis 1") is answered straight away; the verse text is searched as well.
        val refs = References.find(catalog.scriptures, query)
        if (Search.parse(query).isEmpty() && refs.isEmpty()) return
        job?.cancel()
        _state.value = State.Searching(query, refs)
        job = viewModelScope.launch {
            val hymns = withContext(Dispatchers.IO) {
                val index = AppGraph.library.index.value
                Search.searchHymns(catalog.hymnBooks, query) { ref ->
                    index.lyrics(ref)?.let { runCatching { it.readText() }.getOrNull() }
                }
            }
            val results = withContext(Dispatchers.Default) {
                SearchIndex.search(query, SEARCH_RESULT_LIMIT) { ensureActive() }
            }
            _state.value = State.Done(query, refs, hymns, results)
        }
    }

    fun edit() {
        previous = _state.value.takeIf { it is State.Done }
        job?.cancel()
        _editorSession.value++
        _state.value = State.Editing
    }

    /** Leaves the editor. Returns false when there's nothing to go back to, so the screen should close. */
    fun cancelEdit(): Boolean {
        val back = previous ?: return false
        previous = null
        _state.value = back
        return true
    }
}

/**
 * Search: scripture references ("genesis 1"), hymns by title and by their uploaded words, and
 * full-text search over all bundled verses. Opens on the keyboard; results show after submit.
 * Opened from the reader, [findIn] is its chapter: the first result finds the term there instead, and
 * the search returns the term to the reader.
 */
class SearchScreen(sealedActivity: SealedLightActivity, private val findIn: Target.Chapter? = null) :
    LightScreen<String, SearchViewModel>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()

    init {
        AppGraph.init(lightContext)
    }

    override val viewModelClass = SearchViewModel::class.java
    override fun createViewModel() = SearchViewModel()

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        when (val s = state) {
            SearchViewModel.State.Editing -> Editor()
            is SearchViewModel.State.Searching -> Results(s.query) {
                ResultList(s.query, s.refs, emptyList(), null)
            }
            is SearchViewModel.State.Done -> Results(s.query) {
                ResultList(s.query, s.refs, s.hymns, s.results)
            }
        }
    }

    @Composable
    private fun Editor() {
        val session by viewModel.editorSession.collectAsState()
        val keyboardOptions = rememberKeyboardOptions()
        key(session) {
            val text = rememberTextFieldState(viewModel.lastQuery)
            AppTheme {
                LightTextInputEditor(
                    title = "Search",
                    state = text,
                    keyboardOptionsFlow = keyboardOptions,
                    onSubmit = viewModel::submit,
                    onBack = { if (!viewModel.cancelEdit()) goBack() },
                    submitIcon = LightIcons.SEARCH,
                    singleLine = true,
                    editorKey = session,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                )
            }
        }
    }

    @Composable
    private fun Results(query: String, body: @Composable () -> Unit) {
        // The search term, in quotes, is the title; the search icon below edits it.
        ScreenFrame(
            title = "“$query”",
            onBack = { goBack() },
            rightButton = nowPlayingButton(),
            bottomBar = listOf(LightBarButton.LightIcon(LightIcons.SEARCH, onClick = viewModel::edit)),
        ) {
            body()
        }
    }

    /**
     * Find in the reader's chapter (when opened from it), then references (books, chapters, verses the
     * query names), hymns, and verses containing the words.
     */
    @Composable
    private fun ResultList(query: String, refs: List<RefMatch>, hymns: List<HymnHit>, results: SearchResults?) {
        val catalog = viewModel.catalog
        LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
            findIn?.let { chapter ->
                StackRow(title = "Find in this chapter", lines = listOf(catalog.label(chapter) to 1)) { goBack(query) }
            }
            refs.forEach { ref -> ReferenceRow(catalog, ref) }
            if (hymns.isNotEmpty()) {
                Note(if (hymns.size == 1) "1 hymn" else "${hymns.size} hymns")
                hymns.forEach { hit -> HymnRow(catalog, hit) }
            }
            when {
                results == null -> Note("Searching…")
                results.total == 0 && refs.isEmpty() && hymns.isEmpty() -> Note("Nothing contains every word of “$query”.")
                results.total == 0 -> Unit
                else -> {
                    Note(if (results.total == 1) "1 verse" else "${results.total} verses")
                    results.hits.forEach { hit -> ResultRow(catalog, hit) }
                    if (results.more > 0) {
                        Note("and ${results.more} more. Add a word to narrow it down.")
                    }
                }
            }
        }
    }

    /** A book opens its chapter list; a chapter or verse opens the reader there. */
    @Composable
    private fun ReferenceRow(catalog: Catalog, ref: RefMatch) {
        when (ref) {
            is RefMatch.BookRef -> ListRow(ref.book.title, catalog.scriptures.volume(ref.volumeId)?.title) {
                if (ref.book.chapters == 1) {
                    navigateTo({ ReaderScreen(it, Target.Chapter(ref.volumeId, ref.book.id, 1)) })
                } else {
                    navigateTo({ ChapterGridScreen(it, ref.volumeId, ref.book.id) })
                }
            }
            is RefMatch.ChapterRef -> ListRow(catalog.label(ref.target)) {
                navigateTo({ ReaderScreen(it, ref.target) })
            }
            is RefMatch.VerseRefMatch -> ListRow(catalog.verseLabel(ref.ref)) {
                navigateTo({ ReaderScreen(it, ref.ref.chapterTarget, focusVerse = ref.ref.verse) })
            }
        }
    }

    /** A hymn: its number and title, then where its words matched (or its hymnbook, for a title match). */
    @Composable
    private fun HymnRow(catalog: Catalog, hit: HymnHit) {
        Column(
            Modifier
                .fillMaxWidth()
                .lightClickable { navigateTo({ HymnScreen(it, hit.ref) }) }
                .padding(bottom = RESULT_GAP_UNITS.gridUnitsAsDp()),
        ) {
            AppText(
                text = "${hit.ref.number}. ${hit.title}",
                variant = ROW_TEXT,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = hit.snippet ?: catalog.hymnBook(hit.ref.hymnBookId)?.title.orEmpty(),
                variant = LightTextVariant.Fine,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun ResultRow(catalog: Catalog, hit: SearchHit) {
        Column(
            Modifier
                .fillMaxWidth()
                .lightClickable {
                    navigateTo({ ReaderScreen(it, hit.ref.chapterTarget, focusVerse = hit.ref.verse) })
                }
                .padding(bottom = RESULT_GAP_UNITS.gridUnitsAsDp()),
        ) {
            AppText(
                text = catalog.verseLabel(hit.ref),
                variant = ROW_TEXT,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = Search.snippet(hit.verse, hit.matchStart),
                variant = LightTextVariant.Fine,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
