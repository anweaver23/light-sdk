package io.github.anweaver23.scriptures.data

import io.github.anweaver23.scriptures.core.FileMatcher
import io.github.anweaver23.scriptures.core.ImportDecision
import io.github.anweaver23.scriptures.core.ImportPlanner
import io.github.anweaver23.scriptures.core.IncomingFile
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.core.UploadKind
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reads embedded title/album text from an audio file, for names that don't say what they are. */
fun interface TagReader {
    fun read(file: File): String?
}

/**
 * Sorts files the user dropped into the Tool Manager upload folders ([inboxRoot]/<kind folder>)
 * into the [library]. Anything it can't place stays put and is listed in [pending] for review.
 * Every operation is safe to repeat; a file is either in the inbox or in the library.
 */
class Importer(
    private val inboxRoot: File,
    private val matcher: FileMatcher,
    private val library: AudioLibrary,
    private val tags: TagReader = TagReader { null },
) {
    data class ReviewItem(val file: IncomingFile, val reason: ImportDecision.Reason)

    data class Report(val imported: Int, val needsReview: Int)

    private val mutex = Mutex()

    private val _pending = MutableStateFlow<List<ReviewItem>>(emptyList())
    val pending: StateFlow<List<ReviewItem>> = _pending.asStateFlow()

    private val _lastReport = MutableStateFlow<Report?>(null)
    val lastReport: StateFlow<Report?> = _lastReport.asStateFlow()

    fun folder(kind: UploadKind) = File(inboxRoot, kind.folder)

    private fun fileOf(item: IncomingFile) = File(folder(item.kind), item.relativePath)

    suspend fun importAll(): Report = mutex.withLock {
        withContext(Dispatchers.IO) {
            val incoming = UploadKind.entries.flatMap { kind ->
                val dir = folder(kind).apply { mkdirs() }
                expandZips(dir)
                dir.walkTopDown()
                    .filter { it.isFile }
                    .map { IncomingFile(kind, it.relativeTo(dir).invariantSeparatorsPath) }
                    .toList()
            }
            val decisions = ImportPlanner.plan(incoming, matcher) { file ->
                if (file.kind == UploadKind.HymnLyrics) null else runCatching { tags.read(fileOf(file)) }.getOrNull()
            }
            var imported = 0
            for (decision in decisions) {
                if (decision is ImportDecision.Matched) {
                    library.store(fileOf(decision.file), decision.target, libraryKind(decision.file.kind), decision.extension)
                    imported++
                }
            }
            UploadKind.entries.forEach { pruneEmptyDirs(folder(it)) }
            library.refresh()
            val review = decisions.filterIsInstance<ImportDecision.NeedsReview>().map { ReviewItem(it.file, it.reason) }
            _pending.value = review
            Report(imported, review.size).also { _lastReport.value = it }
        }
    }

    /** The user picked a target for a file the matcher couldn't place. */
    suspend fun assign(item: IncomingFile, target: Target) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = fileOf(item)
            if (file.isFile) {
                library.store(file, target, libraryKind(item.kind), file.extension.lowercase())
                pruneEmptyDirs(folder(item.kind))
                library.refresh()
            }
            _pending.value = _pending.value.filterNot { it.file == item }
        }
    }

    suspend fun delete(items: List<IncomingFile>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            items.forEach { fileOf(it).delete() }
            UploadKind.entries.forEach { pruneEmptyDirs(folder(it)) }
            _pending.value = _pending.value.filterNot { it.file in items }
        }
    }

    private fun libraryKind(kind: UploadKind) =
        if (kind == UploadKind.HymnLyrics) AudioLibrary.Kind.Lyrics else AudioLibrary.Kind.Audio

    /**
     * Unpacks each .zip into a folder named after it (so "Alma.zip" can hint the book for
     * "032.mp3" inside), then deletes the zip. A zip that won't open is left for review.
     */
    private fun expandZips(dir: File) {
        val zips = dir.walkTopDown().filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }.toList()
        for (zip in zips) {
            val dest = File(zip.parentFile, zip.nameWithoutExtension)
            val destPath = dest.canonicalPath + File.separator
            val ok = runCatching {
                ZipFile(zip).use { archive ->
                    for (entry in archive.entries()) {
                        if (entry.isDirectory) continue
                        val out = File(dest, entry.name).canonicalFile
                        // Refuse "../" entries that would escape the upload folder.
                        if (!out.path.startsWith(destPath)) continue
                        out.parentFile?.mkdirs()
                        archive.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                }
            }.isSuccess
            if (ok) zip.delete()
        }
    }

    private fun pruneEmptyDirs(dir: File) {
        dir.walkBottomUp()
            .filter { it.isDirectory && it != dir && it.listFiles().isNullOrEmpty() }
            .forEach { it.delete() }
    }
}
