package io.github.anweaver23.scriptures.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookTileTest {
    @Test
    fun `line breaks at spaces are fine`() {
        // "Doctrine and " / "Covenants": the first line ends after the space.
        assertFalse(breaksMidWord("Doctrine and Covenants", listOf(13, 22)))
        assertFalse(breaksMidWord("Hymns", listOf(5)))
    }

    @Test
    fun `line breaks inside a word are caught`() {
        // "Old Testamen" / "t"
        assertTrue(breaksMidWord("Old Testament", listOf(12, 13)))
        // "Doctrine and Covenant" / "s"
        assertTrue(breaksMidWord("Doctrine and Covenants", listOf(21, 22)))
    }
}
