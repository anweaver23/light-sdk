package io.github.anweaver23.scriptures.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindTest {
    private val verses = listOf(
        VerseDisplay.build(1, "And now as I said concerning faith—faith is not"),
        VerseDisplay.build(2, "Faith cometh by hearing"),
    )

    @Test
    fun `finds every occurrence case-insensitively in reading order`() {
        val found = Find.matches(verses, "FAITH")
        assertEquals(listOf(1, 1, 2), found.map { it.verse })
        found.forEach { m ->
            assertEquals("faith", verses[m.verse - 1].text.substring(m.start, m.end).lowercase())
        }
    }

    @Test
    fun `verse numbers never match`() {
        val d = listOf(VerseDisplay.build(12, "one two"))
        assertTrue(Find.matches(d, "12").isEmpty())
        assertTrue(Find.matches(d, "1").isEmpty())
    }

    @Test
    fun `a footnote marker inside a word does not break the match`() {
        val d = listOf(VerseDisplay.build(1, "concerning faith is", listOf(Footnote(word = 1, marker = "a"))))
        assertEquals("1  concerning faitha is", d[0].text)
        val m = Find.matches(d, "faith is").single()
        assertEquals("faitha is", d[0].text.substring(m.start, m.end))
    }

    @Test
    fun `blank query finds nothing`() {
        assertTrue(Find.matches(verses, "  ").isEmpty())
    }
}
