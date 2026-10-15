package io.github.anweaver23.scriptures.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StrongsDataTest {
    @Test
    fun `tags are looked up by word index and repeat for several numbers`() {
        val tags = StrongsData.parseTags("""{"tok":1,"c":[[[0,10,2,20,2,21],[]],[[5,7]]]}""", 'G')
        assertEquals(listOf("G10"), tags.numbersAt(1, 1, 0))
        assertEquals(emptyList(), tags.numbersAt(1, 1, 1))
        assertEquals(listOf("G20", "G21"), tags.numbersAt(1, 1, 2))
        assertEquals(emptyList(), tags.numbersAt(1, 2, 0))
        assertEquals(listOf("G7"), tags.numbersAt(2, 1, 5))
        assertEquals(emptyList(), tags.numbersAt(3, 1, 0))
        assertEquals(emptyList(), tags.numbersAt(0, 0, 0))
    }

    @Test
    fun `shard entries become original words`() {
        val shard = StrongsData.parseShard(
            """{"7225":{"l":"רֵאשִׁית","x":"rêʼshîyth","p":"ray-sheeth'","d":"the first","r":"from the same as H7218","k":"beginning"},"7226":{"l":"x","x":"y"}}""",
            'H',
        )
        val w = shard.getValue(7225)
        assertEquals("H7225", w.strongs)
        assertEquals("from the same as H7218", w.derivation)
        assertEquals("", shard.getValue(7226).definition)
        assertNull(shard.getValue(7226).kjvUsage)
    }

    @Test
    fun `numbers parse with padding case and suffix`() {
        assertEquals('H' to 7225, StrongsData.parseNumber("H07225"))
        assertEquals('G' to 3056, StrongsData.parseNumber("g3056a"))
        assertNull(StrongsData.parseNumber("H0"))
        assertNull(StrongsData.parseNumber("7225"))
        assertNull(StrongsData.parseNumber("H7225 G1"))
    }

    @Test
    fun `paths and prefixes`() {
        assertEquals("strongs/dict/H/72.json", StrongsData.shardPath('H', 7225))
        assertEquals("strongs/dict/G/0.json", StrongsData.shardPath('G', 1))
        assertEquals("strongs/nt/john.json", StrongsData.tagPath("nt", "john"))
        assertEquals('H', StrongsData.prefixFor("ot"))
        assertNull(StrongsData.prefixFor("bofm"))
    }

    @Test
    fun `token checksum matches the generator's CRC`() {
        // zlib.crc32("In the beginning\nJesus wept".encode()) in Python.
        assertEquals(
            java.util.zip.CRC32().apply { update("In the beginning\nJesus wept".toByteArray()) }.value,
            StrongsData.tokenChecksum(sequenceOf("In the beginning.", "Jesus wept.")),
        )
    }
}
