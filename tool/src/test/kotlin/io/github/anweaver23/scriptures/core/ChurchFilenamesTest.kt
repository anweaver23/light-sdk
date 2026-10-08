package io.github.anweaver23.scriptures.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Real filenames from churchofjesuschrist.org downloads, matched against the bundled catalog. */
class ChurchFilenamesTest {
    private fun asset(path: String) = File("src/main/assets/$path").readText()

    private val catalog = Catalog(
        scriptures = Catalog.parseIndex(asset("scriptures/index.json")),
        hymnBooks = Catalog.HYMN_BOOK_FILES.map { Catalog.parseHymnBook(asset("hymns/$it")) },
    )
    private val matcher = FileMatcher(catalog)

    private fun chapter(path: String) = matcher.matchScripture(path)?.let { "${it.bookId} ${it.chapter}" }
    private fun hymn(path: String) = matcher.matchHymn(path)?.let { "${it.hymnBookId} ${it.number}" }

    @Test
    fun hymnBooksLoad() {
        assertEquals(341, catalog.hymnBook(Catalog.HYMNS_1985)!!.hymns.size)
        assertEquals("How Firm a Foundation", catalog.hymnBook(Catalog.HYMNS_1985)!!.hymn(85)!!.title)
        assertEquals("It Is Well with My Soul", catalog.hymnBook(Catalog.HYMNS_HOME_CHURCH)!!.hymn(1003)!!.title)
    }

    @Test
    fun scriptureStudyDownloads() {
        assertEquals("alma 32", chapter("2015-11-1270-alma-32-male-voice-64k-eng.mp3"))
        assertEquals("dc 4", chapter("2015-11-0040-section-04-female-voice-64k-eng.mp3"))
        assertEquals("dc 138", chapter("2015-11-1380-section-138-male-voice-64k-eng.mp3"))
        assertEquals("gen 1", chapter("2015-11-0010-genesis-01-male-voice-64k-eng.mp3"))
        assertEquals("moses 1", chapter("2015-11-0010-moses-01-male-voice-64k-eng.mp3"))
        assertEquals("1-cor 13", chapter("2015-11-1460-1-corinthians-13-male-voice-64k-eng.mp3"))
        assertEquals("ps 119", chapter("2015-11-5910-psalm-119-male-voice-64k-eng.mp3"))
        assertEquals("song 2", chapter("2015-11-6800-the-song-of-solomon-02-male-voice-64k-eng.mp3"))
        assertEquals("1-ne 1", chapter("2015-11-0010-1-nephi-01-male-voice-64k-eng.mp3"))
        assertEquals("w-of-m 1", chapter("2015-11-0900-words-of-mormon-01-male-voice-64k-eng.mp3"))
        assertEquals("obad 1", chapter("2015-11-8410-obadiah-01-male-voice-64k-eng.mp3"))
        assertEquals("enos 1", chapter("2015-11-0870-enos-01-female-voice-64k-eng.mp3"))
    }

    @Test
    fun pearlOfGreatPriceItemsWithoutChapterNumbers() {
        assertEquals("js-h 1", chapter("2015-11-0150-joseph-smith-history-male-voice-64k-eng.mp3"))
        assertEquals("js-m 1", chapter("2015-11-0140-joseph-smith-matthew-male-voice-64k-eng.mp3"))
        assertEquals("a-of-f 1", chapter("2015-11-0160-the-articles-of-faith-male-voice-64k-eng.mp3"))
    }

    @Test
    fun olderAndUnknownScriptureNames() {
        assertEquals("alma 10", chapter("BM_112_Alma_10_eng.mp3"))
        // JST and Official Declarations aren't in the bundled text, so they go to review.
        assertNull(chapter("2015-11-0280-jst-amos-73-male-voice-64k-eng.mp3"))
        assertNull(chapter("65d0929a0cd511ec9244eeeeac1e56461a1b9af4-64k-en.mp3"))
    }

    @Test
    fun hymnDownloads1985() {
        assertEquals("hymns-1985 1", hymn("the_morning_breaks_accompaniment_eng.mp3"))
        assertEquals("hymns-1985 2", hymn("the_spirit_of_god_vocal_accompaniment_eng.mp3"))
        assertEquals("hymns-1985 30", hymn("2001-01-0300-come-come-ye-saints-instrumental-192k-eng.mp3"))
        assertEquals("hymns-1985 98", hymn("2001-01-0980-i-need-thee-every-hour-vocal-and-instrumental-192k-eng.mp3"))
        assertEquals("hymns-1985 341", hymn("2001-01-3410-god-save-the-king-instrumental-192k-eng.mp3"))
        assertEquals("hymns-1985 137", hymn("testimony_accompaniment_eng.mp3"))
        assertEquals("hymns-1985 49", hymn("adam_ondi_ahman_vocal_accompaniment_eng.mp3"))
    }

    @Test
    fun hymnDownloadsHomeAndChurch() {
        assertEquals("hymns-home-church 1003", hymn("it_is_well_with_my_soul.mp3"))
        assertEquals("hymns-home-church 1003", hymn("it_is_well_with_my_soul (1).mp3"))
        assertEquals("hymns-home-church 1001", hymn("come_thou_fount_of_every_blessing.mp3"))
        assertEquals("hymns-home-church 1039", hymn("because.mp3"))
    }
}
