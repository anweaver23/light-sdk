package io.github.anweaver23.scriptures.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One footnote on the words [word]..[end] (indexes from [Words.spans]) of its verse; the marker is
 * drawn before the first. [marker] is the small letter ("a", "b", ...). [parts] is the note as linked
 * runs ("Luke 6:20" → a verse, "TG Poor" → the Topical Guide); [text] is the same note as plain text.
 * [refs] are extra verse keys ("bofm/alma/32/21") listed under the note; unknown keys are skipped.
 */
@Serializable
data class Footnote(
    val word: Int,
    val marker: String,
    val text: String = "",
    val refs: List<String> = emptyList(),
    val end: Int = word,
    val parts: List<Span> = emptyList(),
) {
    /** The note's runs, or its plain [text] as one run. */
    val spans: List<Span> get() = parts.ifEmpty { listOf(Span(text)) }

    val verseRefs: List<VerseRef> get() = refs.mapNotNull(VerseRef::fromKey)
}

/**
 * Asset format for `assets/footnotes/<volumeId>/<bookId>.json`:
 * ```
 * {"version": 1,
 *  "verses": {"32:21": [{"word": 3, "marker": "a", "text": "TG Faith.", "refs": ["bofm/alma/32/21"]}]}}
 * ```
 * Keys of `verses` are "chapter:verse". A missing file means the book has no footnotes.
 */
@Serializable
data class BookFootnotes(val version: Int = 1, val verses: Map<String, List<Footnote>> = emptyMap()) {

    /** The footnotes of one chapter, by verse number, sorted by word so markers render in order. */
    fun chapter(chapter: Int): Map<Int, List<Footnote>> {
        val prefix = "$chapter:"
        val out = HashMap<Int, List<Footnote>>()
        for ((key, notes) in verses) {
            if (!key.startsWith(prefix)) continue
            val verse = key.substring(prefix.length).toIntOrNull() ?: continue
            if (notes.isNotEmpty()) out[verse] = notes.sortedBy { it.word }
        }
        return out
    }

    companion object {
        val EMPTY = BookFootnotes()
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): BookFootnotes = json.decodeFromString(serializer(), text)
    }
}

/** Where the reader gets footnotes. Implementations return [BookFootnotes.EMPTY] when there are none. */
fun interface FootnoteSource {
    fun book(volumeId: String, bookId: String): BookFootnotes
}

object NoFootnotes : FootnoteSource {
    override fun book(volumeId: String, bookId: String) = BookFootnotes.EMPTY
}
