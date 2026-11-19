package io.github.anweaver23.scriptures.data

import android.util.Log
import io.github.anweaver23.scriptures.core.BookFootnotes
import io.github.anweaver23.scriptures.core.BookText
import io.github.anweaver23.scriptures.core.FootnoteSource
import io.github.anweaver23.scriptures.core.FrontPage
import io.github.anweaver23.scriptures.core.ScriptureIndex
import io.github.anweaver23.scriptures.core.importer.PageIndex
import io.github.anweaver23.scriptures.core.importer.TextBuild
import io.github.anweaver23.scriptures.core.importer.TextManifest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "ScripturesText"

/**
 * The scripture text, built from the Church's downloads the user drops into the Tool Manager's
 * "Standard works" folder ([inbox]): the Victor Reader zip and the scripture EPUBs (see [TextBuild]).
 *
 * Dropped files are moved into [sources] (kept, so adding the next download rebuilds from all of them),
 * built into a fresh folder, and swapped in for [root] only when the build finished, so a failed or
 * interrupted import never leaves half a library. Everything here is in the tool's private files.
 */
class TextLibrary(filesDir: File, private val inbox: File, private val index: ScriptureIndex) {
    private val base = File(filesDir, "library")
    private val root = File(base, "text")
    private val sources = File(base, "text-sources")
    private val mutex = Mutex()
    private var upgradeTried = false

    private val _manifest = MutableStateFlow(readManifest())
    /** What's imported; empty until the first import. */
    val manifest: StateFlow<TextManifest> = _manifest.asStateFlow()

    private val _step = MutableStateFlow<String?>(null)
    /** What the import is reading right now, or null when idle. */
    val step: StateFlow<String?> = _step.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val hasText: Boolean get() = _manifest.value.books.isNotEmpty()

    /** The Church files the text was built from. */
    fun sourceNames(): List<String> = sources.listFiles()?.map { it.name }?.sorted().orEmpty()

    /**
     * Takes any new files from the inbox and rebuilds; also finishes a build that was interrupted, and
     * rebuilds one made by an older importer ([TextBuild.FORMAT]).
     * Returns true when it rebuilt. Safe to call often: otherwise it does nothing.
     */
    suspend fun importInbox(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            inbox.mkdirs()
            val dropped = inbox.walkTopDown().filter { it.isFile && accepted(it.name) }.toList()
            // A build that never finished (the app was closed or crashed part way) runs again.
            val interrupted = File(base, "text.new").exists() && !sourceNames().isEmpty()
            // An import made by an older importer is rebuilt from its kept sources (tried once per run,
            // so a failing source doesn't rebuild on every visit home).
            val stale = !upgradeTried && _manifest.value.format < TextBuild.FORMAT && sourceNames().isNotEmpty()
            if (stale) upgradeTried = true
            if (dropped.isEmpty() && !interrupted && !stale) return@withContext false
            sources.mkdirs()
            for (f in dropped) {
                val target = File(sources, f.name)
                target.delete()
                if (!f.renameTo(target)) {
                    f.copyTo(target, overwrite = true)
                    f.delete()
                }
            }
            inbox.walkBottomUp().filter { it != inbox && it.isDirectory && it.list().isNullOrEmpty() }.forEach { it.delete() }
            rebuild()
            true
        }
    }

    private fun rebuild() {
        val fresh = File(base, "text.new").apply { deleteRecursively(); mkdirs() }
        _error.value = null
        try {
            val manifest = TextBuild(index).build(
                sources = sources.listFiles()?.sortedBy { it.name }.orEmpty(),
                sink = { path, text -> File(fresh, path).apply { parentFile?.mkdirs() }.writeText(text) },
                progress = { _step.value = it },
            )
            val old = File(base, "text.old").apply { deleteRecursively() }
            if (root.exists() && !root.renameTo(old)) root.deleteRecursively()
            check(fresh.renameTo(root)) { "Couldn't move the new text into place" }
            old.deleteRecursively()
            clearCaches()
            _manifest.value = manifest
            manifest.problems.forEach { Log.w(TAG, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            _error.value = e.message ?: e.toString()
            fresh.deleteRecursively()
        } finally {
            _step.value = null
        }
    }

    // --- reading ---

    private val books = cache<BookText>(6)
    private val notes = cache<BookFootnotes>(6)
    private val pages = cache<FrontPage>(24)
    @Volatile private var pageIndex: PageIndex? = null

    fun book(volumeId: String, bookId: String): BookText? = read(books, "books/$volumeId/$bookId.json") {
        TextBuild.json.decodeFromString(BookText.serializer(), it)
    }

    fun notes(volumeId: String, bookId: String): BookFootnotes =
        read(notes, "notes/$volumeId/$bookId.json") { BookFootnotes.parse(it) } ?: BookFootnotes.EMPTY

    /** The imported footnotes, for the reader. */
    val footnotes: FootnoteSource = FootnoteSource { v, b -> notes(v, b) }

    fun page(id: String): FrontPage? = read(pages, "pages/$id.json") {
        TextBuild.json.decodeFromString(FrontPage.serializer(), it)
    }

    /** A page read without keeping it, for scanning every page (search). */
    fun pageUncached(id: String): FrontPage? {
        val f = File(root, "pages/$id.json")
        if (!f.isFile) return null
        return runCatching { TextBuild.json.decodeFromString(FrontPage.serializer(), f.readText()) }.getOrNull()
    }

    fun pageIndex(): PageIndex = pageIndex ?: run {
        val f = File(root, "pages.json")
        (if (f.isFile) runCatching { TextBuild.json.decodeFromString(PageIndex.serializer(), f.readText()) }.getOrNull() else null)
            ?: PageIndex()
    }.also { pageIndex = it }

    private fun <T> read(cache: LinkedHashMap<String, T>, path: String, parse: (String) -> T): T? = synchronized(cache) {
        cache[path]?.let { return it }
        val f = File(root, path)
        if (!f.isFile) return null
        val value = runCatching { parse(f.readText()) }.onFailure { Log.w(TAG, "Unreadable $path", it) }.getOrNull() ?: return null
        cache[path] = value
        value
    }

    private fun clearCaches() {
        synchronized(books) { books.clear() }
        synchronized(notes) { notes.clear() }
        synchronized(pages) { pages.clear() }
        pageIndex = null
        SearchIndex.clear()
    }

    private fun readManifest(): TextManifest {
        val f = File(root, "manifest.json")
        return if (f.isFile) runCatching { TextBuild.json.decodeFromString(TextManifest.serializer(), f.readText()) }.getOrNull() ?: TextManifest() else TextManifest()
    }

    companion object {
        /** The Tool Manager folder the Church's downloads go in. */
        const val FOLDER = "standard-works"
        const val LABEL = "Standard works"

        fun accepted(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".epub") || n.endsWith(".zip") || n.endsWith("_eng.html")
        }

        private fun <T> cache(size: Int) = object : LinkedHashMap<String, T>(size, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = this.size > size
        }
    }
}
