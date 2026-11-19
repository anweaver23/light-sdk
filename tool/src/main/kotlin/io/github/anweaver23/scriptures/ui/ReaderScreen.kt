package io.github.anweaver23.scriptures.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.filter
import io.github.anweaver23.scriptures.core.Words
import io.github.anweaver23.scriptures.core.PageRefs
import io.github.anweaver23.scriptures.core.isPage
import io.github.anweaver23.scriptures.core.pageId
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
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
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
import io.github.anweaver23.scriptures.core.ChapterExtras
import io.github.anweaver23.scriptures.core.Find
import io.github.anweaver23.scriptures.core.FindMatch
import io.github.anweaver23.scriptures.core.Footnote
import io.github.anweaver23.scriptures.core.MarkerSpan
import io.github.anweaver23.scriptures.core.ReadAlong
import io.github.anweaver23.scriptures.core.ReadSegment
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

/**
 * Background of the verses a link opened (a footnote's "Alma 32:5–6"): like a highlight, and it stays
 * until the chapter changes.
 */
const val LINKED_ALPHA = HIGHLIGHT_ALPHA

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

/** The zen-mode chapter title, relative to the verse text. */
private const val ZEN_TITLE_SCALE = 1.15f

/** Auto-scroll: how long the page's pace takes to settle to the reading's, so it glides between verses. */
private const val FOLLOW_PACE_SMOOTH_MS = 1500f

/** Auto-scroll: how slowly the page is pulled onto the guess when it's a little off. */
private const val FOLLOW_PULL_MS = 3000f

/** Auto-scroll: how quickly it catches up after a big miss (a seek, a skip). */
private const val FOLLOW_CATCH_UP_MS = 300f

/** Auto-scroll: a player report this far from the page's clock is a seek, taken at once. */
private const val FOLLOW_CLOCK_SNAP_MS = 1500f

/** Auto-scroll: how long a player report's small correction takes to fold into the clock. */
private const val FOLLOW_CLOCK_BLEND_MS = 500f

/** Auto-scroll: after the reader scrolls by hand, it leaves the page alone this long. */
private const val FOLLOW_HAND_HOLD_MS = 6000L

/** What a tap on the text should do. */
sealed interface TapAction {
    data object None : TapAction
    data class OpenAnnotation(val annotation: Annotation) : TapAction
    data class OpenFootnote(val ref: VerseRef, val footnote: Footnote) : TapAction
    /** A link in an imported page's text ([label] is its words). */
    data class OpenLink(val to: String, val label: String) : TapAction
    /** The word has more than one note, highlight or footnote: ask which. */
    data class Choose(val options: List<TapAction>) : TapAction
}

/**
 * How one block of an imported page (see [PageRefs]) is drawn in the reader: its kind ("heading",
 * "subheading", "sig", "entry", "p", "verse") and its runs (italics, bold, links) as styled text over
 * the block's own characters, which are the "verse" text the reader works with.
 */
class PageLook(val kind: String, val text: AnnotatedString) {
    val links: List<AnnotatedString.Range<LinkAnnotation>> = text.getLinkAnnotations(0, text.length)

    /** The link over character [at] of the block's text, as its target and its words. */
    fun linkAt(at: Int): Pair<String, String>? {
        val r = links.firstOrNull { at >= it.start && at < it.end } ?: return null
        val to = (r.item as? LinkAnnotation.Clickable)?.tag ?: return null
        return to to text.text.substring(r.start, r.end)
    }
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
    /** With [holdFocus], the last verse of the range opened (a link to "5–6"). */
    focusThrough: Int? = null,
    /** Mark the focus verses until the chapter changes, rather than a fading flash. */
    holdFocus: Boolean = false,
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

    /** The book's printed title and the chapter's ("The First Book of Moses Called Genesis", "Chapter 1"). */
    private val _fullTitle = MutableStateFlow<List<String>>(emptyList())
    val fullTitle: StateFlow<List<String>> = _fullTitle.asStateFlow()

