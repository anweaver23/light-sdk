package io.github.anweaver23.scriptures.core

/** A footnote marker as drawn: the letters sit at display characters [start] until [end]. */
data class MarkerSpan(val footnote: Footnote, val start: Int, val end: Int)

/**
 * One verse as the reader draws it: the verse number, two spaces, then the text with footnote
 * markers inserted right after their words. Everything here maps between the verse's own
 * character offsets (what [Words.spans] measures) and the drawn string, so touches and spans line up.
 */
class VerseDisplay private constructor(
    val verse: Int,
    val text: String,
    /** Characters of the verse number at the start of [text]. */
    val numberEnd: Int,
    /** Display range of each word, start inclusive and end exclusive, by word index. */
    val wordStarts: IntArray,
    val wordEnds: IntArray,
    /** Each word's display range widened over punctuation touching it, for highlights. */
    private val markStarts: IntArray,
    private val markEnds: IntArray,
    val markers: List<MarkerSpan>,
) {
    val wordCount: Int get() = wordStarts.size

    /** Display characters covering [words], from the first word's start to the last word's end. */
    fun charRange(words: IntRange): IntRange? {
        if (words.isEmpty() || words.first < 0 || words.last >= wordCount) return null
        return wordStarts[words.first] until wordEnds[words.last]
    }

    /**
     * Display characters a highlight of [words] covers: [charRange] plus punctuation touching the
     * first and last word, so "knowledge." is highlighted with its full stop.
     */
    fun markRange(words: IntRange): IntRange? {
        if (words.isEmpty() || words.first < 0 || words.last >= wordCount) return null
        return markStarts[words.first] until markEnds[words.last]
    }

    /**
     * The word at a display [offset] (as a text layout reports for a touch). An offset right after a
     * word's last letter still counts as that word, since layouts snap to the nearest caret position.
     */
    fun wordAt(offset: Int): Int? {
        for (i in 0 until wordCount) {
            if (offset in wordStarts[i]..wordEnds[i]) return i
        }
        return null
    }

    /**
     * The closest word to a display [offset], for dragging a selection across gaps: the word under it,
     * else the last word starting before it, else the first word. Null when the verse has no words.
     */
    fun nearestWord(offset: Int): Int? {
        if (wordCount == 0) return null
        wordAt(offset)?.let { return it }
        var best = 0
        for (i in 0 until wordCount) if (wordStarts[i] <= offset) best = i
        return best
    }

    fun markerAt(offset: Int): MarkerSpan? = markers.firstOrNull { offset in it.start..it.end }

    companion object {
        const val NUMBER_GAP = "  "

        fun build(verse: Int, text: String, footnotes: List<Footnote> = emptyList()): VerseDisplay {
            val spans = Words.spans(text)
            val byWord = footnotes.filter { it.word in spans.indices }.groupBy { it.word }
            val out = StringBuilder()
            out.append(verse).append(NUMBER_GAP)
            val numberEnd = verse.toString().length
            val starts = IntArray(spans.size)
            val ends = IntArray(spans.size)
            val markStarts = IntArray(spans.size)
            val markEnds = IntArray(spans.size)
            val markers = ArrayList<MarkerSpan>()
            var cursor = 0
            for (span in spans) {
                out.append(text, cursor, span.start)
                starts[span.index] = out.length
                // Leading punctuation sits in the gap just appended, so it's directly before the word.
                markStarts[span.index] = out.length - (span.start - Words.punctuationStart(text, span.start))
                out.append(text, span.start, span.end)
                ends[span.index] = out.length
                cursor = span.end
                byWord[span.index]?.forEach { note ->
                    val s = out.length
                    out.append(note.marker)
                    markers += MarkerSpan(note, s, out.length)
                }
                // Trailing punctuation is appended next, after any markers.
                markEnds[span.index] = out.length + (Words.punctuationEnd(text, span.end) - span.end)
            }
            out.append(text, cursor, text.length)
            return VerseDisplay(verse, out.toString(), numberEnd, starts, ends, markStarts, markEnds, markers)
        }
    }
}

/**
 * The first verse still visible when the content is scrolled to [scrollY]. [bottoms] holds each
 * verse's bottom edge in content coordinates (verse n at index n - 1). Null before layout.
 */
fun topVisibleVerse(bottoms: List<Int>, scrollY: Int): Int? {
    if (bottoms.isEmpty()) return null
    val i = bottoms.indexOfFirst { it > scrollY }
    return if (i < 0) bottoms.size else i + 1
}
