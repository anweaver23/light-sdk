package io.github.anweaver23.scriptures.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.SimpleLightScreen
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.andyweaver.scriptures.R
import io.github.anweaver23.scriptures.core.Annotation
import io.github.anweaver23.scriptures.core.Annotations
import io.github.anweaver23.scriptures.core.Bookmark
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Find
import io.github.anweaver23.scriptures.core.FindMatch
import io.github.anweaver23.scriptures.core.Footnote
import io.github.anweaver23.scriptures.core.MarkerSpan
import io.github.anweaver23.scriptures.core.Selection
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.core.VerseDisplay
import io.github.anweaver23.scriptures.core.VerseRef
import io.github.anweaver23.scriptures.core.WordPos
import io.github.anweaver23.scriptures.core.WordRange
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.core.quoteOf
import io.github.anweaver23.scriptures.core.topVisibleVerse
import io.github.anweaver23.scriptures.data.AppGraph
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- Look of study marks. Monochrome, drawn as translucent content colour so they read on AMOLED
// black (a soft grey) and on the light theme alike. ---

/** Background of a saved highlight. Subtle: the text should still read as plain text. */
const val HIGHLIGHT_ALPHA = 0.22f

/** Background of the live selection; clearly stronger than a highlight. */
const val SELECTION_ALPHA = 0.45f

/** Peak background of a verse opened from a bookmark, note, footnote or search, before it fades. */
const val FOCUS_ALPHA = 0.16f
const val FOCUS_HOLD_MS = 500L
const val FOCUS_FADE_MS = 1400

/** Background of find-in-chapter matches, and of the one the find bar is on. */
const val FIND_ALPHA = 0.3f
const val FIND_CURRENT_ALPHA = 0.6f

/** Footnote markers, relative to the verse text. */
private const val MARKER_SIZE_EM = 0.62f

/** Extra room around a footnote marker's glyph that still counts as tapping it, in px. */
private const val MARKER_TOUCH_SLOP_PX = 28f

/** Extra room either side of a word that still counts as tapping it, in px. */
private const val WORD_TOUCH_SLOP_PX = 6f

/** Longest wait for the next chapter's text before sliding it in anyway. */
private const val PAGE_LOAD_WAIT_MS = 1500L

/** Space between verses at normal line spacing, in grid units. */
internal const val VERSE_GAP_UNITS = 0.75f

/** Space above a focused verse when scrolling to it, in grid units. */
private const val FOCUS_TOP_MARGIN_UNITS = 0.5f

/** What a tap on the text should do. */
sealed interface TapAction {
    data object None : TapAction
    data class OpenAnnotation(val annotation: Annotation) : TapAction
    data class OpenFootnote(val ref: VerseRef, val footnote: Footnote) : TapAction
}

/**
 * Ask the screen to scroll to [verse] and mark it; [id] makes repeated requests distinct. With
 * [offset] (a display character, used by find) it brings that line into view instead, scrolling
 * if it's off screen, and doesn't mark the verse. Without [flash] it scrolls but doesn't mark (used
 * when reopening where the reader left off).
 */
data class FocusRequest(val verse: Int, val id: Long, val offset: Int? = null, val flash: Boolean = true)

/** Find in chapter: what was searched, every hit, and which one the find bar is on. */
data class FindState(val query: String, val matches: List<FindMatch>, val current: Int) {
    val currentMatch: FindMatch? get() = matches.getOrNull(current)
}

