package io.github.anweaver23.scriptures.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import io.github.anweaver23.scriptures.core.Annotation
import io.github.anweaver23.scriptures.core.Annotations
import io.github.anweaver23.scriptures.core.Bookmark
import io.github.anweaver23.scriptures.core.Catalog
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

/** Footnote markers, relative to the verse text. */
private const val MARKER_SIZE_EM = 0.62f

/** Extra room around a footnote marker's glyph that still counts as tapping it, in px. */
private const val MARKER_TOUCH_SLOP_PX = 28f

/** Extra room either side of a word that still counts as tapping it, in px. */
private const val WORD_TOUCH_SLOP_PX = 6f

/** Space above a focused verse when scrolling to it, in grid units. */
private const val FOCUS_TOP_MARGIN_UNITS = 0.5f

/** What a tap on the text should do. */
sealed interface TapAction {
    data object None : TapAction
    data class OpenAnnotation(val annotation: Annotation) : TapAction
    data class OpenFootnote(val ref: VerseRef, val footnote: Footnote) : TapAction
}

/** Ask the screen to scroll to [verse] and mark it; [id] makes repeated requests distinct. */
data class FocusRequest(val verse: Int, val id: Long)

class ReaderViewModel(start: Target.Chapter, focusVerse: Int?) : LightViewModel<Unit>() {
    val catalog: Catalog = AppGraph.catalog
    val library = AppGraph.library.index
    private val study = AppGraph.study

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

    /** The verse at the top of the screen, reported by the UI as the user scrolls. */
    private val _topVerse = MutableStateFlow<VerseRef?>(null)

    init {
        viewModelScope.launch { study.load() }
        saveReadingPosition()
        load(start, focusVerse)
    }

    fun previous() {
        neighbor(-1)?.let { load(it, null) }
    }

    fun next() {
        neighbor(1)?.let { load(it, null) }
    }

    fun hasPrevious() = neighbor(-1) != null
    fun hasNext() = neighbor(1) != null

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

    // --- bookmarks ---

    /**
     * The star: with no bookmark in this chapter, bookmarks the top verse on screen. With one or
     * more, removes the one nearest the top verse, so two taps always undo each other.
     */
    fun toggleBookmark() {
        val ch = _chapter.value
        val top = _topVerse.value?.takeIf { it.chapterTarget == ch }?.verse ?: 1
        val existing = chapterBookmarks.value
        AppGraph.scope.launch {
            val nearest = existing.minByOrNull { abs(it.ref.verse - top) }
            if (nearest != null) {
                study.deleteBookmark(nearest.id)
            } else {
                study.addBookmark(VerseRef(ch.volumeId, ch.bookId, ch.chapter, top))
            }
        }
    }

    // --- loading ---

    private fun load(target: Target.Chapter, focusVerse: Int?) {
        _chapter.value = target
        _verses.value = null
        _displays.value = null
        _selection.value = null
        _topVerse.value = null
        scrollState = ScrollState(0)
        _focus.value = focusVerse?.let { FocusRequest(it, ++focusCounter) }
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
    sealedActivity: SealedLightActivity,
    private val start: Target.Chapter,
    private val focusVerse: Int? = null,
) : LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    init {
        AppGraph.init(lightContext)
    }

    override val viewModelClass = ReaderViewModel::class.java
    override fun createViewModel() = ReaderViewModel(start, focusVerse)

    @Composable
    override fun Content() {
        val chapter by viewModel.chapter.collectAsState()
        val displays by viewModel.displays.collectAsState()
        val selection by viewModel.selection.collectAsState()
        val annotations by viewModel.chapterAnnotations.collectAsState()
        val bookmarks by viewModel.chapterBookmarks.collectAsState()
        val index by viewModel.library.collectAsState()
        val hasAudio = index.hasAudio(chapter)
        val currentSelection = selection

        ScreenFrame(
            title = viewModel.catalog.label(chapter),
            onBack = { goBack() },
            rightButton = LightBarButton.LightIcon(
                icon = if (bookmarks.isEmpty()) LightIcons.STAR_OUTLINE else LightIcons.STAR,
                onClick = viewModel::toggleBookmark,
                contentDescription = if (bookmarks.isEmpty()) "Bookmark" else "Remove bookmark",
            ),
            bottomBar = listOf(
                LightBarButton.Text("Prev", onClick = viewModel::previous).takeIf { viewModel.hasPrevious() },
                if (hasAudio) LightBarButton.Text("Listen", onClick = { navigateTo({ PlayerScreen(it, chapter) }) }) else null,
                LightBarButton.Text("Next", onClick = viewModel::next).takeIf { viewModel.hasNext() },
            ),
            bottomContent = currentSelection?.let { sel ->
                {
                    ActionBar(
                        listOfNotNull(
                            LightBarButton.LightIcon(LightIcons.CLOSE, onClick = viewModel::clearSelection),
                            LightBarButton.Text("HIGHLIGHT", onClick = viewModel::highlightSelection),
                            LightBarButton.Text("NOTE", onClick = { openNoteEditor(chapter, sel.range) }),
                            LightBarButton.Text("DEFINE", onClick = ::openDefine).takeIf { sel.range.isSingleWord },
                        ),
                    )
                }
            },
        ) {
            val list = displays
            if (list != null) {
                ChapterText(
                    displays = list,
                    annotations = annotations,
                    selection = currentSelection?.range,
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
    val textStyle = readerTextStyle()
    val geometry = remember(displays) { mutableStateMapOf<Int, VerseGeometry>() }
    val heights = remember(displays) { HashMap<Int, Float>() }
    val tops = remember(displays) { HashMap<Int, Float>() }
    val layouts = remember(displays) { HashMap<Int, TextLayoutResult>() }
    val haptics = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    val verseGap = 0.75f.gridUnitsAsDp()
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
            scrollState.scrollTo((target.top - focusTopMarginPx).toInt().coerceIn(0, scrollState.maxValue))
            onFocusHandled(request)
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
                val text = remember(display, annotations, selection, colors) {
                    styledVerse(display, annotations, selection, colors)
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

/** Paragraph style, scaled to the screen the way LightText scales it. */
@Composable
private fun readerTextStyle(): TextStyle {
    val base = LightThemeTokens.typography.paragraph
    return base.copy(
        color = LightThemeTokens.colors.content,
        fontSize = base.fontSize.value.designVerticalPxToSp(),
        lineHeight = base.lineHeight.value.designVerticalPxToSp(),
    )
}

/** One verse with its number, highlights, notes, the selection and footnote markers as spans. */
private fun styledVerse(
    display: VerseDisplay,
    annotations: List<Annotation>,
    selection: WordRange?,
    colors: LightColors,
): AnnotatedString = buildAnnotatedString {
    append(display.text)
    addStyle(SpanStyle(color = colors.contentSecondary), 0, display.numberEnd)
    val verse = display.verse
    for (a in annotations) {
        val words = a.range.wordsIn(verse, display.wordCount) ?: continue
        val chars = display.charRange(words) ?: continue
        addStyle(
            SpanStyle(
                background = colors.content.copy(alpha = HIGHLIGHT_ALPHA),
                textDecoration = if (a.hasNote) TextDecoration.Underline else null,
            ),
            chars.first,
            chars.last + 1,
        )
    }
    selection?.wordsIn(verse, display.wordCount)?.let(display::charRange)?.let { chars ->
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
