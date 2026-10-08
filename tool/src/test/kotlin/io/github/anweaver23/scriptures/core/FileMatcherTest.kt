package io.github.anweaver23.scriptures.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileMatcherTest {
    // Unit tests run with the module directory as the working directory.
    private val index = Catalog.parseIndex(File("src/main/assets/scriptures/index.json").readText())

    private val hymns1985 = HymnBook(
        id = Catalog.HYMNS_1985,
        title = "Hymns (1985)",
        hymns = listOf(
            Hymn(1, "The Morning Breaks"),
            Hymn(2, "The Spirit of God"),
            Hymn(85, "How Firm a Foundation"),
            Hymn(301, "I Am a Child of God"),
        ),
    )
    private val hymnsNew = HymnBook(
        id = Catalog.HYMNS_HOME_CHURCH,
        title = "Hymns—For Home and Church",
        hymns = listOf(
            Hymn(1001, "Come, Thou Fount of Every Blessing"),
            Hymn(1002, "When the Savior Comes Again"),
        ),
    )
    private val matcher = FileMatcher(Catalog(index, listOf(hymns1985, hymnsNew)))

    private fun chapter(path: String, tag: String? = null) =
        matcher.matchScripture(path, tag)?.let { "${it.bookId} ${it.chapter}" }

    private fun hymn(path: String, tag: String? = null) =
        matcher.matchHymn(path, tag)?.let { "${it.hymnBookId} ${it.number}" }

    @Test
    fun plainNames() {
        assertEquals("alma 32", chapter("Alma 32.mp3"))
        assertEquals("1-ne 3", chapter("1 Nephi 3.mp3"))
        assertEquals("gen 1", chapter("Genesis 1.mp3"))
        assertEquals("ps 23", chapter("Psalm 23.mp3"))
        assertEquals("ps 119", chapter("Psalms 119.m4a"))
        assertEquals("dc 4", chapter("D&C 4.mp3"))
        assertEquals("dc 76", chapter("Doctrine and Covenants Section 76.mp3"))
        assertEquals("dc 121", chapter("Section 121.mp3"))
        assertEquals("moses 1", chapter("Moses 1.mp3"))
        assertEquals("song 2", chapter("Song of Solomon 2.mp3"))
    }

    @Test
    fun slugsPaddingAndSuffixes() {
        assertEquals("alma 32", chapter("bofm_alma_032_eng.mp3"))
        assertEquals("1-ne 1", chapter("bofm-1-ne-001-eng.mp3"))
        assertEquals("dc 4", chapter("dc-testament_dc_004_eng.mp3"))
        assertEquals("js-h 1", chapter("pgp_js-h_001_eng.mp3"))
        assertEquals("alma 32", chapter("alma32.mp3"))
        assertEquals("1-ne 3", chapter("1ne3.mp3"))
        assertEquals("matt 5", chapter("matt-5.mp3"))
    }

    @Test
    fun numberedBooksBeatPlainBooks() {
        assertEquals("1-jn 3", chapter("1 John 3.mp3"))
        assertEquals("john 3", chapter("John 3.mp3"))
        assertEquals("3-ne 11", chapter("3 Nephi 11.mp3"))
        assertEquals("3-ne 11", chapter("Third Nephi 11.mp3"))
        assertEquals("2-ne 31", chapter("II Nephi 31.mp3"))
        // "3 John" only has one chapter, so "03 John 3" must be John 3.
        assertEquals("john 3", chapter("03 John 3.mp3"))
    }

    @Test
    fun collectionNamesDontConfuseTheBook() {
        assertEquals("alma 32", chapter("Book of Mormon - Alma 32.mp3"))
        assertEquals("ether 12", chapter("The Book of Mormon/Ether 12.mp3"))
        assertEquals("matt 5", chapter("New Testament/Matthew 5.mp3"))
    }

    @Test
    fun singleChapterBooks() {
        assertEquals("enos 1", chapter("Enos.mp3"))
        assertEquals("js-h 1", chapter("Joseph Smith—History.mp3"))
        assertEquals("w-of-m 1", chapter("Words of Mormon.mp3"))
        assertEquals("a-of-f 1", chapter("Articles of Faith.mp3"))
    }

    @Test
    fun bookFromFolder() {
        assertEquals("alma 32", chapter("Alma/032.mp3"))
        assertEquals("alma 32", chapter("Book of Mormon/Alma/Track 32.mp3"))
        assertEquals("1-ne 5", chapter("1 Nephi/05.mp3"))
    }

    @Test
    fun tagsAreAFallback() {
        assertEquals("hel 5", chapter("track07.mp3", tag = "Book of Mormon Helaman 5"))
        assertNull(chapter("track07.mp3"))
    }

    @Test
    fun outOfRangeAndJunk() {
        assertNull(chapter("Alma 99.mp3"))
        assertNull(chapter("random recording.mp3"))
        assertNull(chapter("Nephi.mp3"))
    }

    @Test
    fun hymns() {
        assertEquals("hymns-1985 85", hymn("How Firm a Foundation.mp3"))
        assertEquals("hymns-1985 85", hymn("085-how-firm-a-foundation.mp3"))
        assertEquals("hymns-1985 85", hymn("Hymn 85.mp3"))
        assertEquals("hymns-1985 301", hymn("301.mp3"))
        assertEquals("hymns-home-church 1001", hymn("1001 Come Thou Fount of Every Blessing.mp3"))
        assertEquals("hymns-home-church 1002", hymn("1002.mp3"))
        assertEquals("hymns-1985 2", hymn("lyrics/The Spirit of God.txt"))
        assertNull(hymn("unknown song.mp3"))
        assertNull(hymn("999.mp3"))
    }

    @Test
    fun plannerSortsMatchesDuplicatesAndJunk() {
        val files = listOf(
            IncomingFile(UploadKind.ScriptureAudio, "Alma 32.mp3"),
            IncomingFile(UploadKind.ScriptureAudio, "bofm_alma_032_eng.mp3"),
            IncomingFile(UploadKind.ScriptureAudio, "cover.jpg"),
            IncomingFile(UploadKind.ScriptureAudio, "notes.docx"),
            IncomingFile(UploadKind.ScriptureAudio, "mystery.mp3"),
            IncomingFile(UploadKind.HymnLyrics, "85.txt"),
        )
        val byPath = ImportPlanner.plan(files, matcher).associateBy { it.file.relativePath }
        assertEquals(Target.Chapter("bofm", "alma", 32), (byPath.getValue("Alma 32.mp3") as ImportDecision.Matched).target)
        assertEquals(ImportDecision.Reason.Duplicate, (byPath.getValue("bofm_alma_032_eng.mp3") as ImportDecision.NeedsReview).reason)
        assertTrue(byPath.getValue("cover.jpg") is ImportDecision.Ignored)
        assertEquals(ImportDecision.Reason.WrongType, (byPath.getValue("notes.docx") as ImportDecision.NeedsReview).reason)
        assertEquals(ImportDecision.Reason.NoMatch, (byPath.getValue("mystery.mp3") as ImportDecision.NeedsReview).reason)
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 85), (byPath.getValue("85.txt") as ImportDecision.Matched).target)
    }

    @Test
    fun targetKeysRoundTrip() {
        val targets = listOf(Target.Chapter("bofm", "1-ne", 3), Target.HymnRef(Catalog.HYMNS_HOME_CHURCH, 1001))
        targets.forEach { assertEquals(it, Target.fromKey(it.key)) }
    }
}
