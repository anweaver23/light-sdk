package io.github.anweaver23.scriptures.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReferencesTest {
    private val index = Catalog.parseIndex(File("src/main/assets/scriptures/index.json").readText())

    private fun find(q: String) = References.find(index, q)

    @Test
    fun `a book name alone is the book, not its chapters`() {
        val m = find("genesis").single()
        assertIs<RefMatch.BookRef>(m)
        assertEquals("gen", m.book.id)
    }

    @Test
    fun `book and chapter is that chapter`() {
        val m = find("Genesis 1").single()
        assertIs<RefMatch.ChapterRef>(m)
        assertEquals(Target.Chapter("ot", "gen", 1), m.target)
    }

    @Test
    fun `book chapter and verse is that verse`() {
        val m = find("1 nephi 3:7").single()
        assertIs<RefMatch.VerseRefMatch>(m)
        assertEquals(VerseRef("bofm", "1-ne", 3, 7), m.ref)
    }

    @Test
    fun `abbreviations and prefixes work`() {
        assertEquals("1-ne", find("1 Ne. 3").single().book.id)
        assertEquals("dc", find("D&C 4").single().book.id)
        assertEquals("dc", find("dc 4").single().book.id)
        assertEquals("rev", find("revel").single().book.id)
    }

    @Test
    fun `an exact name beats a prefix`() {
        assertEquals(listOf("john"), find("john").map { it.book.id })
    }

    @Test
    fun `a one-chapter book reads its number as a verse`() {
        val m = find("jude 5").single()
        assertIs<RefMatch.VerseRefMatch>(m)
        assertEquals(VerseRef("nt", "jude", 1, 5), m.ref)
    }

    @Test
    fun `chapters out of range and plain words match nothing`() {
        assertTrue(find("genesis 51").isEmpty())
        assertTrue(find("faith").isEmpty())
        assertTrue(find("go").isEmpty())
        assertTrue(find("12").isEmpty())
    }
}
