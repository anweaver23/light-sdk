package io.github.anweaver23.scriptures.core

/**
 * One stretch of what a chapter's recording reads aloud: [chars] characters of text, drawn between
 * [top] and [bottom] in the chapter column. Spoken text with nothing on the page (the reader's
 * "Chapter 6") has [top] == [bottom].
 */
data class ReadSegment(val chars: Int, val top: Float, val bottom: Float)

/**
 * Auto-scroll's guess at where the reading is, with no timing data: the recording is assumed to read
 * at an even pace through its text, so a share of the recording is the same share of the characters.
 * Verse numbers, footnote letters and the gaps between verses take up room but no time, so the guess
 * is by characters, not height.
 */
object ReadAlong {
    /** Where [fraction] of the way through [segments]' characters is drawn, or null with no text. */
    fun y(segments: List<ReadSegment>, fraction: Float): Float? {
        val total = segments.sumOf { it.chars }
        if (total <= 0) return null
        var at = fraction.coerceIn(0f, 1f) * total
        for (s in segments) {
            if (s.chars <= 0) continue
            if (at <= s.chars) return s.top + (s.bottom - s.top) * (at / s.chars)
            at -= s.chars
        }
        return segments.last { it.chars > 0 }.bottom
    }
}