class ReaderViewModel(
    start: Target.Chapter,
    focusVerse: Int?,
    quietFocus: Boolean = false,
    audio: LightAudio,
) : LightViewModel<Unit>() {
    val catalog: Catalog = AppGraph.catalog
    val library = AppGraph.library.index
    private val study = AppGraph.study

    /**
     * The chapter's audio plays here, as a hymn's does on its page: no player screen. It shares this
     * view model's scope and screen events; it adopts playback that's already live.
     */
    val player = PlayerViewModel(audio, request = null, autoStart = false, outerScope = viewModelScope)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        player.onScreenShow(screen)
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        player.onScreenHide(screen)
        super.onScreenHide(screen)
    }

    override fun onAppPause() {
        player.onAppPause()
        super.onAppPause()
    }

    override fun onCleared() {
        player.close()
        super.onCleared()
    }

    private val _chapter = MutableStateFlow(start)
    val chapter: StateFlow<Target.Chapter> = _chapter.asStateFlow()

    private val _verses = MutableStateFlow<List<String>?>(null)
    val verses: StateFlow<List<String>?> = _verses.asStateFlow()

    /** The verses as drawn (with footnote markers), built with [verses]. */
    private val _displays = MutableStateFlow<List<VerseDisplay>?>(null)
    val displays: StateFlow<List<VerseDisplay>?> = _displays.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    val chapterAnnotations: StateFlow<List<Annotation>> =
        combine(study.annotations, _chapter) { all, ch -> all.filter { it.isIn(ch) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val chapterBookmarks: StateFlow<List<Bookmark>> =
        combine(study.bookmarks, _chapter) { all, ch -> all.filter { it.ref.chapterTarget == ch } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Lives here so the position survives opening Define/Note and coming back. New per chapter. */
    var scrollState: ScrollState by mutableStateOf(ScrollState(0))
        private set

    private var focusCounter = 0L
    private val _focus = MutableStateFlow<FocusRequest?>(null)
    val focus: StateFlow<FocusRequest?> = _focus.asStateFlow()

    private val _find = MutableStateFlow<FindState?>(null)
    val find: StateFlow<FindState?> = _find.asStateFlow()

    /** The verse at the top of the screen, reported by the UI as the user scrolls. */
    private val _topVerse = MutableStateFlow<VerseRef?>(null)

    init {
        viewModelScope.launch { study.load() }
        saveReadingPosition()
        load(start, focusVerse, quietFocus)
    }

    fun previous() {
        neighbor(-1)?.let { load(it, null) }
    }

    /** Whether there's a chapter to turn to in [delta]'s direction (1 = next, -1 = previous). */
    fun canTurn(delta: Int) = neighbor(delta) != null

    /** Turns straight to [target] (playback moved on, or the now-playing button). */
    fun open(target: Target.Chapter) {
        if (target != _chapter.value) load(target, null)
    }

    fun next() {
        neighbor(1)?.let { load(it, null) }
    }


    fun onTopVerse(verse: Int) {
        _topVerse.value = VerseRef(_chapter.value.volumeId, _chapter.value.bookId, _chapter.value.chapter, verse)
    }

    fun focusHandled(request: FocusRequest) {
        if (_focus.value == request) _focus.value = null
    }

    // --- selection ---

    fun startSelection(pos: WordPos) {
        _selection.value = Selection(pos)
    }

    fun extendSelection(pos: WordPos) {
        _selection.value = _selection.value?.extendTo(pos)
    }

    fun clearSelection() {
        _selection.value = null
    }

    /** A tap on [pos] (null = not on a word). Returns what the screen should open, if anything. */
    fun onWordTap(pos: WordPos?): TapAction {
        val selection = _selection.value
        if (selection != null) {
            if (pos == null) clearSelection() else extendSelection(pos)
            return TapAction.None
        }
        if (pos == null) return TapAction.None
        return Annotations.at(chapterAnnotations.value, pos)?.let { TapAction.OpenAnnotation(it) } ?: TapAction.None
    }

    fun onMarkerTap(verse: Int, marker: MarkerSpan): TapAction {
        val ch = _chapter.value
        return TapAction.OpenFootnote(VerseRef(ch.volumeId, ch.bookId, ch.chapter, verse), marker.footnote)
    }

    /** The selected text, for a note's quote. */
    fun quote(range: WordRange): String = quoteOf(_verses.value.orEmpty(), range)

    /** The single selected word and where it is, for Define. */
    fun selectedWord(): Pair<String, VerseRef>? {
        val range = _selection.value?.range?.takeIf { it.isSingleWord } ?: return null
        val text = _verses.value?.getOrNull(range.start.verse - 1) ?: return null
        val ch = _chapter.value
        return quoteOf(listOf(text), WordRange(WordPos(1, range.start.word), WordPos(1, range.start.word))) to
            VerseRef(ch.volumeId, ch.bookId, ch.chapter, range.start.verse)
    }

    fun highlightSelection() {
        val range = _selection.value?.range ?: return
        val chapter = _chapter.value
        val verses = _verses.value ?: return
        val counts = _displays.value.orEmpty().map { it.wordCount }
        clearSelection()
        AppGraph.scope.launch {
            study.highlight(chapter, range, { v -> counts.getOrElse(v - 1) { 0 } }) { quoteOf(verses, it) }
        }
    }

    /** Saves [note] on [range] in [chapter] (captured before the note editor opened). */
    fun addNote(chapter: Target.Chapter, range: WordRange, quote: String, note: String) {
        if (note.isBlank()) return
        if (_chapter.value == chapter && _selection.value?.range == range) clearSelection()
        AppGraph.scope.launch { study.addNote(chapter, range, quote, note) }
    }

    // --- find in chapter ---

    fun find(query: String) {
        val displays = _displays.value ?: return
        if (query.isBlank()) return
        clearSelection()
        val matches = Find.matches(displays, query)
        // Start at the first hit at or below the top of the screen, so finding doesn't jump backwards.
        val top = _topVerse.value?.verse ?: 1
        val first = matches.indexOfFirst { it.verse >= top }.coerceAtLeast(0)
        _find.value = FindState(query.trim(), matches, first)
        focusCurrentMatch()
    }

    fun findNext() = stepFind(1)
    fun findPrevious() = stepFind(-1)

    fun closeFind() {
        _find.value = null
    }

    private fun stepFind(delta: Int) {
        val f = _find.value ?: return
        if (f.matches.isEmpty()) return
        _find.value = f.copy(current = Math.floorMod(f.current + delta, f.matches.size))
        focusCurrentMatch()
    }

    private fun focusCurrentMatch() {
        val m = _find.value?.currentMatch ?: return
        _focus.value = FocusRequest(m.verse, ++focusCounter, offset = m.start)
    }

    // --- bookmarks ---

    /**
     * Bookmark (from the menu): with no bookmark in this chapter, bookmarks the top verse on screen. With one or
     * more, removes the one nearest the top verse, so two taps always undo each other.
     */
    /** The verse at the top of the screen, where a bookmark added from here goes. */
    fun topRef(): VerseRef {
        val ch = _chapter.value
        val top = _topVerse.value?.takeIf { it.chapterTarget == ch }?.verse ?: 1
        return VerseRef(ch.volumeId, ch.bookId, ch.chapter, top)
    }

    // --- loading ---

    private fun load(target: Target.Chapter, focusVerse: Int?, quietFocus: Boolean = false) {
        _chapter.value = target
        _verses.value = null
        _displays.value = null
        _selection.value = null
        _find.value = null
        _topVerse.value = null
        scrollState = ScrollState(0)
        _focus.value = focusVerse?.let { FocusRequest(it, ++focusCounter, flash = !quietFocus) }
        viewModelScope.launch {
            val (verses, displays) = withContext(Dispatchers.IO) {
                val verses = AppGraph.bookText(target.volumeId, target.bookId).c.getOrNull(target.chapter - 1).orEmpty()
                val notes = AppGraph.footnotes.book(target.volumeId, target.bookId).chapter(target.chapter)
                verses to verses.mapIndexed { i, text -> VerseDisplay.build(i + 1, text, notes[i + 1].orEmpty()) }
            }
            // Quick Prev/Next taps can finish out of order; only the latest chapter wins.
            if (_chapter.value != target) return@launch
            _verses.value = verses
            _displays.value = displays
            AppGraph.progress.setLastRead(target)
        }
    }

    /** Remembers the top verse a moment after scrolling settles, for Continue reading. */
    @OptIn(FlowPreview::class)
    private fun saveReadingPosition() {
        viewModelScope.launch {
            _topVerse.filterNotNull().distinctUntilChanged().debounce(800).collect { ref ->
                AppGraph.progress.setLastReadVerse(ref)
            }
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

/**
 * Reads one chapter. [focusVerse] scrolls to that verse and marks it briefly (used by bookmarks,
 * annotations, footnote references and search).
 */
class ReaderScreen(
    private val sealedActivity: SealedLightActivity,
    private val start: Target.Chapter,
    private val focusVerse: Int? = null,
    /** Scroll to [focusVerse] without marking it, as when the app reopens where you left off. */
    private val quietFocus: Boolean = false,
    /**
     * Opened from the now-playing button: turn to whatever chapter is live once the player connects,
     * in case autoplay moved on while no page was watching.
     */
    private var followPlayback: Boolean = false,
) : LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    /** Set when the page follows playback to a chapter that isn't simply the next one over. */
    private var jumpTo: Target.Chapter? = null

    /** The last chapter playback was on, to tell when it moves on. */
    private var lastPlaying: Target.Chapter? = null

    private var pageWidth = 0f

    init {
        AppGraph.init(lightContext)
    }

    override val viewModelClass = ReaderViewModel::class.java
    override fun createViewModel() = ReaderViewModel(start, focusVerse, quietFocus, DefaultLightAudio(sealedActivity))

    @Composable
    override fun Content() {
        val chapter by viewModel.chapter.collectAsState()
        val displays by viewModel.displays.collectAsState()
        val selection by viewModel.selection.collectAsState()
        val annotations by viewModel.chapterAnnotations.collectAsState()
        val find by viewModel.find.collectAsState()
        val display by AppGraph.display.collectAsState()
        val index by viewModel.library.collectAsState()
        val hasAudio = index.hasAudio(chapter)
        val current by viewModel.player.current.collectAsState()
        val playingHere = current == chapter
        val currentSelection = selection
        val currentFind = find
        // Page animations need a frame clock, which the composition's scope has and the view model's
        // doesn't (animating in viewModelScope crashed on the first swipe).
        val pageScope = rememberCoroutineScope()

        // Playback moved on (the next chapter, or NEXT / PREVIOUS): if the page was showing the chapter
        // that was playing, slide along to the new one.
        LaunchedEffect(current) {
            val now = current as? Target.Chapter ?: return@LaunchedEffect
            val before = lastPlaying
            lastPlaying = now
            val shown = viewModel.chapter.value
            if (followPlayback) {
                followPlayback = false
                viewModel.open(now)
                return@LaunchedEffect
            }
            if (before == null || before != shown || now == shown) return@LaunchedEffect
            jumpTo = now
            val forward = now.bookId != shown.bookId || now.chapter > shown.chapter
            pages.slide(pageScope, if (forward) 1 else -1, pageWidth)
        }

        ScreenFrame(
            title = viewModel.catalog.label(chapter),
            reading = true,
            // Back while searching goes back to the search bar to keep typing, not out of the chapter.
            onBack = { if (viewModel.find.value != null) editFind() else goBack() },
            zen = Zen.on,
            // Something else playing: turn to its chapter here.
            rightButton = nowPlayingButton(showing = chapter) { target ->
                (target is Target.Chapter).also { if (it) viewModel.open(target as Target.Chapter) }
            },
            // Playing here: the transport. Otherwise the app's bar, with play in the middle when the
            // chapter has audio. Search leads with "Find in <chapter>", which comes back here as a
            // find; Bookmarks leads with adding one here.
            bottomBar = viewModel.player.transportBar(playingHere) ?: navBar(
                play = if (hasAudio) {
                    LightBarButton.LightIcon(LightIcons.PLAY, onClick = { viewModel.player.play(chapter) }, contentDescription = "Listen")
                } else {
                    null
                },
                onSearch = { navigateTo({ SearchScreen(it, findIn = chapter) }) { query -> viewModel.find(query) } },
                onBookmarks = { navigateTo({ BookmarksScreen(it, here = viewModel.topRef()) }) },
            ),
            bottomContent = when {
                currentSelection != null -> {
                    {
                        ActionBar(
                            listOfNotNull(
                                LightBarButton.LightIcon(LightIcons.CLOSE, onClick = viewModel::clearSelection),
                                LightBarButton.Icon(
                                    painter = tintedIcon(R.drawable.ic_highlight),
                                    onClick = viewModel::highlightSelection,
                                    contentDescription = "Highlight",
                                ),
                                // Light's own compose glyph, as LightOS Notes starts a note.
                                LightBarButton.LightIcon(
                                    LightIcons.COMPOSE_MESSAGE,
                                    onClick = { openNoteEditor(chapter, currentSelection.range) },
                                    contentDescription = "Note",
                                ),
                                LightBarButton.Icon(
                                    painter = tintedIcon(R.drawable.ic_define),
                                    onClick = ::openDefine,
                                    contentDescription = "Define",
                                ).takeIf { currentSelection.range.isSingleWord },
                            ),
                        )
                    }
                }
                currentFind != null -> {
                    { FindBar(currentFind) }
                }
                else -> null
            },
        ) {
            val list = displays
            // The frame that reads sideways drags stays still; only the inner page slides, so drag
            // distances are measured in steady coordinates.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { pageWidth = it.width.toFloat() }
                    // Double tap: zen mode on or off (no bars). Highlighting and swiping still work in it.
                    .doubleTap(Zen::toggle)
                    .pageSwipe(
                        enabled = currentSelection == null,
                        onDrag = { dx -> pages.drag(pageScope, dx) },
                        onRelease = { dx, width -> pages.release(pageScope, dx, width) },
                    ),
            ) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = pages.offset.value }) {
            if (list != null) {
                ChapterText(
                    displays = list,
                    annotations = annotations,
                    selection = currentSelection?.range,
                    find = currentFind,
                    textStyle = readerTextStyle(display.textSize.scale, display.lineSpacing),
                    lineSpacing = display.lineSpacing,
                    scrollState = viewModel.scrollState,
                    focus = viewModel.focus,
                    onFocusHandled = viewModel::focusHandled,
                    onTopVerse = viewModel::onTopVerse,
                    onLongPress = viewModel::startSelection,
                    onDrag = viewModel::extendSelection,
                    onWordTap = { pos -> handle(viewModel.onWordTap(pos)) },
                    onMarkerTap = { verse, marker -> handle(viewModel.onMarkerTap(verse, marker)) },
                )
            }
            }
            }
            PlaybackError(viewModel.player, playingHere)
        }
    }

    /** Chapter turns; lives on the screen so it outlasts recomposition. */
    private val pages = PageSlider(
        canTurn = { viewModel.canTurn(it) },
        turn = { direction ->
            val jump = jumpTo
            jumpTo = null
            when {
                jump != null -> viewModel.open(jump)
                direction > 0 -> viewModel.next()
                else -> viewModel.previous()
            }
            withTimeoutOrNull(PAGE_LOAD_WAIT_MS) { viewModel.displays.first { it != null } }
        },
    )

    /** Close, PREV, "n OF m", NEXT; or close and NO MATCHES. */
    @Composable
    private fun FindBar(find: FindState) {
        val close = LightBarButton.LightIcon(LightIcons.CLOSE, onClick = viewModel::closeFind, contentDescription = "Close find")
        ActionBar(
            if (find.matches.isEmpty()) {
                listOf(close, LightBarButton.Text("NO MATCHES", onClick = null))
            } else {
                listOf(
                    close,
                    LightBarButton.Text("PREV", onClick = viewModel::findPrevious),
                    LightBarButton.Text("${find.current + 1} OF ${find.matches.size}", onClick = null),
                    LightBarButton.Text("NEXT", onClick = viewModel::findNext),
                )
            },
        )
    }

    /** Back to the search bar with the current query. Backing out of it ends the search. */
    private fun editFind() {
        val last = viewModel.find.value?.query.orEmpty()
        navigateTo({ FindInputScreen(it, last, blankOnBack = true) }) { query ->
            if (query.isBlank()) viewModel.closeFind() else viewModel.find(query)
        }
    }

    private fun handle(action: TapAction) {
        when (action) {
            TapAction.None -> Unit
            is TapAction.OpenAnnotation -> navigateTo({ AnnotationScreen(it, action.annotation.id) })
            is TapAction.OpenFootnote -> navigateTo({ FootnoteScreen(it, action.ref, action.footnote) })
        }
    }

    private fun openNoteEditor(chapter: Target.Chapter, range: WordRange) {
        val quote = viewModel.quote(range)
        navigateTo({ NoteEditorScreen(it, title = "Note", initial = "") }) { text ->
            viewModel.addNote(chapter, range, quote, text)
        }
    }

    private fun openDefine() {
        val (word, ref) = viewModel.selectedWord() ?: return
        val wordIndex = viewModel.selection.value?.range?.start?.word ?: return
        navigateTo({ DefineScreen(it, word, ref, wordIndex) })
    }
}

