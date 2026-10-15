package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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

/** Full-screen note entry. Returns the text on SAVE; backing out returns nothing. */
class NoteEditorScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        val state = rememberTextFieldState(initial)
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            LightTextInputEditor(
                title = title,
                state = state,
                keyboardOptionsFlow = keyboardOptions,
                onSubmit = { goBack(it.toString()) },
                onBack = { goBack(null) },
                submitLabel = "SAVE",
                initialCaps = initial.isEmpty(),
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}

/** A section label inside the study screens, e.g. "Webster's 1828". */
@Composable
private fun SectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
    )
}

@Composable
private fun Body(text: String, lighten: Boolean = false) {
    LightText(
        text = text,
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

    constructor(sealedActivity: SealedLightActivity, word: String, ref: VerseRef, wordIndex: Int) :
        this(sealedActivity, word, ref, wordIndex, null)

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val result by produceState<DefineResult?>(null) {
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

        ScreenFrame(title = strongs ?: word, onBack = { goBack() }) {
            val r = result ?: return@ScreenFrame
            LightScrollView(Modifier.fillMaxSize()) {
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
        LightText(
            text = listOfNotNull(d.headword, d.partOfSpeech).joinToString("  "),
            variant = LightTextVariant.Copy,
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
        LightText(text = w.lemma, variant = LightTextVariant.Heading, modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
        Body(listOfNotNull(w.transliteration, w.pronunciation?.let { "($it)" }, w.strongs).joinToString("  "), lighten = true)
        Body(w.definition)
        w.kjvUsage?.let { Body("KJV: $it", lighten = true) }
        w.derivation?.let { derivation ->
            Body(derivation, lighten = true)
            Strongs.refsIn(derivation, self = w.strongs).forEach { root ->
                ListRow("Root $root") { navigateTo({ DefineScreen(it, root, null, -1, root) }) }
            }
        }
    }
}

/** A highlight or note, opened by tapping it in the reader: read, edit the note, or remove. */
class AnnotationScreen(sealedActivity: SealedLightActivity, private val id: String) :
    SimpleLightScreen<Unit>(sealedActivity) {

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
            bottomBar = annotation?.let { a ->
                listOf(
                    LightBarButton.LightIcon(LightIcons.TRASH, contentDescription = "Remove", onClick = {
                        AppGraph.scope.launch { AppGraph.study.deleteAnnotation(a.id) }
                        goBack()
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
            LightScrollView(Modifier.fillMaxSize()) {
                Body("“${annotation.quote}”", lighten = true)
                if (annotation.hasNote) {
                    SectionLabel("Note")
                    Body(annotation.note.orEmpty())
                }
            }
        }
    }
}

/** A footnote's text and references. Tapping a reference opens the reader at that verse. */
class FootnoteScreen(
    sealedActivity: SealedLightActivity,
    private val ref: VerseRef,
    private val footnote: Footnote,
) : SimpleLightScreen<Unit>(sealedActivity) {

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val catalog = AppGraph.catalog
        ScreenFrame(title = "${catalog.verseLabel(ref)} ${footnote.marker}", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
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
