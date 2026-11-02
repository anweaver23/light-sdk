package io.github.anweaver23.scriptures.core.importer

import io.github.anweaver23.scriptures.core.Book
import io.github.anweaver23.scriptures.core.ScriptureIndex
import io.github.anweaver23.scriptures.core.Span

/**
 * Finds scripture references in note and index text, as the Church prints them, and turns them into
 * links: "Mosiah 4:2; Morm. 9:11; D&C 14:9; 76:24 (20–24)". A reference after "; " or ", " without a
 * book name keeps the previous book ("76:24" is D&C 76:24); a bare number after ", " is another verse
 * of the same chapter. Ranges, parenthesized extra verses and everything else stay plain text.
 *
 * Plain loops, no regex: see [Scan].
 */
class RefText(private val index: ScriptureIndex) {

    private class Name(val text: String, val volumeId: String, val book: Book)

    /** Book names and abbreviations by first character, longest first. */
    private val byFirst: Map<Char, List<Name>> = index.volumes.flatMap { v ->
        v.books.flatMap { b -> listOf(b.abbrev, b.title).distinct().map { Name(it, v.id, b) } }
    }.groupBy { it.text[0] }.mapValues { (_, l) -> l.sortedByDescending { it.text.length } }

    private class Hit(val name: Name, val start: Int, val numberStart: Int, val end: Int, val chapter: Int, val verse: Int?)

    /** A book name at [i] followed by "chapter[:verse]". */
    private fun hitAt(text: String, i: Int): Hit? {
        if (i > 0 && text[i - 1].isLetterOrDigit()) return null
        val candidates = byFirst[text[i]] ?: return null
        for (name in candidates) {
            if (!text.regionMatches(i, name.text, 0, name.text.length)) continue
            var j = i + name.text.length
            if (j >= text.length || !text[j].isWhitespace()) continue
            while (j < text.length && text[j].isWhitespace()) j++
            val (chapter, afterChapter) = Scan.number(text, j) ?: continue
            var end = afterChapter
            var verse: Int? = null
            if (end < text.length && text[end] == ':') {
                val v = Scan.number(text, end + 1)
                if (v != null) {
                    verse = v.first
                    end = v.second
                }
            }
            if (end < text.length && text[end].isDigit()) continue
            return Hit(name, i, j, end, chapter, verse)
        }
        return null
    }

    /** [text] as runs, references linked to verse ("ot/gen/1/1") or chapter ("dc-testament/dc/76") keys. */
    fun link(text: String): List<Span> {
        // Fast path: no digits, no references.
        if (text.none { it in '0'..'9' }) return listOf(Span(text))
        val out = ArrayList<Span>()
        var plainFrom = 0
        var i = 0
        while (i < text.length) {
            val hit = hitAt(text, i)
            val target = hit?.let { target(it.name, it.chapter, it.verse) }
            if (hit == null || target == null) {
                i++
                continue
            }
            if (hit.start > plainFrom) out += Span(text.substring(plainFrom, hit.start))
            out += Span(text.substring(hit.start, hit.end), to = target)
            var lastChapter = hit.chapter
            var lastVerse = hit.verse
            var at = hit.end
            // Follow-on references: "; 7:22", ", 5", "; 88".
            while (true) {
                var k = skipRangeOrAside(text, at)
                if (k >= text.length || (text[k] != ';' && text[k] != ',')) break
                val sep = text[k]
                k++
                while (k < text.length && text[k] == ' ') k++
                val (n1, after1) = Scan.number(text, k) ?: break
                var end = after1
                var n2: Int? = null
                if (end < text.length && text[end] == ':') {
                    val v = Scan.number(text, end + 1) ?: break
                    n2 = v.first
                    end = v.second
                }
                if (end < text.length && (text[end].isDigit() || text[end] == ':')) break
                val (c, v) = when {
                    n2 != null -> n1 to n2
                    sep == ',' && lastVerse != null -> lastChapter to n1
                    sep == ';' -> n1 to null
                    else -> break
                }
                // A new book name right here ("; Alma 5:1") is handled by the outer scan instead.
                val t = target(hit.name, c, v) ?: break
                out += Span(text.substring(at, k))
                out += Span(text.substring(k, end), to = t)
                lastChapter = c
                lastVerse = v
                at = end
            }
            plainFrom = at
            i = at
        }
        if (plainFrom < text.length) out += Span(text.substring(plainFrom))
        return merge(out)
    }

    /** Skips a range tail ("–24") and a parenthesized aside ("(20–24)") between references. */
    private fun skipRangeOrAside(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '–' || c == '-' -> {
                    i++
                    while (i < text.length && (text[i].isDigit() || text[i] == ':')) i++
                }
                c == '(' -> {
                    val close = text.indexOf(')', i)
                    if (close < 0) return i
                    i = close + 1
                }
                c == ' ' -> i++
                else -> return i
            }
        }
        return i
    }

    private fun target(name: Name, chapter: Int, verse: Int?): String? {
        if (chapter !in 1..name.book.chapters) return null
        if (verse != null && verse < 1) return null
        val base = "${name.volumeId}/${name.book.id}/$chapter"
        return if (verse == null) base else "$base/$verse"
    }

    companion object {
        /** Joins neighbouring plain runs. */
        fun merge(spans: List<Span>): List<Span> {
            val out = ArrayList<Span>()
            for (s in spans) {
                if (s.t.isEmpty()) continue
                val last = out.lastOrNull()
                if (last != null && last.to == null && s.to == null && last.s == s.s) {
                    out[out.lastIndex] = last.copy(t = last.t + s.t)
                } else {
                    out += s
                }
            }
            return out
        }
    }
}
