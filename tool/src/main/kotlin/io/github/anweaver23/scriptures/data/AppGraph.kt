package io.github.anweaver23.scriptures.data

import android.util.Log
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.toolmanager.LightFileProvider
import io.github.anweaver23.scriptures.core.BookText
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Dictionary
import io.github.anweaver23.scriptures.core.PlaybackSettings
import io.github.anweaver23.scriptures.core.OriginalLanguage
import io.github.anweaver23.scriptures.core.FileMatcher
import io.github.anweaver23.scriptures.core.FootnoteSource
import io.github.anweaver23.scriptures.core.DisplaySettings
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

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

    val settings: SettingsStore by lazy { SettingsStore(ctx().dataStore) }

    /** Set once home has reopened the reader where the user left off, so it happens once per launch. */
    @Volatile
    var resumedReading: Boolean = false

    /**
     * The Display settings, shared by every screen. Starts at the defaults until the first read, so
     * screens can collect it without a null check.
     */
    val display: StateFlow<DisplaySettings> by lazy {
        settings.display.stateIn(scope, SharingStarted.Eagerly, DisplaySettings())
    }

    /** Autoplay settings, read by the players when they build a queue. */
    val playback: StateFlow<PlaybackSettings> by lazy {
        settings.playback.stateIn(scope, SharingStarted.Eagerly, PlaybackSettings())
    }

    /** Highlights, notes and bookmarks, as JSON in the private files dir. */
    val study: StudyStore by lazy { StudyStore(File(ctx().filesDir, StudyStore.DIR)) }

    /** Footnotes from assets/footnotes. None are bundled yet, so every book reads as empty. */
    val footnotes: FootnoteSource by lazy { AssetFootnotes { path -> ctx().readAsset(path) } }

    /** Webster's 1828, read one shard at a time. Lookups block; call off the main thread. */
    val dictionary: Dictionary by lazy {
        val c = ctx()
        WebsterDictionary(load = { path -> runCatching { c.readAsset(path) }.getOrNull() })
    }

    /** Strong's Hebrew/Greek behind the KJV text (OT/NT only). */
    val originalLanguage: OriginalLanguage by lazy {
        val c = ctx()
        StrongsOriginalLanguage(load = { path -> runCatching { c.readAsset(path) }.getOrNull() })
    }

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
