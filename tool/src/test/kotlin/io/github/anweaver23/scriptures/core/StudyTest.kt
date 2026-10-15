package io.github.anweaver23.scriptures.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.SerializationException

class StudyTest {
    private val alma32 = Target.Chapter("bofm", "alma", 32)
    private fun p(verse: Int, word: Int) = WordPos(verse, word)
    private fun r(sv: Int, sw: Int, ev: Int, ew: Int) = WordRange(p(sv, sw), p(ev, ew))
    private val tenWords: (Int) -> Int = { 10 }

    private fun ann(id: String, range: WordRange, note: String? = null, at: Long = 0) =
        Annotations.create(id, alma32, range, "q", note, at)

    @Test
    fun `highlight inside an existing one is a no-op`() {
        val existing = listOf(ann("a", r(1, 0, 2, 5)))
        assertEquals(Annotations.Plan.NoOp, Annotations.planHighlight(existing, r(1, 4, 1, 9), tenWords))
    }

    @Test
    fun `overlapping and touching plain highlights merge into one`() {
        val existing = listOf(
            ann("left", r(1, 0, 1, 2)), // touches: ends right before 1:3
            ann("right", r(1, 6, 2, 1)), // overlaps
            ann("far", r(3, 0, 3, 1)),
        )
        val plan = assertIs<Annotations.Plan.Write>(Annotations.planHighlight(existing, r(1, 3, 1, 7), tenWords))
        assertEquals(r(1, 0, 2, 1), plan.range)
        assertEquals(setOf("left", "right"), plan.removeIds.toSet())
    }

    @Test
    fun `touching across a verse boundary merges`() {
        val existing = listOf(ann("a", r(1, 8, 1, 9)))
        val plan = assertIs<Annotations.Plan.Write>(Annotations.planHighlight(existing, r(2, 0, 2, 3), tenWords))
        assertEquals(r(1, 8, 2, 3), plan.range)
    }

    @Test
    fun `merging chains through highlights the union reaches`() {
        val existing = listOf(ann("b", r(1, 5, 1, 6)), ann("c", r(1, 7, 1, 9)))
        val plan = assertIs<Annotations.Plan.Write>(Annotations.planHighlight(existing, r(1, 2, 1, 4), tenWords))
        assertEquals(r(1, 2, 1, 9), plan.range)
        assertEquals(setOf("b", "c"), plan.removeIds.toSet())
    }

    @Test
    fun `notes are never absorbed`() {
        val existing = listOf(ann("n", r(1, 2, 1, 4), note = "keep me"))
        val plan = assertIs<Annotations.Plan.Write>(Annotations.planHighlight(existing, r(1, 3, 1, 8), tenWords))
        assertEquals(r(1, 3, 1, 8), plan.range)
        assertEquals(emptyList(), plan.removeIds)
    }

    @Test
    fun `tap prefers a note then the newest`() {
        val list = listOf(
            ann("old", r(1, 0, 1, 9), at = 1),
            ann("note", r(1, 2, 1, 3), note = "n", at = 2),
            ann("new", r(1, 0, 1, 9), at = 3),
        )
        assertEquals("note", Annotations.at(list, p(1, 2))?.id)
        assertEquals("new", Annotations.at(list, p(1, 5))?.id)
        assertNull(Annotations.at(list, p(2, 0)))
    }

    @Test
    fun `blank notes are stored as plain highlights`() {
        assertNull(ann("x", r(1, 0, 1, 0), note = "   ").note)
    }

    @Test
    fun `round trips through versioned json`() {
        val a = ann("a", r(21, 3, 23, 0), note = "Faith is not a perfect knowledge", at = 42)
        val text = StudyFormat.encodeAnnotations(listOf(a))
        assertEquals(true, text.startsWith("{\"version\":1"))
        assertEquals(true, text.contains("\"bofm/alma/32/21\""))
        assertEquals(listOf(a), StudyFormat.decodeAnnotations(text))

        val b = Bookmark("b", VerseRef("nt", "heb", 11, 1), 7)
        assertEquals(listOf(b), StudyFormat.decodeBookmarks(StudyFormat.encodeBookmarks(listOf(b))))
    }

    @Test
    fun `refuses a file from a newer version`() {
        assertFailsWith<SerializationException> {
            StudyFormat.decodeAnnotations("""{"version":99,"annotations":[]}""")
        }
    }

    @Test
    fun `reference labels`() {
        val catalog = Catalog(Catalog.parseIndex(File("src/main/assets/scriptures/index.json").readText()), emptyList())
        val v = { book: String, vol: String, c: Int, verse: Int -> VerseRef(vol, book, c, verse) }
        assertEquals("Alma 32:21", catalog.verseLabel(v("alma", "bofm", 32, 21)))
        assertEquals("Alma 32:21–23", catalog.rangeLabel(v("alma", "bofm", 32, 21), v("alma", "bofm", 32, 23)))
        assertEquals("Alma 32:43–33:2", catalog.rangeLabel(v("alma", "bofm", 32, 43), v("alma", "bofm", 33, 2)))
        assertEquals("Alma 32:21", catalog.rangeLabel(v("alma", "bofm", 32, 21), v("alma", "bofm", 32, 21)))
        assertEquals("Enos 1:3", catalog.verseLabel(v("enos", "bofm", 1, 3)))
        val dc = catalog.scriptures.volumes.first { vol -> vol.books.any { it.id == "dc" } }.id
        assertEquals("D&C 4:2", catalog.verseLabel(v("dc", dc, 4, 2)))
    }
}