/** Where one verse sits inside the chapter column, and how its text was laid out. */
private class VerseGeometry(val top: Float, val height: Float, val layout: TextLayoutResult)

/** The result of hit-testing a point in the chapter column. */
private sealed interface Hit {
    data object Nothing : Hit
    data class Word(val pos: WordPos) : Hit
    data class Marker(val verse: Int, val marker: MarkerSpan) : Hit
}

@Composable
private fun ChapterText(
    displays: List<VerseDisplay>,
    annotations: List<Annotation>,
    selection: WordRange?,
    find: FindState?,
    textStyle: TextStyle,
    lineSpacing: Float,
    scrollState: ScrollState,
    focus: StateFlow<FocusRequest?>,
    onFocusHandled: (FocusRequest) -> Unit,
    onTopVerse: (Int) -> Unit,
    onLongPress: (WordPos) -> Unit,
    onDrag: (WordPos) -> Unit,
    onWordTap: (WordPos?) -> Unit,
    onMarkerTap: (Int, MarkerSpan) -> Unit,
) {
    val colors = LightThemeTokens.colors
    val geometry = remember(displays) { mutableStateMapOf<Int, VerseGeometry>() }
    val heights = remember(displays) { HashMap<Int, Float>() }
    val tops = remember(displays) { HashMap<Int, Float>() }
    val layouts = remember(displays) { HashMap<Int, TextLayoutResult>() }
    val haptics = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    // The gap between verses grows by whatever line spacing adds between lines, so at wide spacing a
    // verse break still reads as bigger than a line break.
    val verseGap = with(LocalDensity.current) {
        val line = textStyle.lineHeight.toDp()
        VERSE_GAP_UNITS.gridUnitsAsDp() + (line - line / lineSpacing)
    }
    val focusTopMarginPx = with(LocalDensity.current) { FOCUS_TOP_MARGIN_UNITS.gridUnitsAsDp().toPx() }

    val flash = remember { Animatable(0f) }
    var flashVerse by remember { mutableStateOf<Int?>(null) }

    fun publish(verse: Int) {
        val layout = layouts[verse] ?: return
        val top = tops[verse] ?: return
        val height = heights[verse] ?: layout.size.height.toFloat()
        val old = geometry[verse]
        // onGloballyPositioned fires on every scroll frame; only write state when something moved.
        if (old != null && old.top == top && old.height == height && old.layout === layout) return
        geometry[verse] = VerseGeometry(top, height, layout)
    }

    // Scroll to a requested verse once it has been laid out, then fade a mark on it.
    LaunchedEffect(displays) {
        focus.filterNotNull().collect { request ->
            val verse = request.verse.coerceIn(1, displays.size.coerceAtLeast(1))
            // Wait until every verse is laid out, so the scroll range covers the whole chapter.
            snapshotFlow { geometry.size }.first { it >= displays.size }
            val target = geometry[verse] ?: return@collect
            if (request.offset != null) {
                // Find: bring the match's line into view, leaving the screen alone if it's already there.
                val layout = target.layout
                val line = layout.getLineForOffset(request.offset.coerceIn(0, layout.layoutInput.text.length))
                val lineTop = target.top + layout.getLineTop(line)
                val lineBottom = target.top + layout.getLineBottom(line)
                val visible = scrollState.value..(scrollState.value + scrollState.viewportSize)
                if (lineTop.toInt() !in visible || lineBottom.toInt() !in visible) {
                    scrollState.scrollTo((lineTop - focusTopMarginPx).toInt().coerceIn(0, scrollState.maxValue))
                }
                onFocusHandled(request)
                return@collect
            }
            scrollState.scrollTo((target.top - focusTopMarginPx).toInt().coerceIn(0, scrollState.maxValue))
            onFocusHandled(request)
            if (!request.flash) return@collect
            flashVerse = verse
            flash.snapTo(1f)
            delay(FOCUS_HOLD_MS)
            flash.animateTo(0f, tween(FOCUS_FADE_MS))
            flashVerse = null
        }
    }

    // Report the verse at the top of the screen as the user scrolls.
    LaunchedEffect(displays) {
        snapshotFlow {
            val bottoms = (1..displays.size).map { v -> geometry[v]?.let { (it.top + it.height).toInt() } ?: Int.MAX_VALUE }
            if (geometry.isEmpty()) null else topVisibleVerse(bottoms, scrollState.value)
        }.filterNotNull().distinctUntilChanged().collect(onTopVerse)
    }

    val currentGeometry by rememberUpdatedState(geometry)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnWordTap by rememberUpdatedState(onWordTap)
    val currentOnMarkerTap by rememberUpdatedState(onMarkerTap)

    LightScrollView(Modifier.fillMaxSize(), scrollState = scrollState) {
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(displays) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var tapAt: Offset? = null
                        // Wait for the long-press timeout. Lifting first is a tap; moving past touch
                        // slop first is a scroll, which we leave to the scroll view by not consuming.
                        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    if (!change.isConsumed) tapAt = change.position
                                    break
                                }
                                if (change.isConsumed || (change.position - down.position).getDistance() > slop) break
                            }
                        } == null
                        val geo = currentGeometry
                        if (!held) {
                            tapAt?.let { at ->
                                when (val hit = hitTest(geo, displays, at, strict = true)) {
                                    is Hit.Marker -> currentOnMarkerTap(hit.verse, hit.marker)
                                    is Hit.Word -> currentOnWordTap(hit.pos)
                                    Hit.Nothing -> currentOnWordTap(null)
                                }
                            }
                            return@awaitEachGesture
                        }
                        // Long press: select the word under the finger, then follow the drag word by word.
                        val start = (hitTest(geo, displays, down.position, strict = false) as? Hit.Word)?.pos
                            ?: return@awaitEachGesture
                        if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnLongPress(start)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) {
                                change.consume()
                                (hitTest(currentGeometry, displays, change.position, strict = false) as? Hit.Word)
                                    ?.let { currentOnDrag(it.pos) }
                            }
                        }
                    }
                },
        ) {
            displays.forEach { display ->
                val verse = display.verse
                val text = remember(display, annotations, selection, find, colors) {
                    styledVerse(display, annotations, selection, find, colors)
                }
                BasicText(
                    text = text,
                    style = textStyle,
                    onTextLayout = { layouts[verse] = it; publish(verse) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            tops[verse] = coords.positionInParent().y
                            heights[verse] = coords.size.height.toFloat()
                            publish(verse)
                        }
                        .drawBehind {
                            // Read the animation here, in the draw phase, so fading doesn't recompose.
                            if (flashVerse == verse && flash.value > 0f) {
                                drawRect(colors.content.copy(alpha = flash.value * FOCUS_ALPHA))
                            }
                        }
                        .padding(bottom = verseGap),
                )
            }
        }
    }
}

