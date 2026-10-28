package io.github.anweaver23.scriptures.core

/**
 * A word inside one chapter: [verse] is 1-based, [word] is the index from [Words.spans].
 * Ordered by reading order, so a range of these can span verses.
 */
data class WordPos(val verse: Int, val word: Int) : Comparable<WordPos> {
    override fun compareTo(other: WordPos): Int =
        if (verse != other.verse) verse.compareTo(other.verse) else word.compareTo(other.word)
}

/** An inclusive run of words in one chapter, always with [start] <= [end]. */
data class WordRange(val start: WordPos, val end: WordPos) {
    init {
        require(start <= end) { "start $start is after end $end" }
    }

    val isSingleWord: Boolean get() = start == end

    operator fun contains(pos: WordPos): Boolean = pos in start..end

    fun overlaps(other: WordRange): Boolean = start <= other.end && other.start <= end

    fun contains(other: WordRange): Boolean = start <= other.start && other.end <= end

    fun union(other: WordRange): WordRange = WordRange(minOf(start, other.start), maxOf(end, other.end))

    /**
     * The word indexes this range covers in [verse], given that verse's word count.
     * Null when the range doesn't touch the verse (or the verse has no words).
     */
    fun wordsIn(verse: Int, wordCount: Int): IntRange? {
        if (wordCount <= 0 || verse < start.verse || verse > end.verse) return null
        val first = if (verse == start.verse) start.word else 0
        val last = if (verse == end.verse) end.word else wordCount - 1
        val clamped = first.coerceAtLeast(0)..last.coerceAtMost(wordCount - 1)
        return clamped.takeUnless { it.isEmpty() }
    }

    companion object {
        /** The range between two words in either order. */
        fun between(a: WordPos, b: WordPos): WordRange = if (a <= b) WordRange(a, b) else WordRange(b, a)
    }
}

/**
 * A live selection: [anchor] is the word the long-press landed on and stays put; [focus] follows
 * the finger (or the last tapped word). The selected range is everything between them.
 */
data class Selection(val anchor: WordPos, val focus: WordPos = anchor) {
    val range: WordRange get() = WordRange.between(anchor, focus)

    fun extendTo(pos: WordPos): Selection = copy(focus = pos)
}

/**
 * The text a range covers in each verse, as (verse number, text), from the first word to the last,
 * with punctuation touching them. [verses] is the chapter's verse list (verse n at index n - 1).
 */
fun quoteVerses(verses: List<String>, range: WordRange): List<Pair<Int, String>> {
    val parts = ArrayList<Pair<Int, String>>()
    for (v in range.start.verse..range.end.verse) {
        val text = verses.getOrNull(v - 1) ?: continue
        val spans = Words.spans(text)
        val words = range.wordsIn(v, spans.size) ?: continue
        val start = Words.punctuationStart(text, spans[words.first].start)
        val end = Words.punctuationEnd(text, spans[words.last].end)
        parts += v to text.substring(start, end)
    }
    return parts
}

/** [quoteVerses] as one line, verses joined with a space (what an annotation stores for lists). */
fun quoteOf(verses: List<String>, range: WordRange): String =
    quoteVerses(verses, range).joinToString(" ") { it.second }
