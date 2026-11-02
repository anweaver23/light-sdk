package io.github.anweaver23.scriptures.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrontMatterTest {
    @Test
    fun `the Book of Mormon front matter parses and its link resolves`() {
        val front = FrontMatter.parse(File("src/main/assets/scriptures/bofm/front.json").readText())
        assertEquals(
            listOf("Title Page", "Testimony of Three Witnesses", "Testimony of Eight Witnesses", "Testimony of the Prophet Joseph Smith"),
            front.pages.map { it.title },
        )
        assertEquals(VerseRef("pgp", "js-h", 1, 29), front.pages.last().verseRef)
        assertEquals(8, front.pages[2].blocks.count { it.t == "sig" })
        assertTrue(front.pages[0].blocks.any { it.text.startsWith("Wherefore, it is an abridgment") })
    }
}
