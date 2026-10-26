package io.github.anweaver23.scriptures.core

/** One find-in-chapter hit, as display characters [start] until [end] of [verse]'s [VerseDisplay]. */
data class FindMatch(val verse: Int, val start: Int, val end: Int)

object Find {
    /**
     * Every case-insensitive occurrence of [query] in the chapter's verse text, in reading order.
     * Verse numbers are skipped, and footnote markers are ignored, so "faith" still matches where a
     * marker sits inside it ("faithab"); the match's range then covers the marker too.
     */
    fun matches(displays: List<VerseDisplay>, query: String): List<FindMatch> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return displays.flatMap { matchesIn(it, needle) }
    }

    private fun matchesIn(display: VerseDisplay, needle: String): List<FindMatch> {
        // The verse text with markers taken out, and where each of its characters sits in the display.
        val inMarker = BooleanArray(display.text.length)
        display.markers.forEach { m -> for (i in m.start until m.end.coerceAtMost(inMarker.size)) inMarker[i] = true }
        val textStart = display.numberEnd + VerseDisplay.NUMBER_GAP.length
        val positions = (textStart until display.text.length).filter { !inMarker[it] }
        val plain = positions.map { display.text[it] }.joinToString("").lowercase()

        val out = mutableListOf<FindMatch>()
        var from = 0
        while (true) {
            val at = plain.indexOf(needle, from)
            if (at < 0) break
            out += FindMatch(display.verse, positions[at], positions[at + needle.length - 1] + 1)
            from = at + needle.length
        }
        return out
    }
}
