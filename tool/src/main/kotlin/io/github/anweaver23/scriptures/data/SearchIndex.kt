package io.github.anweaver23.scriptures.data

import io.github.anweaver23.scriptures.core.Search
import io.github.anweaver23.scriptures.core.SearchBook
import io.github.anweaver23.scriptures.core.PageResults
import io.github.anweaver23.scriptures.core.SearchPage
import io.github.anweaver23.scriptures.core.SearchResults
import io.github.anweaver23.scriptures.core.importer.TextBuild
import io.github.anweaver23.scriptures.core.plain
import java.lang.ref.SoftReference

/**
 * All bundled verse text, loaded on the first search and kept in memory for later ones.
 *
 * It's held through a [SoftReference], so the system can reclaim it under memory pressure; the
 * next search just reloads it. Loading parses every book once (about 41k verses).
 * Call from a background thread.
 */
object SearchIndex {
    @Volatile
    private var cached: SoftReference<List<SearchBook>>? = null

    /** Drops the loaded text, after an import replaced it. */
    fun clear() {
        cached = null
        pagesCached = null
    }

    @Volatile
    private var pagesCached: SoftReference<List<SearchPage>>? = null

    /** Every imported study-help page's text (Topical Guide, Bible Dictionary, …), read once and kept. */
    fun pages(): List<SearchPage> {
        pagesCached?.get()?.let { return it }
        synchronized(this) {
            pagesCached?.get()?.let { return it }
            val text = AppGraph.text
            val groups = text.pageIndex().groups
            val loaded = TextBuild.HELPS.keys.flatMap { group ->
                groups[group].orEmpty().mapNotNull { entry ->
                    val page = text.pageUncached(entry.id) ?: return@mapNotNull null
                    SearchPage(entry.id, group, entry.title, page.blocks.map { it.spans.plain() })
                }
            }
            pagesCached = SoftReference(loaded)
            return loaded
        }
    }

    fun searchPages(query: String, checkCancelled: () -> Unit = {}): PageResults =
        Search.searchPages(pages(), query, checkCancelled = checkCancelled)

    fun books(): List<SearchBook> {
        cached?.get()?.let { return it }
        synchronized(this) {
            cached?.get()?.let { return it }
            val scriptures = AppGraph.catalog.scriptures
            val loaded = scriptures.volumes.flatMap { volume ->
                volume.books.map { book -> SearchBook(volume.id, book.id, AppGraph.bookText(volume.id, book.id)) }
            }
            cached = SoftReference(loaded)
            return loaded
        }
    }

    fun search(query: String, limit: Int = Search.DEFAULT_LIMIT, checkCancelled: () -> Unit = {}): SearchResults =
        Search.search(books(), query, limit, checkCancelled)
}