/**
 * Paragraph style, scaled to the screen the way LightText scales it, then by the Display text size
 * [scale] and line [spacing].
 */
@Composable
fun readerTextStyle(scale: Float, spacing: Float): TextStyle {
    val base = LightThemeTokens.typography.paragraph
    return base.copy(
        color = LightThemeTokens.colors.content,
        fontSize = (base.fontSize.value * scale).designVerticalPxToSp(),
        lineHeight = (base.lineHeight.value * scale * spacing).designVerticalPxToSp(),
    )
}

/** One verse with its number, highlights, notes, find matches, the selection and footnote markers as spans. */
private fun styledVerse(
    display: VerseDisplay,
    annotations: List<Annotation>,
    selection: WordRange?,
    find: FindState?,
    colors: LightColors,
): AnnotatedString = buildAnnotatedString {
    append(display.text)
    addStyle(SpanStyle(color = colors.contentSecondary), 0, display.numberEnd)
    val verse = display.verse
    for (a in annotations) {
        val words = a.range.wordsIn(verse, display.wordCount) ?: continue
        val chars = display.markRange(words) ?: continue
        addStyle(
            SpanStyle(
                background = colors.content.copy(alpha = HIGHLIGHT_ALPHA),
                textDecoration = if (a.hasNote) TextDecoration.Underline else null,
            ),
            chars.first,
            chars.last + 1,
        )
    }
    find?.matches?.forEach { m ->
        if (m.verse != verse) return@forEach
        val alpha = if (m == find.currentMatch) FIND_CURRENT_ALPHA else FIND_ALPHA
        addStyle(SpanStyle(background = colors.content.copy(alpha = alpha)), m.start, m.end)
    }
    selection?.wordsIn(verse, display.wordCount)?.let(display::markRange)?.let { chars ->
        addStyle(SpanStyle(background = colors.content.copy(alpha = SELECTION_ALPHA)), chars.first, chars.last + 1)
    }
    for (m in display.markers) {
        addStyle(
            SpanStyle(
                fontSize = MARKER_SIZE_EM.em,
                baselineShift = BaselineShift.Superscript,
                color = colors.contentSecondary,
                background = Color.Transparent,
                textDecoration = TextDecoration.None,
            ),
            m.start,
            m.end,
        )
    }
}

