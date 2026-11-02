package io.github.anweaver23.scriptures.data

import io.github.anweaver23.scriptures.core.BookItalics
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.StrongsData
import io.github.anweaver23.scriptures.core.VerseDisplay
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs against the real bundled italics, the same files the phone reads. */
class ItalicsAssetsTest {
    private val assets = File("src/main/assets")

    private fun book(volumeId: String, bookId: String) =
        Catalog.parseBook(File(assets, "scriptures/$volumeId/$bookId.json").readText())

    private fun italics(volumeId: String, bookId: String) =
        BookItalics.parse(File(assets, BookItalics.path(volumeId, bookId)).readText())

    /** The generator numbered every word exactly as Kotlin does, so no italic sits on the wrong word. */
    @Test
    fun `generator tokenized every verse exactly like Words spans`() {
        val index = Catalog.parseIndex(File(assets, "scriptures/index.json").readText())
        var books = 0
        for (volumeId in listOf("ot", "nt")) {
            for (b in index.volume(volumeId)!!.books) {
                val text = book(volumeId, b.id)
                assertEquals(italics(volumeId, b.id).tokenChecksum, StrongsData.tokenChecksum(text.c.asSequence().flatten()), b.id)
                books++
            }
        }
        assertEquals(66, books)
    }

    @Test
    fun `genesis 1 italicizes the supplied words`() {
        val verses = book("ot", "gen").c[0]
        val runs = italics("ot", "gen").chapter(1)
        fun italicWords(verse: Int): String {
            val d = VerseDisplay.build(verse, verses[verse - 1], italics = runs[verse].orEmpty())
            return d.italics.joinToString(" | ") { r -> d.text.substring(d.wordStarts[r.first], d.wordEnds[r.last]) }
        }
        assertEquals("was", italicWords(2))
        assertEquals("it was", italicWords(4))
        assertEquals("", italicWords(1))
    }
}
