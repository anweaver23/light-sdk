package io.github.anweaver23.scriptures.data

import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.StrongsData
import io.github.anweaver23.scriptures.core.VerseRef
import io.github.anweaver23.scriptures.core.Words
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs against the real bundled assets, the same files the phone reads. */
class StrongsOriginalLanguageTest {
    private val assets = File("src/main/assets")
    private val loads = mutableListOf<String>()
    private val strongs = StrongsOriginalLanguage({ path ->
        loads += path
        File(assets, path).takeIf { it.isFile }?.readBytes()
    })

    private fun verse(ref: VerseRef): String =
        Catalog.parseBook(File(assets, "scriptures/${ref.volumeId}/${ref.bookId}.json").readText())
            .c[ref.chapter - 1][ref.verse - 1]

    /** Word indexes of every occurrence of [word] in the verse, as numbered by Words.spans. */
    private fun indexesOf(ref: VerseRef, word: String): List<Int> {
        val text = verse(ref)
        return Words.spans(text).filter { it.text(text) == word }.map { it.index }.also {
            assertTrue(it.isNotEmpty(), "\"$word\" not in ${ref.key}: $text")
        }
    }

    private fun numbers(ref: VerseRef, word: String): List<List<String>> =
        indexesOf(ref, word).map { i -> strongs.wordsAt(ref, i).map { it.strongs } }

    @Test
    fun `Genesis 1 1`() {
        val gen = VerseRef("ot", "gen", 1, 1)
        assertEquals(listOf(listOf("H7225")), numbers(gen, "beginning"))
        assertEquals(listOf(listOf("H430")), numbers(gen, "God"))
        assertEquals(listOf(listOf("H1254")), numbers(gen, "created"))
        assertEquals(listOf(emptyList()), numbers(gen, "In"))

        val beginning = strongs.wordsAt(gen, indexesOf(gen, "beginning").single()).single()
        assertEquals("רֵאשִׁית", beginning.lemma)
        assertEquals("rêʼshîyth", beginning.transliteration)
        assertEquals("ray-sheeth'", beginning.pronunciation)
        assertTrue(beginning.definition.startsWith("the first"), beginning.definition)
        assertTrue(beginning.kjvUsage!!.contains("beginning"))
    }

    @Test
    fun `John 1 1 Word is logos every time`() {
        val john = VerseRef("nt", "john", 1, 1)
        assertEquals(List(3) { listOf("G3056") }, numbers(john, "Word"))
        assertEquals("λόγος", strongs.entry("G3056")!!.lemma)
    }

    @Test
    fun `John 11 35 wept`() {
        val ref = VerseRef("nt", "john", 11, 35)
        assertEquals(listOf(listOf("G1145")), numbers(ref, "wept"))
        assertEquals(listOf(listOf("G2424")), numbers(ref, "Jesus"))
    }

    @Test
    fun `one word can carry two numbers`() {
        // The tagged source splits "can not" across two Greek words; our text reads "cannot".
        assertEquals(listOf(listOf("G1410", "G3756")), numbers(VerseRef("nt", "acts", 4, 20), "cannot"))
    }

    @Test
    fun `derivation links resolve`() {
        val logos = strongs.entry("G3056")!!
        assertEquals("from G3004", logos.derivation)
        assertNotNull(strongs.entry("G3004"))

        val reshith = strongs.entry("H7225")!!
        val root = Regex("""H\d+""").find(reshith.derivation!!)!!.value
        assertEquals("H7218", root)
        assertEquals("רֹאשׁ", strongs.entry(root)!!.lemma)
    }

    @Test
    fun `entry accepts padded and lowercase numbers and rejects junk`() {
        assertEquals("H7225", strongs.entry("h07225")?.strongs)
        assertNull(strongs.entry("H0"))
        assertNull(strongs.entry("X12"))
        assertNull(strongs.entry("H99999"))
    }

    @Test
    fun `non Bible volumes have no original words and load nothing`() {
        assertEquals(emptyList(), strongs.wordsAt(VerseRef("bofm", "1-ne", 1, 1), 0))
        assertEquals(emptyList(), strongs.wordsAt(VerseRef("dc-testament", "dc", 1, 1), 3))
        assertEquals(emptyList(), loads)
    }

    @Test
    fun `out of range references are empty`() {
        assertEquals(emptyList(), strongs.wordsAt(VerseRef("ot", "gen", 99, 1), 0))
        assertEquals(emptyList(), strongs.wordsAt(VerseRef("ot", "gen", 1, 1), 500))
        assertEquals(emptyList(), strongs.wordsAt(VerseRef("ot", "no-such-book", 1, 1), 0))
    }

    @Test
    fun `a lookup reads one book and one shard`() {
        strongs.wordsAt(VerseRef("ot", "gen", 1, 1), 2)
        strongs.wordsAt(VerseRef("ot", "gen", 1, 2), 2)
        assertEquals(listOf("strongs/ot/gen.json", "strongs/dict/H/72.json"), loads.take(2))
        assertEquals(1, loads.count { it == "strongs/ot/gen.json" })
    }

    /**
     * Every tag file was aligned with a Python port of Words.spans. This proves the port numbered
     * every word of every OT/NT verse exactly as Kotlin does, so no tag sits on the wrong word.
     */
    @Test
    fun `generator tokenized every verse exactly like Words spans`() {
        val index = Catalog.parseIndex(File(assets, "scriptures/index.json").readText())
        var books = 0
        for (volumeId in listOf("ot", "nt")) {
            val prefix = StrongsData.prefixFor(volumeId)!!
            for (book in index.volume(volumeId)!!.books) {
                val text = Catalog.parseBook(File(assets, "scriptures/$volumeId/${book.id}.json").readText())
                val tags = StrongsData.parseTags(File(assets, StrongsData.tagPath(volumeId, book.id)).readText(), prefix)
                assertEquals(tags.tokenChecksum, StrongsData.tokenChecksum(text.c.asSequence().flatten()), book.id)
                books++
            }
        }
        assertEquals(66, books)
    }

    @Test
    fun `every dictionary shard parses`() {
        var entries = 0
        for (prefix in listOf('H', 'G')) {
            File(assets, "strongs/dict/$prefix").listFiles()!!.forEach { f ->
                val shard = StrongsData.parseShard(f.readText(), prefix)
                shard.keys.forEach { n -> assertEquals(f.nameWithoutExtension.toInt(), n / StrongsData.SHARD_SIZE) }
                entries += shard.size
            }
        }
        assertEquals(8674 + 5624, entries)
    }
}
