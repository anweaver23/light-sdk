package io.github.anweaver23.scriptures.data

import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.FileMatcher
import io.github.anweaver23.scriptures.core.ImportDecision
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.core.UploadKind
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ImporterTest {
    private val root = Files.createTempDirectory("importer").toFile()
    private val inbox = File(root, "shared")
    private val library = AudioLibrary(root)

    private val catalog = Catalog(
        scriptures = Catalog.parseIndex(File("src/main/assets/scriptures/index.json").readText()),
        hymnBooks = Catalog.HYMN_BOOK_FILES.map { Catalog.parseHymnBook(File("src/main/assets/hymns/$it").readText()) },
    )
    private val tags = mapOf("track07.mp3" to "Book of Mormon Helaman 5")
    private val importer = Importer(inbox, FileMatcher(catalog), library) { tags[it.name] }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun upload(kind: UploadKind, path: String, body: String = "audio") =
        File(importer.folder(kind), path).apply { parentFile.mkdirs(); writeText(body) }

    private fun uploadZip(kind: UploadKind, name: String, entries: List<String>) {
        val zip = File(importer.folder(kind), name).apply { parentFile.mkdirs() }
        ZipOutputStream(zip.outputStream()).use { out ->
            entries.forEach { entry ->
                out.putNextEntry(ZipEntry(entry))
                out.write(entry.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun bulkUploadIsSortedIntoTheLibrary() = runBlocking {
        upload(UploadKind.ScriptureAudio, "2015-11-1270-alma-32-male-voice-64k-eng.mp3")
        upload(UploadKind.ScriptureAudio, "Book of Mormon/Alma/033.mp3")
        upload(UploadKind.ScriptureAudio, "track07.mp3")
        upload(UploadKind.ScriptureAudio, "mystery.mp3")
        upload(UploadKind.HymnAudio, "it_is_well_with_my_soul.mp3")
        upload(UploadKind.HymnLyrics, "85.txt", "How firm a foundation...")
        uploadZip(UploadKind.ScriptureAudio, "Ether.zip", listOf("12.mp3", "../escape.mp3"))

        val report = importer.importAll()

        assertEquals(6, report.imported)
        assertEquals(1, report.needsReview)
        val index = library.index.value
        listOf(
            Target.Chapter("bofm", "alma", 32),
            Target.Chapter("bofm", "alma", 33),
            Target.Chapter("bofm", "hel", 5),
            Target.Chapter("bofm", "ether", 12),
            Target.HymnRef(Catalog.HYMNS_HOME_CHURCH, 1003),
        ).forEach { assertTrue(index.hasAudio(it), "missing $it") }
        assertEquals("How firm a foundation...", index.lyrics(Target.HymnRef(Catalog.HYMNS_1985, 85))!!.readText())
        assertEquals(ImportDecision.Reason.NoMatch, importer.pending.value.single().reason)
        assertFalse(File(root, "escape.mp3").exists())
        assertFalse(File(importer.folder(UploadKind.ScriptureAudio), "Ether.zip").exists())
        // Matched files are moved, so only the unknown file is left in the inbox.
        assertEquals(listOf("mystery.mp3"), importer.folder(UploadKind.ScriptureAudio).walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    @Test
    fun reviewItemsCanBeAssignedOrDeleted() = runBlocking {
        upload(UploadKind.ScriptureAudio, "mystery.mp3", "first")
        upload(UploadKind.ScriptureAudio, "Alma 32.mp3")
        upload(UploadKind.ScriptureAudio, "bofm_alma_032_eng.mp3")
        importer.importAll()
        assertEquals(2, importer.pending.value.size)

        val mystery = importer.pending.value.first { it.reason == ImportDecision.Reason.NoMatch }.file
        importer.assign(mystery, Target.Chapter("bofm", "moro", 10))
        assertEquals("first", library.index.value.audio(Target.Chapter("bofm", "moro", 10))!!.readText())

        val duplicate = importer.pending.value.single { it.reason == ImportDecision.Reason.Duplicate }.file
        importer.delete(listOf(duplicate))
        assertTrue(importer.pending.value.isEmpty())
        assertTrue(importer.importAll().let { it.imported == 0 && it.needsReview == 0 })
    }

    @Test
    fun reuploadReplacesEarlierFileEvenWithADifferentExtension() = runBlocking {
        upload(UploadKind.HymnAudio, "Hymn 85.mp3", "old")
        importer.importAll()
        upload(UploadKind.HymnAudio, "Hymn 85.m4a", "new")
        importer.importAll()
        val stored = library.index.value.audio(Target.HymnRef(Catalog.HYMNS_1985, 85))!!
        assertEquals("new", stored.readText())
        assertEquals(1, stored.parentFile.listFiles()!!.size)
    }
}
