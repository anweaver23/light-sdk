package io.github.anweaver23.scriptures.data

import android.util.Log
import io.github.anweaver23.scriptures.core.BookFootnotes
import io.github.anweaver23.scriptures.core.FootnoteSource
import java.io.FileNotFoundException

/**
 * Footnotes from `assets/footnotes/<volumeId>/<bookId>.json` (format in [BookFootnotes]).
 * No file means no footnotes, which is the normal case: none are bundled yet.
 */
class AssetFootnotes(private val readAsset: (String) -> ByteArray) : FootnoteSource {
    private val cache = HashMap<String, BookFootnotes>()

    override fun book(volumeId: String, bookId: String): BookFootnotes = synchronized(cache) {
        cache.getOrPut("$volumeId/$bookId") { load(volumeId, bookId) }
    }

    private fun load(volumeId: String, bookId: String): BookFootnotes {
        val path = "footnotes/$volumeId/$bookId.json"
        val bytes = try {
            readAsset(path)
        } catch (e: FileNotFoundException) {
            return BookFootnotes.EMPTY
        }
        return try {
            BookFootnotes.parse(bytes.decodeToString())
        } catch (e: Exception) {
            Log.w("Scriptures", "Ignoring unreadable $path", e)
            BookFootnotes.EMPTY
        }
    }
}
