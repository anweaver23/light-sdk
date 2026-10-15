package io.github.anweaver23.scriptures.data

import io.github.anweaver23.scriptures.core.Annotation
import io.github.anweaver23.scriptures.core.Annotations
import io.github.anweaver23.scriptures.core.Bookmark
import io.github.anweaver23.scriptures.core.StudyFormat
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.core.VerseRef
import io.github.anweaver23.scriptures.core.WordRange
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Highlights, notes and bookmarks, kept in [dir] (the tool's private `filesDir/study`) as two
 * versioned JSON files: `annotations.json` and `bookmarks.json`. Each write goes to a temp file
 * first and is then renamed over the old one, so a crash mid-write can't leave half a file.
 *
 * The flows start empty and fill once the files are read (on first use, off the main thread).
 * A file that can't be read is set aside as `<name>.unreadable-<time>` rather than overwritten.
 */
class StudyStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false

    private val annotationsFile get() = File(dir, ANNOTATIONS)
    private val bookmarksFile get() = File(dir, BOOKMARKS)

    /** Reads the files if that hasn't happened yet. Screens call this when they open. */
    suspend fun load() = mutex.withLock { ensureLoaded() }

    // --- annotations ---

    /**
     * Highlights [range] in [chapter], merging with the plain highlights it overlaps or touches
     * (see [Annotations.planHighlight]). Returns the saved highlight, or null if [range] was
     * already inside one.
     */
    suspend fun highlight(
        chapter: Target.Chapter,
        range: WordRange,
        wordCounts: (verse: Int) -> Int,
        quoteOf: (WordRange) -> String,
    ): Annotation? = mutex.withLock {
        ensureLoaded()
        val current = _annotations.value
        val plan = Annotations.planHighlight(current.filter { it.isIn(chapter) }, range, wordCounts)
        if (plan !is Annotations.Plan.Write) return@withLock null
        val created = Annotations.create(newId(), chapter, plan.range, quoteOf(plan.range), note = null, now = clock())
        saveAnnotations(current.filterNot { it.id in plan.removeIds } + created)
        created
    }

    /** Adds a note on [range]. Notes never merge with other annotations. */
    suspend fun addNote(chapter: Target.Chapter, range: WordRange, quote: String, note: String): Annotation =
        mutex.withLock {
            ensureLoaded()
            val created = Annotations.create(newId(), chapter, range, quote, note, now = clock())
            saveAnnotations(_annotations.value + created)
            created
        }

    /** Sets or clears (blank [note]) the note text; the highlight stays either way. */
    suspend fun setNote(id: String, note: String?) = mutex.withLock {
        ensureLoaded()
        val clean = note?.trim()?.takeIf { it.isNotEmpty() }
        val now = clock()
        saveAnnotations(_annotations.value.map { if (it.id == id) it.copy(note = clean, updatedAt = now) else it })
    }

    suspend fun deleteAnnotation(id: String) = mutex.withLock {
        ensureLoaded()
        saveAnnotations(_annotations.value.filterNot { it.id == id })
    }

    // --- bookmarks ---

    suspend fun addBookmark(ref: VerseRef): Bookmark = mutex.withLock {
        ensureLoaded()
        val created = Bookmark(newId(), ref, clock())
        saveBookmarks(_bookmarks.value + created)
        created
    }

    suspend fun deleteBookmark(id: String) = mutex.withLock {
        ensureLoaded()
        saveBookmarks(_bookmarks.value.filterNot { it.id == id })
    }

    // --- files ---

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            _annotations.value = read(annotationsFile, StudyFormat::decodeAnnotations)
            _bookmarks.value = read(bookmarksFile, StudyFormat::decodeBookmarks)
        }
        loaded = true
    }

    private fun <T> read(file: File, decode: (String) -> List<T>): List<T> {
        if (!file.exists()) return emptyList()
        return try {
            decode(file.readText())
        } catch (e: Exception) {
            // Keep the user's data for recovery instead of overwriting it on the next save.
            file.renameTo(File(file.parentFile, "${file.name}.unreadable-${clock()}"))
            emptyList()
        }
    }

    private suspend fun saveAnnotations(list: List<Annotation>) {
        withContext(Dispatchers.IO) { writeAtomically(annotationsFile, StudyFormat.encodeAnnotations(list)) }
        _annotations.value = list
    }

    private suspend fun saveBookmarks(list: List<Bookmark>) {
        withContext(Dispatchers.IO) { writeAtomically(bookmarksFile, StudyFormat.encodeBookmarks(list)) }
        _bookmarks.value = list
    }

    private fun writeAtomically(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val DIR = "study"
        const val ANNOTATIONS = "annotations.json"
        const val BOOKMARKS = "bookmarks.json"
    }
}
