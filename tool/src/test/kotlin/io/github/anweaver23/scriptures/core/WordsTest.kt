package io.github.anweaver23.scriptures.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WordsTest {
    private fun words(verse: String) = Words.spans(verse).map { it.text(verse) }

    @Test
    fun `splits on spaces punctuation and em dashes`() {
        assertEquals(
            listOf("Behold", "it", "came", "to", "pass", "that", "I", "Enos", "knowing", "my", "father"),
            words("Behold, it came to pass that I, Enos, knowing my father—"),
        )
    }

    @Test
    fun `keeps apostrophes and hyphens between letters`() {
        assertEquals(listOf("man's", "Beth-el", "LORD’s"), words("man's Beth-el LORD’s"))
    }

    @Test
    fun `trailing or doubled joiners are separators`() {
        assertEquals(listOf("brethren", "saints"), words("brethren' -saints-"))
    }

    @Test
    fun `indexes and offsets line up`() {
        val verse = "In the beginning"
        val spans = Words.spans(verse)
        assertEquals(listOf(0, 1, 2), spans.map { it.index })
        assertEquals(WordSpan(2, 7, 16), spans[2])
    }
}
