package io.github.anweaver23.scriptures.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerseLayoutTest {
    private val verse = "And now as I said concerning faith—faith is not"

    @Test
    fun `plain verse keeps word offsets after the number`() {
        val d = VerseDisplay.build(21, verse)
        assertEquals("21  $verse", d.text)
        assertEquals(2, d.numberEnd)
        val spans = Words.spans(verse)
        assertEquals(spans.size, d.wordCount)
        spans.forEach { s ->
            assertEquals(s.text(verse), d.text.substring(d.wordStarts[s.index], d.wordEnds[s.index]))
        }
    }

    @Test
    fun `markers are inserted after their words and shift what follows`() {
        val notes = listOf(Footnote(word = 6, marker = "a"), Footnote(word = 6, marker = "b"), Footnote(word = 0, marker = "c"))
        val d = VerseDisplay.build(1, verse, notes)
        assertEquals("1  Andc now as I said concerning faithab—faith is not", d.text)
        assertEquals(listOf("c", "a", "b"), d.markers.map { d.text.substring(it.start, it.end) })
        assertEquals("faith", d.text.substring(d.wordStarts[7], d.wordEnds[7]))
        assertEquals("not", d.text.substring(d.wordStarts[9], d.wordEnds[9]))
    }

    @Test
    fun `footnotes pointing past the last word are dropped`() {
        val d = VerseDisplay.build(1, "Short verse.", listOf(Footnote(word = 5, marker = "a")))
        assertEquals("1  Short verse.", d.text)
        assertTrue(d.markers.isEmpty())
    }

    @Test
    fun `hit offsets map to words and markers`() {
        val d = VerseDisplay.build(3, "I am", listOf(Footnote(word = 0, marker = "a")))
        // "3  Ia am"
        assertNull(d.wordAt(0)) // the verse number
        assertEquals(0, d.wordAt(3))
        assertEquals(0, d.wordAt(4)) // caret right after "I"
        assertEquals("a", d.markerAt(4)?.footnote?.marker)
        assertEquals(1, d.wordAt(6))
        assertEquals(1, d.nearestWord(99))
        assertEquals(0, d.nearestWord(1))
    }

    @Test
    fun `char range covers the gap between words`() {
        val d = VerseDisplay.build(1, "faith is not")
        assertEquals("faith is", d.charRange(0..1)?.let { d.text.substring(it.first, it.last + 1) })
        assertNull(d.charRange(2..5))
    }

    @Test
    fun `top visible verse`() {
        val bottoms = listOf(100, 250, 400)
        assertEquals(1, topVisibleVerse(bottoms, 0))
        assertEquals(2, topVisibleVerse(bottoms, 100))
        assertEquals(3, topVisibleVerse(bottoms, 399))
        assertEquals(3, topVisibleVerse(bottoms, 1000))
        assertNull(topVisibleVerse(emptyList(), 0))
    }

    @Test
    fun `footnote fixture parses by chapter and verse`() {
        val book = BookFootnotes.parse(File("src/test/resources/footnotes/bofm/alma.json").readText())
        val ch32 = book.chapter(32)
        assertEquals(setOf(21), ch32.keys) // empty lists and bad keys are skipped
        assertEquals(listOf("a", "b"), ch32.getValue(21).map { it.marker }) // sorted by word
        assertEquals(
            listOf(VerseRef("bofm", "alma", 32, 27), VerseRef("bofm", "ether", 12, 6)),
            ch32.getValue(21).first().verseRefs,
        )
        assertEquals(setOf(1), book.chapter(33).keys)
        assertTrue(book.chapter(1).isEmpty())
        assertTrue(BookFootnotes.EMPTY.chapter(32).isEmpty())
    }

    @Test
    fun `strongs root links`() {
        assertEquals(listOf("H7218", "H7225"), Strongs.refsIn("from H7218 (compare H07225); see H7218", self = "H1"))
        assertEquals(listOf("G3004"), Strongs.refsIn("from G3004", self = "G3056"))
        assertEquals(emptyList(), Strongs.refsIn("from H7218", self = "H07218"))
        assertEquals(emptyList(), Strongs.refsIn(null))
        assertTrue(Strongs.covers("ot") && Strongs.covers("nt") && !Strongs.covers("bofm"))
    }
}
