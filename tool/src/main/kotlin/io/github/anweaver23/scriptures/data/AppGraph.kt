package io.github.anweaver23.scriptures.data

import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.toolmanager.LightFileProvider
import io.github.anweaver23.scriptures.core.BookText
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.FileMatcher
import java.io.File
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

    fun init(lightContext: SealedLightContext) {
        if (context == null) {
            synchronized(this) {
                if (context == null) context = lightContext
            }
        }
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

    /** For work that should finish even if the screen that started it closes (imports, file moves). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
