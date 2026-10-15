package io.github.anweaver23.scriptures.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One footnote, anchored after the word at index [word] (from [Words.spans]) of its verse.
 * [marker] is the small letter shown in the text ("a", "b", ...). [refs] are verse keys
 * ("bofm/alma/32/21") the note points to; unknown or malformed keys are skipped when shown.
 */
@Serializable
data class Footnote(
    val word: Int,
    val marker: String,
    val text: String = "",
    val refs: List<String> = emptyList(),
) {
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
