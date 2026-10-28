package io.github.anweaver23.scriptures.ui

import io.github.anweaver23.scriptures.core.quoteVerses
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import io.github.anweaver23.scriptures.core.Annotation
import io.github.anweaver23.scriptures.core.Definition
import io.github.anweaver23.scriptures.core.Footnote
import io.github.anweaver23.scriptures.core.OriginalWord
import io.github.anweaver23.scriptures.core.Strongs
import io.github.anweaver23.scriptures.core.VerseRef
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.core.verseLabel
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A section label inside the study screens, e.g. "Webster's 1828". */
@Composable
private fun SectionLabel(text: String) {
    AppText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
    )
}

@Composable
private fun Body(text: String, lighten: Boolean = false) {
    AppText(
        reading = true,
        // Definitions and derivations quote Greek and Hebrew too.
        text = withScriptFonts(text),
        variant = LightTextVariant.Paragraph,
        lighten = lighten,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 0.5f.gridUnitsAsDp()),
    )
}

/** What [DefineScreen] shows: an English word in a verse, or a Strong's entry reached from a root link. */
private data class DefineResult(val english: List<Definition>, val original: List<OriginalWord>)

/**
 * Definition of one word: Webster's 1828 entries, then (Old and New Testament only) the Hebrew or
 * Greek word behind it from Strong's, with links to its roots. Pass [strongs] instead of a verse
 * position to show a single Strong's entry (from a root link).
 */
class DefineScreen private constructor(
    sealedActivity: SealedLightActivity,
    private val word: String,
    private val ref: VerseRef?,
    private val wordIndex: Int,
    private val strongs: String?,
) : SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()


    constructor(sealedActivity: SealedLightActivity, word: String, ref: VerseRef, wordIndex: Int) :
        this(sealedActivity, word, ref, wordIndex, null)

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        // Keyed: a root link opens another DefineScreen in the same composition slot, which would
        // otherwise keep the previous screen's result.
        val result by produceState<DefineResult?>(null, word, ref, wordIndex, strongs) {
            value = withContext(Dispatchers.IO) {
                if (strongs != null) {
                    DefineResult(emptyList(), listOfNotNull(AppGraph.originalLanguage.entry(strongs)))
                } else {
                    DefineResult(
                        english = AppGraph.dictionary.define(word),
                        original = if (ref != null && Strongs.covers(ref.volumeId)) {
                            AppGraph.originalLanguage.wordsAt(ref, wordIndex)
                        } else {
                            emptyList()
                        },
                    )
                }
            }
        }

        ScreenFrame(title = strongs ?: word, onBack = { goBack() }, rightButton = nowPlayingButton()) {
            val r = result ?: return@ScreenFrame
            LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
                if (strongs == null) {
                    SectionLabel("Webster's 1828")
                    if (r.english.isEmpty()) Body("No definition found.", lighten = true)
                    r.english.forEach { DefinitionEntry(it) }
                }
                val showOriginal = strongs != null || (ref != null && Strongs.covers(ref.volumeId))
                if (showOriginal) {
                    SectionLabel(if (strongs == null) "Original" else "Strong's")
                    if (r.original.isEmpty()) {
                        Body(if (strongs == null) "No original word is tagged here." else "No entry found.", lighten = true)
                    }
                    r.original.forEach { OriginalEntry(it) }
                }
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
            }
        }
    }

    @Composable
    private fun DefinitionEntry(d: Definition) {
        AppText(
            text = listOfNotNull(d.headword, d.partOfSpeech).joinToString("  "),
            variant = LightTextVariant.Copy,
            reading = true,
            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
        )
        if (d.senses.size == 1) {
            Body(d.senses.single())
        } else {
            d.senses.forEachIndexed { i, sense -> Body("${i + 1}. $sense") }
        }
    }

    @Composable
    private fun OriginalEntry(w: OriginalWord) {
        AppText(
            text = withScriptFonts(w.lemma),
            variant = LightTextVariant.Heading,
            reading = true,
            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
        )
        Body(listOfNotNull(w.transliteration, w.pronunciation?.let { "($it)" }, w.strongs).joinToString("  "), lighten = true)
        Body(w.definition)
        w.kjvUsage?.let { Body("KJV: $it", lighten = true) }
        w.derivation?.let { derivation ->
            Body(derivation, lighten = true)
            Strongs.refsIn(derivation, self = w.strongs).forEach { root ->
                ListRow("Root $root", underline = true) { navigateTo({ DefineScreen(it, root, null, -1, root) }) }
            }
        }
    }
}

