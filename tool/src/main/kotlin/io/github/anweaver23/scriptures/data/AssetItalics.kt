package io.github.anweaver23.scriptures.data

import android.util.Log
import io.github.anweaver23.scriptures.core.BookItalics
import io.github.anweaver23.scriptures.core.ItalicsSource
import java.io.FileNotFoundException

/** KJV italics from `assets/italics` (format in [BookItalics]). No file means no italics (every book outside the Bible). */
class AssetItalics(private val readAsset: (String) -> ByteArray) : ItalicsSource {
    private val cache = object : LinkedHashMap<String, BookItalics>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookItalics>?) = size > 4
    }

    override fun book(volumeId: String, bookId: String): BookItalics = synchronized(cache) {
        cache.getOrPut("$volumeId/$bookId") { load(volumeId, bookId) }
    }

    private fun load(volumeId: String, bookId: String): BookItalics {
        val path = BookItalics.path(volumeId, bookId)
        val bytes = try {
            readAsset(path)
        } catch (e: FileNotFoundException) {
            return BookItalics.EMPTY
        }
        return try {
            BookItalics.parse(bytes.decodeToString())
        } catch (e: Exception) {
            Log.w("Scriptures", "Ignoring unreadable $path", e)
            BookItalics.EMPTY
        }
    }
}