    /** An imported page's blocks as drawn; null for scripture. */
    private val _pageLooks = MutableStateFlow<List<PageLook>?>(null)
    val pageLooks: StateFlow<List<PageLook>?> = _pageLooks.asStateFlow()

    /** The chapter's heading and summary (imported text only). */
    private val _extras = MutableStateFlow<ChapterExtras?>(null)
    val extras: StateFlow<ChapterExtras?> = _extras.asStateFlow()

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

    /** Verses a link opened, marked until the chapter changes. */
    private val _marked = MutableStateFlow<IntRange?>(null)
    val marked: StateFlow<IntRange?> = _marked.asStateFlow()

    /** The verse at the top of the screen, reported by the UI as the user scrolls. */
    private val _topVerse = MutableStateFlow<VerseRef?>(null)

    init {
        viewModelScope.launch { study.load() }
        saveReadingPosition()
        load(start, focusVerse, quietFocus)
        if (focusVerse != null && holdFocus) focusOn(focusVerse, focusThrough, hold = true)
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

    /**
     * Scrolls to [verse] and marks it: with [hold], verses [verse] to [through] stay marked until the
     * chapter changes; otherwise the verse flashes and fades.
     */
    fun focusOn(verse: Int, through: Int? = null, hold: Boolean = false) {
        clearSelection()
        _marked.value = if (hold) verse..maxOf(verse, through ?: verse) else null
        _focus.value = FocusRequest(verse, ++focusCounter, flash = !hold)
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
        // A footnoted word is a link too, not just its letter.
        val ch = _chapter.value
        // A word in a page's link (a Topical Guide reference) follows it.
        val link = _pageLooks.value?.getOrNull(pos.verse - 1)?.let { look ->
            Words.spans(look.text.text).getOrNull(pos.word)?.let { look.linkAt(it.start) }
        }?.let { (to, label) -> TapAction.OpenLink(to, label) }
        val ref = VerseRef(ch.volumeId, ch.bookId, ch.chapter, pos.verse)
        val footnotes = _displays.value?.getOrNull(pos.verse - 1)?.markers.orEmpty()
            .filter { pos.word in it.footnote.word..it.footnote.end }
            .map { TapAction.OpenFootnote(ref, it.footnote) }
        val options = listOfNotNull(link) + Annotations.allAt(chapterAnnotations.value, pos).map { TapAction.OpenAnnotation(it) } + footnotes
        return when (options.size) {
            0 -> TapAction.None
            1 -> options.single()
            else -> TapAction.Choose(options)
        }
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
        _extras.value = null
        _pageLooks.value = null
        _selection.value = null
        _find.value = null
        _topVerse.value = null
        _marked.value = null
        scrollState = ScrollState(0)
        _focus.value = focusVerse?.let { FocusRequest(it, ++focusCounter, flash = !quietFocus) }
        viewModelScope.launch {
            target.pageId?.let { id ->
                loadPage(target, id)
                return@launch
            }
            val (verses, displays, extras) = withContext(Dispatchers.IO) {
                val book = AppGraph.bookText(target.volumeId, target.bookId)
                val verses = book.c.getOrNull(target.chapter - 1).orEmpty()
                val extras = book.x.getOrNull(target.chapter - 1)
                val notes = AppGraph.footnotes.book(target.volumeId, target.bookId).chapter(target.chapter)
                Triple(
                    verses,
                    verses.mapIndexed { i, text ->
                        VerseDisplay.build(
                            i + 1,
                            text,
                            notes[i + 1].orEmpty(),
                            italics = extras?.italicRuns(i + 1).orEmpty(),
                            caps = extras?.capsRuns(i + 1).orEmpty(),
                        )
                    },
                    extras,
                )
            }
            // Quick Prev/Next taps can finish out of order; only the latest chapter wins.
            if (_chapter.value != target) return@launch
            _verses.value = verses
            _extras.value = extras
            val book = catalog.scriptures.book(target.bookId)
            _fullTitle.value = listOfNotNull(
                AppGraph.bookText(target.volumeId, target.bookId).title ?: book?.title,
                extras?.title ?: "${book?.chapterLabel ?: "Chapter"} ${target.chapter}",
            )
            _displays.value = displays
            AppGraph.progress.setLastRead(target)
        }
    }

    /**
     * An imported page as a one-chapter book: each block a "verse" with no number (a JST verse keeps
     * its own), styled by [PageLook]. Pages aren't where you left off: that stays with scripture.
     */
    private suspend fun loadPage(target: Target.Chapter, id: String) {
        val page = withContext(Dispatchers.IO) { AppGraph.text.page(id) }
        if (_chapter.value != target) return
        val blocks = page?.blocks.orEmpty()
        val looks = blocks.map { PageLook(it.t, annotated(it.spans) {}) }
        val texts = looks.map { it.text.text }
        _verses.value = texts
        _pageLooks.value = looks
        _fullTitle.value = listOfNotNull(page?.title)
        _displays.value = texts.mapIndexed { i, text -> VerseDisplay.build(i + 1, text, number = blocks[i].n?.toString()) }
    }

    /** Remembers the top verse a moment after scrolling settles, for Continue reading. */
    @OptIn(FlowPreview::class)
    private fun saveReadingPosition() {
        viewModelScope.launch {
            _topVerse.filterNotNull().filter { !it.chapterTarget.isPage }.distinctUntilChanged().debounce(800).collect { ref ->
                AppGraph.progress.setLastReadVerse(ref)
            }
        }
    }

    /** The chapter before or after this one, crossing into the neighbouring book of the same volume. */
    private fun neighbor(delta: Int): Target.Chapter? {
        val current = _chapter.value
        // A page turns to the next of the pages listed with it (a folder's, or its group's loose ones).
        current.pageId?.let { id ->
            val all = AppGraph.text.pageIndex().groups[pageGroup(id)].orEmpty()
            val folder = all.firstOrNull { it.id == id }?.folder
            val list = all.filter { it.folder == folder }
            val i = list.indexOfFirst { it.id == id }
            return if (i < 0) null else list.getOrNull(i + delta)?.let { PageRefs.chapter(it.id) }
        }
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
 * Opens the reader at [chapter], marking [verse], or goes back to a reader already showing that
 * chapter (see [Nav]) and marks the verse there.
 */
fun SimpleLightScreen<*>.openReader(chapter: Target.Chapter, verse: Int? = null, through: Int? = null, hold: Boolean = false) =
    open(chapter, { ReaderScreen(it, chapter, focusVerse = verse, focusThrough = through, holdFocus = hold) }) { reader ->
        verse?.let { reader.focus(it, through, hold) }
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
    /** With [holdFocus], the last verse to mark (a link to a range, "5–6"). */
    private val focusThrough: Int? = null,
    /** Keep [focusVerse] (to [focusThrough]) marked until the chapter changes, as a link opens it. */
    private val holdFocus: Boolean = false,
) : AppLightScreen<Unit, ReaderViewModel>(sealedActivity), Keyed {

    /** The chapter on the page: going to it again (a bookmark, a footnote link) comes back here. */
    override val navKey: Any get() = viewModel.chapter.value

    /** Scrolls to [verse] and marks it, as when opened with a focus verse. */
    fun focus(verse: Int, through: Int? = null, hold: Boolean = false) = viewModel.focusOn(verse, through, hold)

    /** Set when the page follows playback to a chapter that isn't simply the next one over. */
    private var jumpTo: Target.Chapter? = null

    /** The last chapter playback was on, to tell when it moves on. */
    private var lastPlaying: Target.Chapter? = null

    /**
     * The chapter this page is following playback on. When playback moves to the next chapter, the
     * page stays "the playing page" (its transport, no corner button) while it slides along, instead
     * of flashing its own bar for the moment between. A swipe by hand lets go.
     */
    private var following by mutableStateOf<Target.Chapter?>(null)

    private var pageWidth = 0f

    init {
        AppGraph.init(lightContext)
    }

    override val viewModelClass = ReaderViewModel::class.java
    override fun createViewModel() = ReaderViewModel(start, focusVerse, quietFocus, DefaultLightAudio(sealedActivity), focusThrough, holdFocus)

    @Composable
    override fun Content() {
        val chapter by viewModel.chapter.collectAsState()
        val displays by viewModel.displays.collectAsState()
        val selection by viewModel.selection.collectAsState()
        val annotations by viewModel.chapterAnnotations.collectAsState()
        val find by viewModel.find.collectAsState()
        val marked by viewModel.marked.collectAsState()
        val pageLooks by viewModel.pageLooks.collectAsState()
        val extras by viewModel.extras.collectAsState()
        val fullTitle by viewModel.fullTitle.collectAsState()
        val manifest by AppGraph.text.manifest.collectAsState()
        val display by AppGraph.display.collectAsState()
        val index by viewModel.library.collectAsState()
        val hasAudio = index.hasAudio(chapter)
        val current by viewModel.player.current.collectAsState()
        val playingHere = current == chapter || (current is Target.Chapter && following == chapter)
        val currentSelection = selection
        val currentFind = find
        // Page animations need a frame clock, which the composition's scope has and the view model's
        // doesn't (animating in viewModelScope crashed on the first swipe).
        val pageScope = rememberCoroutineScope()

        // Playing this chapter: keep the line being read near the middle of the screen (Settings ›
        // Playback › Auto-scroll). Not while selecting or finding, which move the page themselves.
        val playing by viewModel.player.isPlaying.collectAsState()
        val playback by AppGraph.playback.collectAsState()
        val autoScroll = playback.autoScroll && playing && current == chapter && currentSelection == null && currentFind == null
        val verses by viewModel.verses.collectAsState()
        val readAlong = remember(autoScroll, chapter, verses, extras) {
            val text = verses
            if (!autoScroll || text == null) return@remember null
            val announced = "${viewModel.catalog.scriptures.book(chapter.bookId)?.chapterLabel ?: "Chapter"} ${chapter.chapter}"
            ReadAlongSource(
                positionMs = viewModel.player.positionMs,
                durationMs = viewModel.player.durationMs,
                speed = { AppGraph.playback.value.scriptureSpeed },
                leadChars = announced.length + LEAD_PAUSE_CHARS,
                headerChars = extras?.let { x -> ReadAlong.spokenLength((x.intro + x.summary).joinToString("") { it.t }) } ?: 0,
                verseChars = text.map(ReadAlong::spokenLength),
            )
        }

        // Showing the chapter that's playing: follow it.
        LaunchedEffect(current, chapter) {
            if (current == chapter) following = chapter
        }

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

        AppPage(
            title = viewModel.catalog.label(chapter),
            fullTitle = fullTitle.ifEmpty { null },
            reading = true,
            // Back while searching goes back to the search bar to keep typing, not out of the chapter.
            onBack = { if (viewModel.find.value != null) editFind() else goBack() },
            zen = Zen.on,
            // Something else playing: turn to its chapter here.
            rightButton = nowPlayingButton(showing = if (playingHere) current else chapter) { target ->
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
                onBookmarks = {
                    val here = viewModel.topRef()
                    open(NavKeys.Bookmarks, { BookmarksScreen(it, here = here) }) { it.here = here }
                },
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
            if (list != null && list.isEmpty() && manifest.books.isEmpty()) {
                NoTextYet()
            } else if (list != null) {
                ChapterText(
                    displays = list,
                    annotations = annotations,
                    marked = marked,
                    pageLooks = pageLooks,
                    readAlong = readAlong,
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
                    header = {
                        val style = readerTextStyle(display.textSize.scale, display.lineSpacing)
                        // Zen mode hides the top bar, so the chapter's name leads the text (it scrolls away).
                        if (Zen.on) ZenTitle(viewModel.catalog.label(chapter), style)
                        extras?.let { ChapterHeader(it, style) }
                    },
                    footer = extras?.closing?.takeIf { it.isNotEmpty() }?.let { closing ->
                        {
                            val style = readerTextStyle(display.textSize.scale, display.lineSpacing)
                            SpanText(
                                closing,
                                style.copy(color = LightThemeTokens.colors.contentSecondary, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center),
                                Modifier.fillMaxWidth().padding(top = VERSE_GAP_UNITS.gridUnitsAsDp()),
                            ) { openLink(it) }
                        }
                    },
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
            following = jump
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

    /** The chapter's name over its text in zen mode, where there's no top bar to show it. */
    @Composable
    private fun ZenTitle(title: String, style: TextStyle) {
        BasicText(
            text = title,
            style = style.copy(
                fontSize = style.fontSize * ZEN_TITLE_SCALE,
                lineHeight = style.lineHeight * ZEN_TITLE_SCALE,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = VERSE_GAP_UNITS.gridUnitsAsDp() * 1.5f),
        )
    }

    /** A D&C section's heading or a Psalm's title, then the chapter summary, as the printed page has them. */
    @Composable
    private fun ChapterHeader(x: ChapterExtras, style: TextStyle) {
        val secondary = LightThemeTokens.colors.contentSecondary
        val gap = VERSE_GAP_UNITS.gridUnitsAsDp()
        // The verses' size, so auto-scroll's height-for-time guess holds over a long heading; dimmed instead.
        val dim = style.copy(color = secondary)
        if (x.intro.isNotEmpty()) {
            SpanText(x.intro, dim, Modifier.fillMaxWidth().padding(bottom = gap)) { openLink(it) }
        }
        if (x.summary.isNotEmpty()) {
            SpanText(x.summary, dim.copy(fontStyle = FontStyle.Italic), Modifier.fillMaxWidth().padding(bottom = gap * 1.5f)) { openLink(it) }
        }
    }

    private fun handle(action: TapAction) {
        when (action) {
            TapAction.None -> Unit
            is TapAction.OpenAnnotation -> navigateTo({ AnnotationScreen(it, action.annotation.id) })
            is TapAction.OpenFootnote -> navigateTo({ FootnoteScreen(it, action.ref, action.footnote) })
            is TapAction.OpenLink -> openLink(action.to)
            is TapAction.Choose -> navigateTo({ TapChoiceScreen(it, action.options) }) { i ->
                action.options.getOrNull(i)?.let(::handle)
            }
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
    /** Verses a link opened, marked like a highlight. */
    marked: IntRange?,
    /** An imported page's block styles (see [PageLook]); null for scripture. */
    pageLooks: List<PageLook>?,
    /** While a chapter plays with auto-scroll on: what to follow. */
    readAlong: ReadAlongSource?,
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
    /** Drawn above verse 1: the chapter's heading and summary. */
    header: (@Composable () -> Unit)? = null,
    /** Drawn after the last verse: an epistle's closing line. */
    footer: (@Composable () -> Unit)? = null,
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

    // Where the heading sits (top, bottom), for auto-scroll; the verses' places are in geometry.
    val headerBounds = remember(displays) { FloatArray(2) }

    // Auto-scroll: follow the recording through the text by spoken characters (ReadAlong). The lead-in
    // sits at the top of the heading; each verse runs over its text, then its pause over the gap below,
    // so the guess moves down the page without a jump.
    LaunchedEffect(displays, readAlong) {
        val follow = readAlong ?: return@LaunchedEffect
        snapshotFlow { geometry.size }.first { it >= displays.size }
        followPlayback(scrollState, follow.positionMs, follow.durationMs, follow.speed) { fraction ->
            val segments = buildList {
                add(ReadSegment(follow.leadChars, headerBounds[0], headerBounds[0]))
                if (follow.headerChars > 0) {
                    add(ReadSegment(follow.headerChars, headerBounds[0], headerBounds[1]))
                    add(ReadSegment(ReadAlong.HEADING_PAUSE, headerBounds[1], headerBounds[1]))
                }
                displays.forEach { d ->
                    val g = geometry[d.verse] ?: return@forEach
                    val textBottom = g.top + g.layout.size.height
                    add(ReadSegment(follow.verseChars.getOrElse(d.verse - 1) { 0 }, g.top, textBottom))
                    add(ReadSegment(ReadAlong.VERSE_PAUSE, textBottom, g.top + g.height))
                }
            }
            ReadAlong.y(segments, fraction)
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
            if (header != null) {
                Column(
                    Modifier.fillMaxWidth().onGloballyPositioned { coords ->
                        headerBounds[0] = coords.positionInParent().y
                        headerBounds[1] = headerBounds[0] + coords.size.height
                    },
                ) { header() }
            }
            displays.forEach { display ->
                val verse = display.verse
                val isMarked = marked != null && verse in marked
                val look = pageLooks?.getOrNull(verse - 1)
                val text = remember(display, annotations, isMarked, selection, find, colors, look) {
                    val styled = styledVerse(display, annotations, isMarked, selection, find, colors)
                    if (look == null) styled else withLook(styled, look, display.textStart, colors)
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
            footer?.invoke()
        }
    }
}

/**
 * What auto-scroll follows: the playing recording ([positionMs] of [durationMs], at [speed]) and the
 * text it reads aloud: [leadChars] spoken before anything on the page ("Chapter 6" and a pause), the
 * heading's [headerChars], then each verse's [verseChars].
 */
class ReadAlongSource(
    val positionMs: StateFlow<Long>,
    val durationMs: StateFlow<Long>,
    val speed: () -> Float,
    val leadChars: Int,
    val headerChars: Int,
    val verseChars: List<Int>,
)

/**
 * The spoken announcement before a chapter's text, in characters: its "Chapter 6" plus a pause either
 * side, which the recording reads but the page doesn't print.
 */
private const val LEAD_PAUSE_CHARS = 30

/**
 * Auto-scroll while a chapter plays: a rough guess at the line being read ([where], by characters, see
 * [ReadAlong]) from how far through the recording playback is, kept in the middle of the screen. So
 * the page sits still until the reading passes the middle, and stops a little before the end.
 *
 * It should flow, not hop: the playback position runs on its own clock (at [speed]), nudged toward the
 * player's reports (every 250 ms) rather than reset by them; the page moves at the reading's pace,
 * smoothed, plus a gentle pull onto the guess, and never backs up for a small error (it waits
 * instead). Only a big miss (a seek, a skip) moves it quickly. A scroll by hand (the page moved
 * without us) leaves it alone for a while.
 */
private suspend fun followPlayback(
    scroll: ScrollState,
    positionMs: StateFlow<Long>,
    durationMs: StateFlow<Long>,
    speed: () -> Float,
    where: (Float) -> Float?,
) {
    var reported = positionMs.value
    var clock = reported.toFloat()
    var clockError = 0f
    var last = withFrameMillis { it }
    var expected = scroll.value
    var handUntil = 0L
    var pos = scroll.value.toFloat()
    var lastTarget: Float? = null
    var velocity = 0f
    while (true) {
        val now = withFrameMillis { it }
        val dt = (now - last).coerceIn(0L, 100L).toFloat()
        last = now
        // The playback clock: run at speed, and fold each report's error in over a moment.
        clock += dt * speed()
        if (positionMs.value != reported) {
            reported = positionMs.value
            val error = reported - clock
            if (abs(error) > FOLLOW_CLOCK_SNAP_MS) {
                clock = reported.toFloat()
                clockError = 0f
            } else {
                clockError = error
            }
        }
        val fold = clockError * (dt / FOLLOW_CLOCK_BLEND_MS).coerceAtMost(1f)
        clock += fold
        clockError -= fold

        // Moved by a finger (or a fling, or a find): leave it be for a while, then pick up from there.
        if (scroll.isScrollInProgress || abs(scroll.value - expected) > 2) {
            handUntil = now + FOLLOW_HAND_HOLD_MS
            lastTarget = null
            velocity = 0f
        }
        expected = scroll.value
        val duration = durationMs.value
        if (now < handUntil || duration <= 0 || scroll.maxValue <= 0 || dt <= 0f) {
            pos = scroll.value.toFloat()
            continue
        }
        val y = where((clock / duration).coerceIn(0f, 1f)) ?: continue
        val target = (y - scroll.viewportSize / 2f).coerceIn(0f, scroll.maxValue.toFloat())

        // The reading's pace down the page, smoothed so the page glides between verses.
        lastTarget?.let { previous ->
            val pace = (target - previous) / dt
            velocity += (pace - velocity) * (dt / FOLLOW_PACE_SMOOTH_MS).coerceAtMost(1f)
        }
        lastTarget = target
        val gap = target - pos
        val far = abs(gap) > scroll.viewportSize / 3f
        val pull = gap * (dt / if (far) FOLLOW_CATCH_UP_MS else FOLLOW_PULL_MS).coerceAtMost(1f)
        var move = velocity * dt + pull
        if (!far) move = move.coerceAtLeast(0f)
        pos = (pos + move).coerceIn(0f, scroll.maxValue.toFloat())
        // Whole pixels, so the scroll state keeps no fraction of its own and stays level with pos.
        val delta = (pos - scroll.value).toInt()
        if (delta != 0) {
            scroll.dispatchRawDelta(delta.toFloat())
            expected = scroll.value
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

/** A page block's own styling laid over its drawn verse text, which starts at [offset]. */
private fun withLook(base: AnnotatedString, look: PageLook, offset: Int, colors: LightColors): AnnotatedString = buildAnnotatedString {
    append(base)
    val end = base.length
    fun at(i: Int) = (i + offset).coerceAtMost(end)
    for (r in look.text.spanStyles) addStyle(r.item, at(r.start), at(r.end))
    for (r in look.links) addStyle(SpanStyle(textDecoration = TextDecoration.Underline), at(r.start), at(r.end))
    when (look.kind) {
        "heading" -> addStyle(ParagraphStyle(textAlign = TextAlign.Center), 0, end)
        "subheading" -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, end)
        "sig" -> {
            addStyle(SpanStyle(color = colors.contentSecondary), 0, end)
            addStyle(ParagraphStyle(textAlign = TextAlign.End), 0, end)
        }
    }
}

/** One verse with its number, KJV italics, highlights, notes, find matches, the selection and footnote markers as spans. */
private fun styledVerse(
    display: VerseDisplay,
    annotations: List<Annotation>,
    marked: Boolean,
    selection: WordRange?,
    find: FindState?,
    colors: LightColors,
): AnnotatedString = buildAnnotatedString {
    append(display.text)
    addStyle(SpanStyle(color = colors.contentSecondary), 0, display.numberEnd)
    for (run in display.italics) {
        display.charRange(run)?.let { addStyle(SpanStyle(fontStyle = FontStyle.Italic), it.first, it.last + 1) }
    }
    // Small caps: the text is upper case already; every letter after a word's first is drawn smaller.
    for (run in display.caps) {
        for (w in run) {
            val start = display.wordStarts.getOrNull(w) ?: continue
            val end = display.wordEnds[w]
            if (end - start > 1) addStyle(SpanStyle(fontSize = SMALL_CAPS_EM.em), start + 1, end)
        }
    }
    val verse = display.verse
    if (marked && display.wordCount > 0) {
        display.markRange(0 until display.wordCount)?.let { chars ->
            addStyle(SpanStyle(background = colors.content.copy(alpha = LINKED_ALPHA)), chars.first, chars.last + 1)
        }
    }
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
                fontStyle = FontStyle.Italic,
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
