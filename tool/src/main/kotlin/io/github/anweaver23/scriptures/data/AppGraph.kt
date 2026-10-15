package io.github.anweaver23.scriptures.data

import android.util.Log
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.toolmanager.LightFileProvider
import io.github.anweaver23.scriptures.core.BookText
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Dictionary
import io.github.anweaver23.scriptures.core.NoDictionary
import io.github.anweaver23.scriptures.core.NoOriginalLanguage
import io.github.anweaver23.scriptures.core.OriginalLanguage
import io.github.anweaver23.scriptures.core.FileMatcher
import io.github.anweaver23.scriptures.core.FootnoteSource
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons. Screens call [init] with their context; the Tool
 * Manager callback only gets to import files once a screen has done that.
 */
object AppGraph {
    @Volatile
    private var context: SealedLightContext? = null

    /** Keeps the newest screen's context, so a destroyed activity isn't held onto. */
    fun init(lightContext: SealedLightContext) {
        context = lightContext
    }

    val isReady: Boolean get() = context != null

    private fun ctx(): SealedLightContext = checkNotNull(context) { "AppGraph.init was not called" }

    val catalog: Catalog by lazy {
        val c = ctx()
        Catalog(
            scriptures = Catalog.parseIndex(c.readAsset("scriptures/index.json").decodeToString()),
            hymnBooks = Catalog.HYMN_BOOK_FILES.mapNotNull { name ->
                runCatching { Catalog.parseHymnBook(c.readAsset("hymns/$name").decodeToString()) }.getOrNull()
            },
        )
    }

    val library: AudioLibrary by lazy { AudioLibrary(ctx().filesDir) }

    val importer: Importer by lazy {
        // Tool Manager uploads land in the same shared directory LightFileShare uses.
        Importer(File(ctx().filesDir, LightFileProvider.SHARED_DIR), FileMatcher(catalog), library, MediaTagReader)
    }

    val progress: ProgressStore by lazy { ProgressStore(ctx().dataStore) }

    /** Highlights, notes and bookmarks, as JSON in the private files dir. */
    val study: StudyStore by lazy { StudyStore(File(ctx().filesDir, StudyStore.DIR)) }

    /** Footnotes from assets/footnotes. None are bundled yet, so every book reads as empty. */
    val footnotes: FootnoteSource by lazy { AssetFootnotes { path -> ctx().readAsset(path) } }

    /** Webster's 1828. Placeholder until the bundled dictionary is wired in. */
    val dictionary: Dictionary by lazy { NoDictionary }

    /** Strong's Hebrew/Greek behind the KJV text. Placeholder until the bundled data is wired in. */
    val originalLanguage: OriginalLanguage by lazy { NoOriginalLanguage }

    /** For work that should finish even if the screen that started it closes (imports, file moves). */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background work failed", e) },
    )

    private const val TAG = "Scriptures"

    private val bookCache = object : LinkedHashMap<String, BookText>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookText>?) = size > 4
    }

    /** Verse text for one book, read from assets on first use. */
    fun bookText(volumeId: String, bookId: String): BookText = synchronized(bookCache) {
        bookCache.getOrPut("$volumeId/$bookId") {
            Catalog.parseBook(ctx().readAsset("scriptures/$volumeId/$bookId.json").decodeToString())
        }
    }
}
