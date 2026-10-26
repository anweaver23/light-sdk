package io.github.anweaver23.scriptures.core

/** A scripture reference typed into search. */
sealed interface RefMatch {
    val volumeId: String
    val book: Book

    /** Just a book ("genesis"): opens the book's chapter list. */
    data class BookRef(override val volumeId: String, override val book: Book) : RefMatch

    /** A chapter ("genesis 1"). */
    data class ChapterRef(override val volumeId: String, override val book: Book, val chapter: Int) : RefMatch {
        val target: Target.Chapter get() = Target.Chapter(volumeId, book.id, chapter)
    }

    /** A verse ("genesis 1:3", or "jude 5" in a one-chapter book). */
    data class VerseRefMatch(override val volumeId: String, override val book: Book, val ref: VerseRef) : RefMatch
}

/**
 * Reads search queries as scripture references: a book name or abbreviation, optionally followed by a
 * chapter and a verse ("1 ne 3:7", "D&C 4", "gen"). A name matches a book's title or abbreviation
 * exactly, or as the start of its title (3+ letters). Exact matches win over prefix matches, so
 * "john" is John, not 1 John. A chapter outside the book matches nothing.
 */
object References {
    // Lazy name, so "genesis 1:3" splits as genesis / 1 / 3, not "genesis 1:" / 3.
    private val TRAILING_NUMBERS = Regex("""^(.*?\D)\s*(\d+)(?:\s*:\s*(\d+))?$""")

    /** Lowercase, punctuation (except &) to spaces, single spaces: "Joseph Smith—History" → "joseph smith history". */
    fun normalize(s: String): String =
        s.lowercase().map { if (it.isLetterOrDigit() || it == '&') it else ' ' }.joinToString("")
            .trim().replace(Regex("""\s+"""), " ")

    fun find(index: ScriptureIndex, query: String): List<RefMatch> {
        val q = query.trim().lowercase().replace('.', ' ').trim()
        if (q.isEmpty()) return emptyList()
        val numbers = TRAILING_NUMBERS.find(q)
        val name = normalize(numbers?.groupValues?.get(1) ?: q)
        val chapter = numbers?.groupValues?.get(2)?.toIntOrNull()
        val verse = numbers?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (name.isEmpty()) return emptyList()

        val books = matchBooks(index, name)
        return books.mapNotNull { (volumeId, book) ->
            when {
                chapter == null -> RefMatch.BookRef(volumeId, book)
                // "jude 5": a one-chapter book's number is a verse.
                verse == null && book.chapters == 1 && chapter > 1 ->
                    RefMatch.VerseRefMatch(volumeId, book, VerseRef(volumeId, book.id, 1, chapter))
                chapter !in 1..book.chapters -> null
                verse == null -> RefMatch.ChapterRef(volumeId, book, chapter)
                verse < 1 -> null
                else -> RefMatch.VerseRefMatch(volumeId, book, VerseRef(volumeId, book.id, chapter, verse))
            }
        }
    }

    private fun matchBooks(index: ScriptureIndex, name: String): List<Pair<String, Book>> {
        val all = index.volumes.flatMap { v -> v.books.map { v.id to it } }
        val exact = all.filter { (_, b) -> name in keys(b) }
        if (exact.isNotEmpty()) return exact
        if (name.count { it.isLetter() } < 3) return emptyList()
        return all.filter { (_, b) -> normalize(b.title).startsWith(name) }
    }

    /** Names a book answers to exactly: its title and abbreviation, with and without spaces. */
    private fun keys(book: Book): Set<String> {
        val names = listOf(normalize(book.title), normalize(book.abbrev))
        return (names + names.map { it.replace(" ", "") } + names.map { it.replace("&", "") }).toSet()
    }
}
