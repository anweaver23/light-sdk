package io.github.anweaver23.scriptures.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackStackTest {
    @Test
    fun `pops to the newest matching screen`() {
        val keys = listOf(null, "bookmarks", "alma 32", "bookmarks", "alma 5")
        assertEquals(1, BackStack.popsTo(keys, "bookmarks"))
        assertEquals(2, BackStack.popsTo(keys, "alma 32"))
        assertEquals(0, BackStack.popsTo(keys, "alma 5"))
        assertNull(BackStack.popsTo(keys, "settings"))
    }
}
