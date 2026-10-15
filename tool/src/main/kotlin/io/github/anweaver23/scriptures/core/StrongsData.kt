package io.github.anweaver23.scriptures.core

import java.util.zip.CRC32
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Shapes and parsing for the bundled Strong's assets under assets/strongs (built by
// tool/scripts/build_strongs_assets.py; see assets/strongs/SOURCE.md). No Android here.

/**
 * Strong's tags for one book, aligned to OUR verse text: word indexes are [Words.spans] indexes.
 * Each verse is a flat `[wordIndex, number, wordIndex, number, ...]` array; a word carrying
 * several numbers repeats its index. Numbers are stored without their prefix, which is implied
 * by the volume ([prefix]: 'H' for the Old Testament, 'G' for the New).
 */
class StrongsBookTags(
    val prefix: Char,
    /** CRC-32 of the book's tokens as the generator saw them; see [StrongsData.tokenChecksum]. */
    val tokenChecksum: Long,
    private val chapters: List<List<IntArray>>,
) {
    /** Strong's numbers ("H7225") on [wordIndex] of [chapter]:[verse], in source order. */
    fun numbersAt(chapter: Int, verse: Int, wordIndex: Int): List<String> {
        val flat = chapters.getOrNull(chapter - 1)?.getOrNull(verse - 1) ?: return emptyList()
        var out: MutableList<String>? = null
        var i = 0
        while (i + 1 < flat.size) {
            if (flat[i] == wordIndex) {
                (out ?: ArrayList<String>(2).also { out = it }) += "$prefix${flat[i + 1]}"
            }
            i += 2
        }
        return out ?: emptyList()
    }
}

/** One dictionary entry as stored in a shard. Field names are kept short on disk. */
@Serializable
internal data class StrongsEntryJson(
    val l: String = "",
    val x: String = "",
    val p: String? = null,
    val d: String? = null,
    val r: String? = null,
    val k: String? = null,
)

@Serializable
internal data class StrongsTagFileJson(val tok: Long, val c: List<List<List<Int>>>)

object StrongsData {
    private val json = Json { ignoreUnknownKeys = true }

    /** Dictionary entries per shard file: shard `n / SHARD_SIZE` holds numbers in that hundred. */
    const val SHARD_SIZE = 100

    /** Only the Bible volumes have original-language data. */
    fun prefixFor(volumeId: String): Char? = when (volumeId) {
        "ot" -> 'H'
        "nt" -> 'G'
        else -> null
    }

    fun tagPath(volumeId: String, bookId: String) = "strongs/$volumeId/$bookId.json"

    fun shardPath(prefix: Char, number: Int) = "strongs/dict/$prefix/${number / SHARD_SIZE}.json"

    private val NUMBER = Regex("""^\s*([HGhg])0*(\d{1,5})[a-zA-Z]?\s*$""")

    /** Accepts "H7225", "h07225" or "G3056a" and returns ('H', 7225); null when it isn't one. */
    fun parseNumber(strongs: String): Pair<Char, Int>? {
        val m = NUMBER.matchEntire(strongs) ?: return null
        val n = m.groupValues[2].toInt()
        if (n == 0) return null
        return m.groupValues[1].uppercase()[0] to n
    }

    fun parseTags(text: String, prefix: Char): StrongsBookTags {
        val file = json.decodeFromString<StrongsTagFileJson>(text)
        return StrongsBookTags(prefix, file.tok, file.c.map { ch -> ch.map { it.toIntArray() } })
    }

    /** A dictionary shard, keyed by number without prefix. */
    fun parseShard(text: String, prefix: Char): Map<Int, OriginalWord> =
        json.decodeFromString<Map<String, StrongsEntryJson>>(text).entries.associate { (key, e) ->
            val n = key.toInt()
            n to OriginalWord(
                strongs = "$prefix$n",
                lemma = e.l,
                transliteration = e.x,
                pronunciation = e.p,
                definition = e.d.orEmpty(),
                derivation = e.r,
                kjvUsage = e.k,
            )
        }

    /**
     * CRC-32 over every token of a book: tokens of a verse joined by " ", verses by "\n", UTF-8.
     * The generator stores the same value in each tag file, so comparing the two proves that the
     * Python port of [Words.spans] numbered every word exactly as Kotlin does.
     */
    fun tokenChecksum(verses: Sequence<String>): Long {
        val crc = CRC32()
        var first = true
        for (verse in verses) {
            if (!first) crc.update('\n'.code)
            first = false
            val line = Words.spans(verse).joinToString(" ") { it.text(verse) }
            crc.update(line.toByteArray(Charsets.UTF_8))
        }
        return crc.value
    }
}
