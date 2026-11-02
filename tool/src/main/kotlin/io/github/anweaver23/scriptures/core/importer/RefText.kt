package io.github.anweaver23.scriptures.core.importer

import io.github.anweaver23.scriptures.core.Book
import io.github.anweaver23.scriptures.core.ScriptureIndex
import io.github.anweaver23.scriptures.core.Span

/**
 * Finds scripture references in note and index text, as the Church prints them, and turns them into
 * links: "Mosiah 4:2; Morm. 9:11; D&C 14:9; 76:24 (20–24)". A reference after "; " or ", " without a
 * book name keeps the previous book ("76:24" is D&C 76:24); a bare number after ", " is another verse
 * of the same chapter. Ranges, parenthesized extra verses and everything else stay plain text.
 */
class RefText(private val index: ScriptureIndex) {

    private data class Name(val text: String, val volumeId: String, val book: Book)

    private val names: List<Name> = index.volumes.flatMap { v ->
        v.books.flatMap { b ->
            listOf(b.abbrev, b.title).distinct().map { Name(it, v.id, b) }
        }
    }.sortedByDescending { it.text.length }

    private val byText = names.associateBy { it.text }

    private val start = Regex(
        "(?<![\\p{L}\\d])(" + names.joinToString("|") { Regex.escape(it.text) } + ")\\s+(\\d+)(?::(\\d+))?(?![\\d])",
    )
    private val next = Regex("""([;,])\s*(\d+)(?::(\d+))?(?![\d:])""").toPattern()

    /** [text] as runs, references linked to verse ("ot/gen/1/1") or chapter ("dc-testament/dc/76") keys. */
    fun link(text: String): List<Span> {
        val out = ArrayList<Span>()
        var plainFrom = 0
        var pos = 0
        while (true) {
            val m = start.find(text, pos) ?: break
            val name = byText.getValue(m.groupValues[1])
            val chapter = m.groupValues[2].toInt()
            val verse = m.groupValues[3].toIntOrNull()
            val target = target(name, chapter, verse)
            if (target == null) {
                pos = m.range.last + 1
                continue
            }
            if (m.range.first > plainFrom) out += Span(text.substring(plainFrom, m.range.first))
            out += Span(m.value, to = target)
            var lastChapter = chapter
            var lastVerse = verse
            var at = m.range.last + 1
            // Follow-on references: "; 7:22", ", 5", "; 88".
            while (true) {
                val gap = text.indexOfFirstNonSpaceOrRange(at)
                val f = next.matcher(text).region(gap, text.length)
                if (!f.lookingAt()) break
                val sep = f.group(1)
                val n1 = f.group(2).toInt()
                val n2 = f.group(3)?.toIntOrNull()
                val (c, v) = when {
                    n2 != null -> n1 to n2
                    sep == "," && lastVerse != null -> lastChapter to n1
                    sep == ";" -> n1 to null
                    else -> break
                }
                val t = target(name, c, v) ?: break
                val numberStart = f.start(2)
                out += Span(text.substring(at, numberStart))
                out += Span(text.substring(numberStart, f.end()), to = t)
                lastChapter = c
                lastVerse = v
                at = f.end()
            }
            plainFrom = at
            pos = at
        }
        if (plainFrom < text.length) out += Span(text.substring(plainFrom))
        return merge(out)
    }

    /** Skips a range tail ("–24") and a parenthesized aside ("(20–24)") between references. */
    private fun String.indexOfFirstNonSpaceOrRange(from: Int): Int {
        var i = from
        while (i < length) {
            val c = this[i]
            when {
                c == '–' || c == '-' -> {
                    i++
                    while (i < length && (this[i].isDigit() || this[i] == ':')) i++
                }
                c == '(' -> {
                    val close = indexOf(')', i)
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
