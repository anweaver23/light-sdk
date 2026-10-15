package io.github.anweaver23.scriptures.core

import io.github.anweaver23.scriptures.data.WebsterDictionary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebsterLookupTest {
    // Unit tests run with the module directory as the working directory.
    private val reads = ArrayList<String>()
    private val dictionary = WebsterDictionary { path ->
        reads += path
        File("src/main/assets/$path").takeIf { it.isFile }?.readBytes()
    }

    private fun first(word: String): Definition {
        val found = dictionary.define(word)
        assertTrue(found.isNotEmpty(), "no entry for \"$word\"")
        return found.first()
    }

    @Test
    fun definesWordsFromTheRealShards() {
        val charity = first("charity")
        assertEquals("CHARITY", charity.headword)
        assertEquals("n.", charity.partOfSpeech)
        assertEquals(8, charity.senses.size)
        assertTrue(charity.senses[0].startsWith("In a general sense, love, benevolence, good will"))

        val wrestle = first("wrestle")
        assertEquals("v.i.", wrestle.partOfSpeech)
        assertTrue(wrestle.senses.any { "Ephesians 6:12" in it })

        val nurture = dictionary.define("nurture")
        assertEquals(listOf("n.", "v.t."), nurture.map { it.partOfSpeech })

        val admonition = first("admonition")
        assertTrue(admonition.senses.single().startsWith("Gentle reproof"))
    }

    @Test
    fun isCaseInsensitive() {
        val remission = first("Remission")
        assertEquals("REMISSION", remission.headword)
        assertTrue(remission.senses.any { "remission of sins" in it })
        assertEquals(dictionary.define("remission"), dictionary.define("REMISSION"))
    }

    @Test
    fun fallsBackToBaseFormsForArchaicInflections() {
        assertEquals("GIVE", first("giveth").headword)
        assertEquals("v.t.; pret. gave; pp. given", first("giveth").partOfSpeech)
        assertEquals("SAY", first("sayest").headword)
        assertTrue(dictionary.define("spake").any { it.headword == "SPEAK" })
        assertTrue(dictionary.define("hath").any { it.headword == "HAVE" })
        assertTrue(dictionary.define("doth").any { it.headword == "DO" })
        assertTrue(dictionary.define("saith").any { it.headword == "SAY" })
        assertTrue(dictionary.define("wrought").any { it.headword == "WORK" })
        assertEquals("CRY", first("crieth").headword)
        assertEquals("HOPE", first("hopeth").headword)
    }

    @Test
    fun mapsScriptureSpellingsToWebsters() {
        assertEquals("HONOR", first("honour").headword)
        assertEquals("SEPULCHER", first("sepulchre").headword)
        assertEquals("FULLNESS", first("fulness").headword)
        assertEquals("FIRST-BORN", first("firstborn").headword) // closed compound -> alias
        assertEquals("WOLF", first("wolves").headword)
        assertEquals("MIGHT", first("might").headword) // from upstream's words-in-progress
        assertEquals("MEEK", first("meek").headword)
    }

    @Test
    fun handlesApostrophesAndPossessives() {
        assertEquals("MAN", first("man’s").headword)
        assertEquals("FATHER", first("fathers'").headword)
    }

    @Test
    fun unknownWordsAreEmpty() {
        assertTrue(dictionary.define("zarahemla").isEmpty())
        assertTrue(dictionary.define("").isEmpty())
        assertTrue(dictionary.define("—").isEmpty())
    }

    @Test
    fun aLookupReadsOneShardNotTheWholeDictionary() {
        dictionary.define("charity")
        assertEquals(listOf(WebsterLookup.INDEX_PATH, "dictionary/shard-cha.txt"), reads)
        dictionary.define("charity")
        assertEquals(2, reads.size, "second lookup should hit the cache")
    }

    @Test
    fun shardNamesMatchTheGenerator() {
        val split = setOf("ch")
        assertEquals("cha", WebsterLookup.shardFor("charity", split))
        assertEquals("ab", WebsterLookup.shardFor("abide", split))
        assertEquals("a_", WebsterLookup.shardFor("a", split))
        assertEquals("ch_", WebsterLookup.shardFor("ch", split))
        assertEquals("ad", WebsterLookup.shardFor("ad-hoc", split))
        assertEquals("a_", WebsterLookup.shardFor("a-", split))
    }

    @Test
    fun everyShardLineParses() {
        val dir = File("src/main/assets/dictionary")
        val split = WebsterLookup.parseSplitIndex(File(dir, "index.txt").readText())
        val shards = dir.listFiles { f -> f.name.startsWith("shard-") }!!
            .associate { it.nameWithoutExtension.removePrefix("shard-") to WebsterLookup.parseShard(it.readText()) }
        var entries = 0
        for ((name, shard) in shards) {
            for ((key, defs) in shard.entries) {
                assertEquals(name, WebsterLookup.shardFor(key, split), "key $key in wrong shard")
                assertTrue(defs.all { it.senses.isNotEmpty() && it.senses.none(String::isBlank) }, key)
                entries += defs.size
            }
            for ((alias, target) in shard.aliases) {
                assertEquals(name, WebsterLookup.shardFor(alias, split), "alias $alias in wrong shard")
                val targetShard = shards.getValue(WebsterLookup.shardFor(target, split))
                assertTrue(target in targetShard.entries, "alias $alias -> missing $target")
            }
        }
        assertTrue(entries > 70_000, "only $entries entries")
    }

    @Test
    fun normalizes() {
        assertEquals("man", WebsterLookup.normalize("Man’s"))
        assertEquals("lord", WebsterLookup.normalize("LORD"))
        assertEquals("beth-el", WebsterLookup.normalize("Beth-el"))
        assertEquals(listOf("give", "giv"), WebsterLookup.inflectionBases("giveth").take(2))
        assertEquals("walk", WebsterLookup.inflectionBases("walked").first())
        assertEquals("cry", WebsterLookup.inflectionBases("cries").first())
        assertEquals("beg", WebsterLookup.inflectionBases("begging").last())
    }
}
