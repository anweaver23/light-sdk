package io.github.anweaver23.scriptures.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkTargetTest {
    @Test
    fun `a verse link is that verse alone`() {
        val (first, last) = LinkTarget.verses("bofm/alma/32/5")!!
        assertEquals(VerseRef("bofm", "alma", 32, 5), first)
        assertEquals(5, last)
    }

    @Test
    fun `a range key covers its verses, dashes in ids aside`() {
        val (first, last) = LinkTarget.verses("dc-testament/dc/76/20-24")!!
        assertEquals(VerseRef("dc-testament", "dc", 76, 20), first)
        assertEquals(24, last)
        assertNull(LinkTarget.verses("dc-testament/dc/76"))
    }

    @Test
    fun `range tails are read only within the chapter`() {
        assertEquals(6 to 2, LinkTarget.rangeTail("–6; TG Faith.", 5))
        assertEquals(12 to 3, LinkTarget.rangeTail("-12", 5))
        assertNull(LinkTarget.rangeTail("–33:2", 5))
        assertNull(LinkTarget.rangeTail("; 7", 5))
        assertNull(LinkTarget.rangeTail("– see", 5))
        assertNull(LinkTarget.rangeTail("–4", 5))
    }
}
