package io.github.anweaver23.scriptures.data

import io.github.anweaver23.scriptures.core.BookFootnotes
import io.github.anweaver23.scriptures.core.StudyFormat
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.core.VerseRef
import io.github.anweaver23.scriptures.core.WordPos
import io.github.anweaver23.scriptures.core.WordRange
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class StudyStoreTest {
    private val alma32 = Target.Chapter("bofm", "alma", 32)
    private fun r(sv: Int, sw: Int, ev: Int, ew: Int) = WordRange(WordPos(sv, sw), WordPos(ev, ew))

    private fun store(dir: File): StudyStore {
        var n = 0
        var t = 1000L
        return StudyStore(dir, clock = { t++ }, newId = { "id${n++}" })
    }

    private fun tempDir(): File = Files.createTempDirectory("study").toFile().also { it.deleteOnExit() }

    @Test
    fun `highlights notes and bookmarks persist across instances`() = runBlocking {
        val dir = tempDir()
        val s = store(dir)
        s.highlight(alma32, r(21, 0, 21, 2), { 10 }) { "quote" }
        s.highlight(alma32, r(21, 3, 21, 4), { 10 }) { "merged" } // touches → merged
        s.addNote(alma32, r(22, 0, 22, 1), "q2", "  my note ")
        s.addBookmark(VerseRef("bofm", "alma", 32, 21))

        val reopened = StudyStore(dir)
        reopened.load()
        val anns = reopened.annotations.value
        assertEquals(2, anns.size)
        assertEquals(r(21, 0, 21, 4), anns.first { it.note == null }.range)
        assertEquals("merged", anns.first { it.note == null }.quote)
        assertEquals("my note", anns.first { it.note != null }.note)
        assertEquals(listOf(VerseRef("bofm", "alma", 32, 21)), reopened.bookmarks.value.map { it.ref })
        assertTrue(File(dir, "annotations.json").readText().startsWith("{\"version\":${StudyFormat.VERSION}"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `highlighting inside an existing highlight writes nothing`() = runBlocking {
        val s = store(tempDir())
        s.highlight(alma32, r(1, 0, 1, 9), { 10 }) { "a" }
        assertNull(s.highlight(alma32, r(1, 2, 1, 3), { 10 }) { "b" })
        assertEquals(1, s.annotations.value.size)
    }

    @Test
    fun `clearing a note keeps the highlight and delete removes it`() = runBlocking {
        val s = store(tempDir())
        val a = s.addNote(alma32, r(1, 0, 1, 1), "q", "note")
        s.setNote(a.id, " ")
        assertNull(s.annotations.value.single().note)
        assertTrue(s.annotations.value.single().updatedAt > a.updatedAt)
        s.deleteAnnotation(a.id)
        assertTrue(s.annotations.value.isEmpty())
    }

    @Test
    fun `bookmarks add and delete`() = runBlocking {
        val s = store(tempDir())
        val b = s.addBookmark(VerseRef("ot", "gen", 1, 1))
        s.addBookmark(VerseRef("ot", "gen", 1, 1)) // more than one is allowed
        assertEquals(2, s.bookmarks.value.size)
        s.deleteBookmark(b.id)
        assertEquals(1, s.bookmarks.value.size)
    }

    @Test
    fun `an unreadable file is set aside, not overwritten`() = runBlocking {
        val dir = tempDir()
        File(dir, "annotations.json").writeText("{not json")
        val s = store(dir)
        s.load()
        assertTrue(s.annotations.value.isEmpty())
        s.addNote(alma32, r(1, 0, 1, 0), "q", "n")
        val kept = dir.listFiles()!!.single { it.name.startsWith("annotations.json.unreadable-") }
        assertEquals("{not json", kept.readText())
    }

    @Test
    fun `missing footnote asset means no footnotes`() {
        val source = AssetFootnotes { throw FileNotFoundException(it) }
        assertEquals(BookFootnotes.EMPTY, source.book("bofm", "alma"))
        val fixture = File("src/test/resources/footnotes/bofm/alma.json").readBytes()
        val withData = AssetFootnotes { path -> if (path == "footnotes/bofm/alma.json") fixture else throw FileNotFoundException(path) }
        assertEquals(setOf(21), withData.book("bofm", "alma").chapter(32).keys)
        assertTrue(withData.book("bofm", "ether").verses.isEmpty())
    }
}
