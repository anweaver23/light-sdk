package io.github.anweaver23.scriptures.core.importer

/**
 * Small text helpers for the import, written as plain loops on purpose: on Android every regex match
 * holds native (ICU) memory that's only freed when the garbage collector gets round to it, and an import
 * runs millions of them. That killed the app on the LP3 (3 GB of native memory in seconds).
 */
object Scan {
    /** Runs of whitespace as single spaces (not trimmed). */
    fun collapse(s: String): String {
        var needs = false
        for (i in s.indices) {
            val c = s[i]
            if (c.isWhitespace() && (c != ' ' || (i > 0 && s[i - 1].isWhitespace()))) {
                needs = true
                break
            }
        }
        if (!needs) return s
        val out = StringBuilder(s.length)
        var space = false
        for (c in s) {
            if (c.isWhitespace()) {
                if (!space) out.append(' ')
                space = true
            } else {
                out.append(c)
                space = false
            }
        }
        return out.toString()
    }

    fun clean(s: String): String = collapse(s).trim()

    /** The digits starting at [from] as a number and the index after them, or null when there are none. */
    fun number(s: String, from: Int): Pair<Int, Int>? {
        var i = from
        var n = 0
        while (i < s.length && s[i].isAsciiDigit() && i - from < 6) {
            n = n * 10 + (s[i] - '0')
            i++
        }
        return if (i == from) null else n to i
    }

    /** The first "chapter:verse" in [s]. */
    fun firstChapterVerse(s: String): Pair<Int, Int>? {
        var i = 0
        while (i < s.length) {
            if (s[i].isAsciiDigit() && (i == 0 || !s[i - 1].isAsciiDigit())) {
                val (a, after) = number(s, i) ?: return null
                if (after < s.length && s[after] == ':') {
                    val b = number(s, after + 1)
                    if (b != null) return a to b.first
                }
                i = after
            } else {
                i++
            }
        }
        return null
    }

    /** The number [s] starts with, after any spaces. */
    fun leadingNumber(s: String): Int? {
        var i = 0
        while (i < s.length && s[i].isWhitespace()) i++
        return number(s, i)?.first
    }

    /** Lowercase letters and digits, everything else one "-": "God, Creator" → "god-creator". */
    fun slug(name: String): String {
        val out = StringBuilder()
        var dash = false
        for (c in name.lowercase()) {
            when {
                c == '’' || c == '\'' -> Unit
                (c in 'a'..'z') || c.isAsciiDigit() -> {
                    if (dash && out.isNotEmpty()) out.append('-')
                    out.append(c)
                    dash = false
                }
                else -> dash = true
            }
        }
        return out.toString()
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
