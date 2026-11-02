package io.github.anweaver23.scriptures.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The KJV's italics for one book: words the translators supplied, as inclusive runs of word indexes
 * ([Words.spans] numbering) per verse. Built by tool/scripts/build_italics_assets.py from
 * `assets/italics/<ot|nt>/<book>.json` (see assets/italics/SOURCE.md). Only the Bible has italics.
 */
class BookItalics(
    /** CRC-32 of the book's tokens as the generator saw them; see [StrongsData.tokenChecksum]. */
    val tokenChecksum: Long,
    private val chapters: List<List<List<Int>>>,
) {
    /** Italic word runs of [chapter], by verse number. */
    fun chapter(chapter: Int): Map<Int, List<IntRange>> {
        val verses = chapters.getOrNull(chapter - 1) ?: return emptyMap()
        val out = HashMap<Int, List<IntRange>>()
        verses.forEachIndexed { i, flat ->
            if (flat.size >= 2) out[i + 1] = (0 until flat.size / 2).map { flat[it * 2]..flat[it * 2 + 1] }
        }
        return out
    }

    companion object {
        val EMPTY = BookItalics(0, emptyList())
        private val json = Json { ignoreUnknownKeys = true }

        fun path(volumeId: String, bookId: String) = "italics/$volumeId/$bookId.json"

        fun parse(text: String): BookItalics {
            val file = json.decodeFromString(ItalicsFileJson.serializer(), text)
            return BookItalics(file.tok, file.c)
        }
    }
}

@Serializable
internal data class ItalicsFileJson(val tok: Long, val c: List<List<List<Int>>>)

/** Where the reader gets italics. Implementations return [BookItalics.EMPTY] when a book has none. */
fun interface ItalicsSource {
    fun book(volumeId: String, bookId: String): BookItalics
}