/** A highlight or note, opened by tapping it in the reader: read, edit the note, or remove. */
class AnnotationScreen(sealedActivity: SealedLightActivity, private val id: String) :
    SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()


    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val all by AppGraph.study.annotations.collectAsState()
        val annotation = all.firstOrNull { it.id == id }
        LaunchedEffect(Unit) { AppGraph.study.load() }

        ScreenFrame(
            title = annotation?.let { AppGraph.catalog.label(it) } ?: "Highlight",
            onBack = { goBack() },
            reading = true,
            rightButton = nowPlayingButton(),
            bottomBar = annotation?.let { a ->
                listOf(
                    LightBarButton.LightIcon(LightIcons.TRASH, contentDescription = "Remove", onClick = {
                        navigateTo({ confirmRemove(it, a) }) { confirmed ->
                            if (!confirmed) return@navigateTo
                            AppGraph.scope.launch { AppGraph.study.deleteAnnotation(a.id) }
                            goBack()
                        }
                    }),
                    LightBarButton.Text(if (a.hasNote) "EDIT NOTE" else "ADD NOTE", onClick = {
                        navigateTo({ NoteEditorScreen(it, title = "Note", initial = a.note.orEmpty()) }) { text ->
                            AppGraph.scope.launch { AppGraph.study.setNote(a.id, text) }
                        }
                    }),
                    null,
                )
            },
        ) {
            if (annotation == null) {
                Note("This highlight was removed.")
                return@ScreenFrame
            }
            // The quote and note are reading text: they follow the Display size and spacing.
            val display by AppGraph.display.collectAsState()
            val style = readerTextStyle(display.textSize.scale, display.lineSpacing)
            // The quote verse by verse, as the reader lays it out; the stored one-line quote until the
            // chapter's text loads (or if it can't).
            val chapter = annotation.chapter
            var verses by remember(annotation.id) { mutableStateOf<List<Pair<Int, String>>?>(null) }
            LaunchedEffect(annotation.id, annotation.range) {
                verses = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = AppGraph.bookText(chapter.volumeId, chapter.bookId).c.getOrNull(chapter.chapter - 1).orEmpty()
                        quoteVerses(text, annotation.range).takeIf { it.isNotEmpty() }
                    }.getOrNull()
                }
            }
            val quoteColor = LightThemeTokens.colors.contentSecondary
            LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
                // First line: when it was made, lighter, as LightOS Notes dates a note.
                AppText(
                    text = formatDate(annotation.createdAt),
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )
                val lines = verses
                if (lines == null || lines.size == 1) {
                    BasicText(
                        text = "“${lines?.single()?.second ?: annotation.quote}”",
                        style = style.copy(color = quoteColor),
                        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                    )
                } else {
                    lines.forEachIndexed { i, (verse, text) ->
                        BasicText(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = quoteColor.copy(alpha = 0.6f))) { append("$verse  ") }
                                append(text)
                            },
                            style = style.copy(color = quoteColor),
                            modifier = Modifier.padding(bottom = (if (i == lines.lastIndex) 0.5f else VERSE_GAP_UNITS).gridUnitsAsDp()),
                        )
                    }
                }
                if (annotation.hasNote) {
                    SectionLabel("Note")
                    BasicText(text = annotation.note.orEmpty(), style = style)
                }
            }
        }
    }
}

/** Asks before removing a highlight or note; the note goes with it, so it says so. */
fun confirmRemove(sealedActivity: SealedLightActivity, annotation: Annotation) = ConfirmScreen(
    sealedActivity,
    title = if (annotation.hasNote) "Remove note" else "Remove highlight",
    message = if (annotation.hasNote) "Remove this note and its highlight?" else "Remove this highlight?",
    confirmLabel = "REMOVE",
)

/** A footnote's text and references. Tapping a reference opens the reader at that verse. */
class FootnoteScreen(
    sealedActivity: SealedLightActivity,
    private val ref: VerseRef,
    private val footnote: Footnote,
) : SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()


    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val catalog = AppGraph.catalog
        ScreenFrame(title = "${catalog.verseLabel(ref)} ${footnote.marker}", onBack = { goBack() }, rightButton = nowPlayingButton()) {
            LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
                if (footnote.text.isNotBlank()) Body(footnote.text)
                footnote.verseRefs.filter { catalog.scriptures.book(it.bookId) != null }.forEach { target ->
                    ListRow(catalog.verseLabel(target)) {
                        navigateTo({ ReaderScreen(it, target.chapterTarget, focusVerse = target.verse) })
                    }
                }
            }
        }
    }
}
