package io.github.anweaver23.scriptures.data

import io.github.anweaver23.scriptures.core.Definition
import io.github.anweaver23.scriptures.core.Dictionary
import io.github.anweaver23.scriptures.core.WebsterLookup

/**
 * Webster's 1828 from the bundled shards. A lookup reads one small shard file (a few more when it
 * falls back to base forms), never the whole dictionary. Recently used shards are kept in memory.
 *
 * [load] reads an asset by path ("dictionary/ch.txt") and returns null when it's missing, so tests
 * can read the files from disk. Reads block, so call [define] off the main thread.
 */
class WebsterDictionary(
    private val cacheSize: Int = 8,
    private val load: (assetPath: String) -> ByteArray?,
) : Dictionary {

    /** Null when the index can't be read; then every lookup is empty rather than wrong. */
    private val split: Set<String>? by lazy {
        load(WebsterLookup.INDEX_PATH)?.decodeToString()?.let(WebsterLookup::parseSplitIndex)
    }

    private val shards = object : LinkedHashMap<String, WebsterLookup.Shard>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebsterLookup.Shard>?) =
            size > cacheSize
    }

    override fun define(word: String): List<Definition> {
        val split = split ?: return emptyList()
        return WebsterLookup.define(word) { key -> WebsterLookup.entriesFor(key, split, ::shard) }
    }

    private fun shard(name: String): WebsterLookup.Shard = synchronized(shards) {
        shards.getOrPut(name) {
            // A shard that doesn't exist just has no words in it.
            load(WebsterLookup.shardPath(name))?.decodeToString()?.let(WebsterLookup::parseShard)
                ?: WebsterLookup.Shard.EMPTY
        }
    }
}
