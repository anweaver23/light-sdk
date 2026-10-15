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
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.core.Catalog
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

class SearchViewModel : LightViewModel<Unit>() {
    val catalog: Catalog = AppGraph.catalog

    sealed interface State {
        /** Typing the query. */
        data object Editing : State
        data class Searching(val query: String) : State
        data class Done(val query: String, val results: SearchResults) : State
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
        if (Search.parse(query).isEmpty()) return
        job?.cancel()
        _state.value = State.Searching(query)
        job = viewModelScope.launch {
            val results = withContext(Dispatchers.Default) {
                SearchIndex.search(query, SEARCH_RESULT_LIMIT) { ensureActive() }
            }
            _state.value = State.Done(query, results)
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

/** Full-text search over all bundled verses. Opens on the keyboard; results show after submit. */
class SearchScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, SearchViewModel>(sealedActivity) {
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
                Note("Searching…")
            }
            is SearchViewModel.State.Done -> Results(s.query) {
                if (s.results.total == 0) {
                    Note("No verses contain every word of “${s.query}”.")
                } else {
                    ResultList(s.results)
                }
            }
        }
    }

    @Composable
    private fun Editor() {
        val session by viewModel.editorSession.collectAsState()
        val colors by LightThemeController.colors.collectAsState()
        val keyboardOptions = rememberKeyboardOptions()
        key(session) {
            val text = rememberTextFieldState(viewModel.lastQuery)
            LightTheme(colors = colors) {
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
        ScreenFrame(
            title = "Search",
            onBack = { goBack() },
            bottomBar = listOf(LightBarButton.LightIcon(LightIcons.SEARCH, onClick = viewModel::edit)),
        ) {
            // The query itself; tapping it edits it.
            LightText(
                text = query,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = viewModel::edit)
                    .padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            body()
        }
    }

    @Composable
    private fun ResultList(results: SearchResults) {
        val catalog = viewModel.catalog
        LightScrollView(Modifier.fillMaxSize()) {
            Note(if (results.total == 1) "1 verse" else "${results.total} verses")
            results.hits.forEach { hit -> ResultRow(catalog, hit) }
            if (results.more > 0) {
                Note("and ${results.more} more. Add a word to narrow it down.")
            }
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
            LightText(
                text = catalog.verseLabel(hit.ref),
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = Search.snippet(hit.verse, hit.matchStart),
                variant = LightTextVariant.Fine,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
