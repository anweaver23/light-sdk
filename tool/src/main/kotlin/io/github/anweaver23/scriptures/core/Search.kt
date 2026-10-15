package io.github.anweaver23.scriptures.core

/** One book's verses, as the search scans them. */
class SearchBook(val volumeId: String, val bookId: String, val text: BookText)

/** A verse that matched, with the character range of the first matched word (for the snippet). */
data class SearchHit(val ref: VerseRef, val verse: String, val matchStart: Int, val matchEnd: Int)

/** The first [hits] in canonical order, plus how many verses matched in total. */
data class SearchResults(val hits: List<SearchHit>, val total: Int) {
    val more: Int get() = total - hits.size

    companion object {
        val EMPTY = SearchResults(emptyList(), 0)
    }
}

/**
 * Plain full-text search over verses.
 *
 * - Case-insensitive.
 * - Every query word must appear in the verse (AND).
 * - A query word matches the *start* of a verse word, as tokenized by [Words.spans]:
 *   "fait" finds "faith" and "faithful", but "aith" finds nothing.
 * - Curly and straight apostrophes are the same character, so "man's" finds "man’s".
 */
object Search {
    const val DEFAULT_LIMIT = 200

    /** Lowercases and turns curly apostrophes into straight ones. */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(foldChar(c))
        return sb.toString()
    }

    private fun foldChar(c: Char): Char = when (c) {
        '’', '‘', 'ʼ' -> '\''
        else -> c.lowercaseChar()
    }

    /** The query's words, folded and de-duplicated. A word that is a prefix of another is dropped as redundant. */
    fun parse(query: String): List<String> {
        val folded = fold(query)
        val words = Words.spans(folded).map { it.text(folded) }.distinct()
        return words.filter { w -> words.none { other -> other != w && other.startsWith(w) } }
    }

    /**
     * Where [terms] match in [verse], or null if any term is missing. The range is the earliest
     * matched word, so the snippet shows the first relevant bit of the verse.
     */
    fun match(verse: String, terms: List<String>): IntRange? {
        if (terms.isEmpty()) return null
        // Cheap substring pre-check before tokenizing. Apostrophes are left out of it, since the
        // verse may use either kind; the token check below is the real test.
        for (t in terms) {
            if (!verse.contains(prefilterPart(t), ignoreCase = true)) return null
        }
        val spans = Words.spans(verse)
        var first: WordSpan? = null
        for (t in terms) {
            val span = spans.firstOrNull { startsWithFolded(verse, it, t) } ?: return null
            if (first == null || span.start < first.start) first = span
        }
        return first!!.let { it.start until it.end }
    }

    private fun prefilterPart(term: String): String =
        term.split('\'').maxBy { it.length }

    private fun startsWithFolded(verse: String, span: WordSpan, term: String): Boolean {
        if (span.end - span.start < term.length) return false
        for (i in term.indices) {
            if (foldChar(verse[span.start + i]) != term[i]) return false
        }
        return true
    }

    /**
     * Scans [books] in order and returns the first [limit] hits plus the total count.
     * [checkCancelled] runs once per chapter so a caller can abandon a stale search.
     */
    fun search(
        books: Iterable<SearchBook>,
        query: String,
        limit: Int = DEFAULT_LIMIT,
        checkCancelled: () -> Unit = {},
    ): SearchResults {
        val terms = parse(query)
        if (terms.isEmpty()) return SearchResults.EMPTY
        val hits = ArrayList<SearchHit>()
        var total = 0
        for (book in books) {
            book.text.c.forEachIndexed { ci, verses ->
                checkCancelled()
                verses.forEachIndexed { vi, verse ->
                    val range = match(verse, terms)
                    if (range != null) {
                        total++
                        if (hits.size < limit) {
                            hits += SearchHit(
                                VerseRef(book.volumeId, book.bookId, ci + 1, vi + 1),
                                verse,
                                range.first,
                                range.last + 1,
                            )
                        }
                    }
                }
            }
        }
        return SearchResults(hits, total)
    }

    /**
     * A short excerpt of [verse] around [start]: up to [before] characters of lead-in, cut at a
     * word boundary, then the rest of the verse up to [maxChars]. Trimmed ends get an ellipsis.
     */
    fun snippet(verse: String, start: Int, before: Int = 24, maxChars: Int = 120): String {
        val spans = Words.spans(verse)
        val from = spans.firstOrNull { it.start >= start - before && it.start <= start }?.start ?: start
        val leading = from > 0
        var to = verse.length
        if (to - from > maxChars) {
            to = spans.lastOrNull { it.end <= from + maxChars && it.end > start }?.end ?: (from + maxChars)
        }
        val body = verse.substring(from, to).trim()
        return buildString {
            if (leading) append('…')
            append(body)
            if (to < verse.length) append('…')
        }
    }
}

