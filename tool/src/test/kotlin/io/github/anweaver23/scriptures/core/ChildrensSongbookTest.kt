package io.github.anweaver23.scriptures.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs against the bundled Children's Songbook index. */
class ChildrensSongbookTest {
    private val book = Catalog.parseHymnBook(File("src/main/assets/hymns/childrens-songbook.json").readText())
    private val index = Catalog.parseIndex(File("src/main/assets/scriptures/index.json").readText())
    private val matcher = FileMatcher(Catalog(index, listOf(book)))

    @Test
    fun `songs sharing a page keep their printed numbers`() {
        assertEquals(268, book.hymns.size)
        assertEquals(268, book.hymns.map { it.n }.toSet().size)
        assertEquals("A Song of Thanks", book.hymn("20a")?.title)
        assertEquals("I Am a Child of God", book.hymn("2")?.title)
    }

    @Test
    fun `search finds a song by its printed number`() {
        assertEquals(listOf("20b"), Search.searchHymns(listOf(book), "#20b") { null }.map { book.hymn(it.ref.number)!!.number })
    }

    @Test
    fun `the Church's oddly named downloads go to the right recording`() {
        val n = book.hymn("2")!!.n
        assertEquals(
            Target.HymnRef(Catalog.CHILDRENS_SONGBOOK, n, accompaniment = true),
            matcher.match(UploadKind.HymnAudio, "Primary/soy_un_hijo_de_diosmusica_solo.mp3"),
        )
        assertEquals(
            Target.HymnRef(Catalog.CHILDRENS_SONGBOOK, n),
            matcher.match(UploadKind.HymnAudio, "i_am_a_child_of_god_vocal_accompaniment_eng.mp3"),
        )
    }
}
