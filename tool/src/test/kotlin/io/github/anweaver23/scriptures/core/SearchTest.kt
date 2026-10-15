package io.github.anweaver23.scriptures.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTest {
    private fun matches(verse: String, query: String) = Search.match(verse, Search.parse(query)) != null

    @Test
    fun `parse folds case and apostrophes and drops redundant prefixes`() {
        assertEquals(listOf("faith", "hope"), Search.parse("  Faith, HOPE faith "))
        assertEquals(listOf("man's"), Search.parse("Man’s"))
        assertEquals(listOf("faithful"), Search.parse("fait faithful"))
        assertEquals(emptyList(), Search.parse(" , — "))
    }

    @Test
    fun `matches whole-word prefixes only`() {
        val verse = "And now as I said concerning faith—faith is not to have a perfect knowledge"
        assertTrue(matches(verse, "fait"))
        assertTrue(matches(verse, "FAITH"))
        assertTrue(matches(verse, "knowledge perfect"))
        assertTrue(!matches(verse, "aith"))
        assertTrue(!matches(verse, "faithful"))
        assertTrue(!matches(verse, "owledge"))
    }

    @Test
    fun `every word must appear`() {
        val verse = "And charity suffereth long, and is kind"
        assertTrue(matches(verse, "charity kind"))
        assertTrue(!matches(verse, "charity hope"))
    }

    @Test
    fun `curly and straight apostrophes are interchangeable`() {
        assertTrue(matches("the man's house", "man’s"))
        assertTrue(matches("the man’s house", "man's"))
        assertTrue(matches("the LORD’s house", "lord's"))
        assertTrue(!matches("the man's house", "mans"))
    }

    @Test
    fun `hyphenated words match on their first part`() {
        assertTrue(matches("and called the name of that place Beth-el", "beth"))
        assertTrue(matches("and called the name of that place Beth-el", "beth-el"))
        assertTrue(!matches("and called the name of that place Beth-el", "el"))
    }

    @Test
    fun `match range is the earliest matched word`() {
        val verse = "Ask, and it shall be given you; seek, and ye shall find"
        val range = assertNotNull(Search.match(verse, Search.parse("seek ask")))
        assertEquals("Ask", verse.substring(range.first, range.last + 1))
        assertNull(Search.match(verse, emptyList()))
    }

    @Test
    fun `snippet keeps some lead-in and trims long verses`() {
        val verse = "And now as I said concerning faith—faith is not to have a perfect knowledge of things; " +
            "therefore if ye have faith ye hope for things which are not seen, which are true."
        val start = verse.indexOf("perfect")
        val s = Search.snippet(verse, start, before = 15, maxChars = 50)
        assertTrue(s.startsWith("…"), s)
        assertTrue(s.endsWith("…"), s)
        assertTrue("perfect knowledge" in s, s)
        assertTrue(s.length <= 52, s)
        assertEquals("Ask, and it shall be given you", Search.snippet("Ask, and it shall be given you", 0))
    }

    // --- Against the real bundled text. Unit tests run with the module directory as the working directory. ---

    private val catalog = Catalog(
        Catalog.parseIndex(File("src/main/assets/scriptures/index.json").readText()),
        emptyList(),
    )

    private val corpus: List<SearchBook> by lazy {
        catalog.scriptures.volumes.flatMap { v ->
            v.books.map { b ->
                SearchBook(v.id, b.id, Catalog.parseBook(File("src/main/assets/scriptures/${v.id}/${b.id}.json").readText()))
            }
        }
    }

    @Test
    fun `labels read like citations`() {
        assertEquals("Alma 32:21", catalog.verseLabel(VerseRef("bofm", "alma", 32, 21)))
        assertEquals("D&C 4:2", catalog.verseLabel(VerseRef("dc-testament", "dc", 4, 2)))
        assertEquals("Enos 1:3", catalog.verseLabel(VerseRef("bofm", "enos", 1, 3)))
    }

    @Test
    fun `finds known verses in canonical order`() {
        val r = Search.search(corpus, "perfect knowledge faith")
        val labels = r.hits.map { catalog.verseLabel(it.ref) }
        assertTrue("Alma 32:21" in labels, labels.toString())

        val charity = Search.search(corpus, "charity suffereth")
        val charityLabels = charity.hits.map { catalog.verseLabel(it.ref) }
        assertEquals(listOf("1 Corinthians 13:4", "Moroni 7:45"), charityLabels)
        assertEquals(charity.total, charity.hits.size)
    }

    @Test
    fun `caps hits but counts everything`() {
        val r = Search.search(corpus, "the", limit = 200)
        assertEquals(200, r.hits.size)
        assertTrue(r.total > 20_000, "total ${r.total}")
        assertEquals(r.total - 200, r.more)
        assertEquals(VerseRef("ot", "gen", 1, 1), r.hits.first().ref)
    }

    @Test
    fun `reports scan time`() {
        val loadStart = System.nanoTime()
        val books = corpus
        val verses = books.sumOf { b -> b.text.c.sumOf { it.size } }
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        // Warm up, then time a few typical queries over the whole corpus.
        repeat(3) { Search.search(books, "faith") }
        for (q in listOf("faith", "the", "charity suffereth", "zarahemla", "xyzzy", "man's heart")) {
            val t = System.nanoTime()
            val r = Search.search(books, q)
            println("search '$q': ${r.total} hits in ${(System.nanoTime() - t) / 1_000_000} ms")
        }
        println("corpus: $verses verses in ${books.size} books, parsed in $loadMs ms")
        assertTrue(verses > 40_000)
    }
}