/**
 * Maps a point in the chapter column to a footnote marker or a word. [strict] (taps) requires the
 * point to be on the word itself; otherwise (long-press, drag) the nearest word on the line wins,
 * and points between verses snap to the closest verse.
 */
private fun hitTest(
    geometry: Map<Int, VerseGeometry>,
    displays: List<VerseDisplay>,
    point: Offset,
    strict: Boolean,
): Hit {
    if (geometry.isEmpty()) return Hit.Nothing
    val entry = geometry.entries.firstOrNull { (_, g) -> point.y >= g.top && point.y < g.top + g.height }
        ?: if (strict) {
            return Hit.Nothing
        } else {
            geometry.entries.minByOrNull { (_, g) ->
                if (point.y < g.top) g.top - point.y else point.y - (g.top + g.height)
            } ?: return Hit.Nothing
        }
    val verse = entry.key
    val g = entry.value
    val display = displays.getOrNull(verse - 1) ?: return Hit.Nothing
    val layout = g.layout
    val local = Offset(point.x, (point.y - g.top).coerceIn(0f, (layout.size.height - 1).toFloat().coerceAtLeast(0f)))
    if (strict && point.y - g.top > layout.size.height) return Hit.Nothing

    if (strict) {
        for (m in display.markers) {
            val first = layout.getBoundingBox(m.start)
            val last = layout.getBoundingBox(m.end - 1)
            val left = minOf(first.left, last.left) - MARKER_TOUCH_SLOP_PX
            val right = maxOf(first.right, last.right) + MARKER_TOUCH_SLOP_PX
            val top = minOf(first.top, last.top) - MARKER_TOUCH_SLOP_PX
            val bottom = maxOf(first.bottom, last.bottom) + MARKER_TOUCH_SLOP_PX
            if (local.x in left..right && local.y in top..bottom) return Hit.Marker(verse, m)
        }
    }

    val offset = layout.getOffsetForPosition(local)
    if (!strict) {
        return display.nearestWord(offset)?.let { Hit.Word(WordPos(verse, it)) } ?: Hit.Nothing
    }
    val word = display.wordAt(offset) ?: return Hit.Nothing
    val start = display.wordStarts[word]
    val end = display.wordEnds[word]
    val line = layout.getLineForVerticalPosition(local.y)
    if (layout.getLineForOffset(start) != line && layout.getLineForOffset(end - 1) != line) return Hit.Nothing
    val left = layout.getBoundingBox(start).left - WORD_TOUCH_SLOP_PX
    val right = layout.getBoundingBox(end - 1).right + WORD_TOUCH_SLOP_PX
    // A word broken across lines (at a hyphen) is accepted anywhere on either line.
    val sameLine = layout.getLineForOffset(start) == layout.getLineForOffset(end - 1)
    if (sameLine && local.x !in left..right) return Hit.Nothing
    return Hit.Word(WordPos(verse, word))
}
