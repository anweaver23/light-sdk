package io.github.anweaver23.scriptures.core

/**
 * One word inside a verse: its position among the verse's words ([index], 0-based) and its
 * character range in the verse string ([start] inclusive, [end] exclusive).
 *
 * Word indexes are the shared anchor for everything layered on top of the text (highlights,
 * notes, footnote markers, Strong's numbers), so every feature must tokenize with [Words.spans].
 * Changing the rules here re-anchors all of that data; treat it as a file-format change.
 */
data class WordSpan(val index: Int, val start: Int, val end: Int) {
    fun text(verse: String): String = verse.substring(start, end)
}

object Words {
    /**
     * A word is a run of letters or digits, allowing a single apostrophe (' or ’) or hyphen
     * between two letters, so "man's", "Beth-el" and "LORD" are one word each. Everything else
     * (spaces, punctuation, em dashes) separates words.
     */
    fun spans(verse: String): List<WordSpan> {
        val out = ArrayList<WordSpan>()
        var i = 0
        while (i < verse.length) {
            if (!verse[i].isLetterOrDigit()) {
                i++
                continue
            }
            val start = i
            while (i < verse.length) {
                val c = verse[i]
                if (c.isLetterOrDigit()) {
                    i++
                } else if (c in JOINERS && i + 1 < verse.length && verse[i + 1].isLetter() && verse[i - 1].isLetter()) {
                    i++
                } else {
                    break
                }
            }
            out += WordSpan(out.size, start, i)
        }
        return out
    }

    private val JOINERS = setOf('\'', '’', '-')
}
