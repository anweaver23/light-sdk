package io.github.anweaver23.scriptures.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadAlongTest {
    // "Chapter 6" read aloud (nothing on the page), a heading, then two verses with a gap between them.
    private val segments = listOf(
        ReadSegment(chars = 20, top = 0f, bottom = 0f),
        ReadSegment(chars = 80, top = 0f, bottom = 100f),
        ReadSegment(chars = 50, top = 130f, bottom = 180f),
        ReadSegment(chars = 50, top = 210f, bottom = 260f),
    )

    @Test
    fun `the spoken lead-in stays at the top`() {
        assertEquals(0f, ReadAlong.y(segments, 0.05f)!!, 0.01f)
    }

    @Test
    fun `characters, not height, decide the place`() {
        // Half way through the heading's characters is half way down the heading.
        assertEquals(50f, ReadAlong.y(segments, 60f / 200)!!, 0.01f)
        // The gap between verses is skipped: the end of verse 1 runs straight into verse 2.
        assertEquals(180f, ReadAlong.y(segments, 150f / 200)!!, 0.01f)
        assertEquals(235f, ReadAlong.y(segments, 175f / 200)!!, 0.01f)
        assertEquals(260f, ReadAlong.y(segments, 1f)!!, 0.01f)
    }

    @Test
    fun `no text, no guess`() {
        assertNull(ReadAlong.y(emptyList(), 0.5f))
        assertNull(ReadAlong.y(listOf(ReadSegment(0, 0f, 10f)), 0.5f))
    }
}
