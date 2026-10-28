package io.github.anweaver23.scriptures.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionTest {
    private fun p(verse: Int, word: Int) = WordPos(verse, word)

    @Test
    fun `positions order by verse then word`() {
        assertTrue(p(1, 9) < p(2, 0))
        assertTrue(p(2, 1) < p(2, 2))
        assertEquals(0, p(3, 3).compareTo(p(3, 3)))
    }

    @Test
    fun `selection normalizes when dragged backwards across verses`() {
        val sel = Selection(p(5, 2)).extendTo(p(3, 7))
        assertEquals(WordRange(p(3, 7), p(5, 2)), sel.range)
        assertEquals(p(5, 2), sel.anchor)
    }

    @Test
    fun `extending keeps the anchor and moves the focus`() {
        val sel = Selection(p(2, 4)).extendTo(p(2, 9)).extendTo(p(2, 1))
        assertEquals(WordRange(p(2, 1), p(2, 4)), sel.range)
        assertTrue(Selection(p(1, 1)).range.isSingleWord)
    }

    @Test
    fun `words in each verse of a multi-verse range`() {
        val range = WordRange(p(2, 3), p(4, 1))
        assertNull(range.wordsIn(1, 10))
        assertEquals(3..9, range.wordsIn(2, 10))
        assertEquals(0..5, range.wordsIn(3, 6))
        assertEquals(0..1, range.wordsIn(4, 8))
        assertNull(range.wordsIn(5, 8))
        assertNull(range.wordsIn(3, 0))
    }

    @Test
    fun `stale word indexes are clamped to the verse`() {
        assertEquals(2..4, WordRange(p(1, 2), p(1, 40)).wordsIn(1, 5))
        assertNull(WordRange(p(1, 7), p(1, 9)).wordsIn(1, 5))
    }

    @Test
    fun `overlap containment and union`() {
        val a = WordRange(p(1, 2), p(1, 5))
        val b = WordRange(p(1, 5), p(2, 0))
        val c = WordRange(p(1, 6), p(1, 8))
        assertTrue(a.overlaps(b))
        assertFalse(a.overlaps(c))
        assertEquals(WordRange(p(1, 2), p(2, 0)), a.union(b))
        assertTrue(a.union(b).contains(a))
        assertFalse(a.contains(b))
    }

    @Test
    fun `quote spans verses from first to last letter`() {
        val verses = listOf("And now, behold, faith is not", "to have a perfect knowledge.")
        assertEquals("faith is not to have", quoteOf(verses, WordRange(p(1, 3), p(2, 1))))
        assertEquals("behold,", quoteOf(verses, WordRange(p(1, 2), p(1, 2))))
    }

    @Test
    fun `quote keeps punctuation and verse structure`() {
        val verses = listOf("And now, behold, faith is not", "to have a perfect knowledge.")
        assertEquals(
            listOf(1 to "faith is not", 2 to "to have a perfect knowledge."),
            quoteVerses(verses, WordRange(p(1, 3), p(2, 4))),
        )
        assertEquals("(the Lord)", quoteOf(listOf("saith (the Lord) unto"), WordRange(p(1, 1), p(1, 2))))
    }
}
