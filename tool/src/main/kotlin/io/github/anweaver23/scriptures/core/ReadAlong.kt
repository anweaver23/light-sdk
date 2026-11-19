package io.github.anweaver23.scriptures.core

/**
 * One stretch of what a chapter's recording reads aloud: [chars] characters of speech, drawn between
 * [top] and [bottom] in the chapter column. Spoken text with nothing on the page (the reader's
 * "Chapter 6") has [top] == [bottom]; a pause after a verse runs over the gap below it.
 */
data class ReadSegment(val chars: Int, val top: Float, val bottom: Float)

/**
 * Auto-scroll's guess at where the reading is, with no timing data: the recording is assumed to read
 * at an even pace through its speech, so a share of the recording is the same share of the spoken
 * characters. Verse numbers and footnote letters take up room but no time, so the guess is by
 * characters, not height.
 *
 * Measured on the Church's recordings (1 Nephi 2 and 6, Alma 32): about 15 characters a second; a
 * pause of about 0.7 s after each verse and 1.3 s after the heading; numbers are read out in full
 * ("600–592" is "six hundred to five hundred ninety-two") and a dash is a pause, which is why
 * headings, with their dates, take longer than their length.
 */
object ReadAlong {
    /** The pause after each verse, in characters of speech. */
    const val VERSE_PAUSE = 10

    /** The pause after the heading, before verse 1. */
    const val HEADING_PAUSE = 18

    /** A dash read as a pause. */
    private const val DASH_PAUSE = 10

    /** A digit read out ("592": "five hundred ninety-two"). */
    private const val PER_DIGIT = 6

    /** How long [text] takes to read aloud, in characters of ordinary speech. */
    fun spokenLength(text: String): Int {
        var n = 0
        for (c in text) {
            n += when {
                c.isDigit() -> PER_DIGIT
                c == '—' -> DASH_PAUSE
                else -> 1
            }
        }
        return n
    }

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
