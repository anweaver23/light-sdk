package io.github.anweaver23.scriptures.data

import io.github.anweaver23.scriptures.core.OriginalLanguage
import io.github.anweaver23.scriptures.core.OriginalWord
import io.github.anweaver23.scriptures.core.StrongsBookTags
import io.github.anweaver23.scriptures.core.StrongsData
import io.github.anweaver23.scriptures.core.VerseRef

/**
 * Strong's Hebrew/Greek behind the bundled OT/NT text, read lazily from assets/strongs.
 *
 * A lookup loads one book's tag file and one dictionary shard (100 entries), never the whole
 * dataset; both are kept in small LRU caches. [load] returns an asset's bytes, or null if it
 * doesn't exist, so this runs unchanged on the JVM in tests. Volumes other than "ot" and "nt"
 * have no data and always return empty.
 */
class StrongsOriginalLanguage(
    private val load: (assetPath: String) -> ByteArray?,
    maxBooks: Int = 4,
    maxShards: Int = 16,
) : OriginalLanguage {
    // A missing or unreadable file is cached as null too, so it isn't re-read on every tap.
    private val books = Lru<String, StrongsBookTags?>(maxBooks)
    private val shards = Lru<String, Map<Int, OriginalWord>?>(maxShards)

    override fun wordsAt(ref: VerseRef, wordIndex: Int): List<OriginalWord> {
        if (StrongsData.prefixFor(ref.volumeId) == null) return emptyList()
        val tags = book(ref.volumeId, ref.bookId) ?: return emptyList()
        return tags.numbersAt(ref.chapter, ref.verse, wordIndex).mapNotNull(::entry)
    }

    override fun entry(strongs: String): OriginalWord? {
        val (prefix, number) = StrongsData.parseNumber(strongs) ?: return null
        val path = StrongsData.shardPath(prefix, number)
        val shard = shards.getOrPut(path) {
            read(path) { StrongsData.parseShard(it, prefix) }
        }
        return shard?.get(number)
    }

    private fun book(volumeId: String, bookId: String): StrongsBookTags? {
        val prefix = StrongsData.prefixFor(volumeId) ?: return null
        val path = StrongsData.tagPath(volumeId, bookId)
        return books.getOrPut(path) { read(path) { StrongsData.parseTags(it, prefix) } }
    }

    // Reference data degrades to "no original word" rather than crashing the reader; the unit
    // tests parse every bundled file, so a bad asset is caught there, not on the phone.
    private fun <T> read(path: String, parse: (String) -> T): T? =
        runCatching { load(path)?.let { parse(it.decodeToString()) } }.getOrNull()

    private class Lru<K, V>(private val max: Int) {
        private val map = object : LinkedHashMap<K, V>(max * 2, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
        }

        fun getOrPut(key: K, create: () -> V): V = synchronized(map) {
            if (map.containsKey(key)) {
                @Suppress("UNCHECKED_CAST")
                map[key] as V
            } else {
                create().also { map[key] = it }
            }
        }
    }
}
