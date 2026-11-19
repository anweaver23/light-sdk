package io.github.anweaver23.scriptures.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageSearchTest {
    private val pages = listOf(
        SearchPage("tg/faith-in-god", "tg", "Faith in God", listOf("See also Trust.")),
        SearchPage("tg/faith", "tg", "Faith", listOf("See also Belief.")),
        SearchPage("bd/hope", "bd", "Hope", listOf("Hope is the expectation of good.", "Faith, hope and charity go together.")),
        SearchPage("bd/love", "bd", "Love", listOf("Charity is the pure love of Christ.")),
    )

    @Test
    fun `an exact title leads, then other titles, then text with its block`() {
        val hits = Search.searchPages(pages, "faith").hits
        assertEquals(listOf("tg/faith", "tg/faith-in-god", "bd/hope"), hits.map { it.id })
        assertNull(hits[0].block)
        assertEquals(1, hits[2].block)
    }

    @Test
    fun `every word must be in one block`() {
        assertEquals(listOf("bd/hope"), Search.searchPages(pages, "hope charity").hits.map { it.id })
        assertEquals(0, Search.searchPages(pages, "trust charity").total)
    }

    @Test
    fun `the limit keeps the count`() {
        val r = Search.searchPages(pages, "faith", limit = 1)
        assertEquals(1, r.hits.size)
        assertEquals(2, r.more)
    }

    @Test
    fun `pages read as chapters round-trip their ids`() {
        val chapter = PageRefs.chapter("jst/jst-gen/9")
        assertEquals("jst/jst-gen/9", chapter.pageId)
        assertEquals(chapter, Target.fromKey(chapter.key))
        assertNull(Target.Chapter("bofm", "alma", 32).pageId)
    }
}
