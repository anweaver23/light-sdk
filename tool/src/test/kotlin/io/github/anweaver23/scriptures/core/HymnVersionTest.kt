package io.github.anweaver23.scriptures.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HymnVersionTest {
    private fun asset(path: String) = File("src/main/assets/$path").readText()

    private val matcher = FileMatcher(
        Catalog(
            scriptures = Catalog.parseIndex(asset("scriptures/index.json")),
            hymnBooks = Catalog.HYMN_BOOK_FILES.map { Catalog.parseHymnBook(asset("hymns/$it")) },
        ),
    )

    @Test
    fun `accompaniment and instrumental files are the music-only version`() {
        assertTrue(Target.isAccompanimentOnly("the_morning_breaks_accompaniment_eng.mp3"))
        assertTrue(Target.isAccompanimentOnly("2001-01-0300-come-come-ye-saints-instrumental-192k-eng.mp3"))
        assertFalse(Target.isAccompanimentOnly("the_spirit_of_god_vocal_accompaniment_eng.mp3"))
        assertFalse(Target.isAccompanimentOnly("2001-01-0980-i-need-thee-every-hour-vocal-and-instrumental-192k-eng.mp3"))
        assertFalse(Target.isAccompanimentOnly("Hymn 85.mp3"))
    }

    @Test
    fun `both versions of one hymn land in different slots`() {
        val music = matcher.match(UploadKind.HymnAudio, "the_morning_breaks_accompaniment_eng.mp3")
        val vocal = matcher.match(UploadKind.HymnAudio, "the_morning_breaks_vocal_accompaniment_eng.mp3")
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 1, accompaniment = true), music)
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 1), vocal)
        assertEquals("hymns/hymns-1985/1/accompaniment", music!!.key)
        assertEquals("hymns/hymns-1985/1", vocal!!.key)
    }

    @Test
    fun `keys round-trip for both versions`() {
        val music = Target.HymnRef("hymns-1985", 85, accompaniment = true)
        assertEquals(music, Target.fromKey(music.key))
        assertEquals(music.base, Target.fromKey(music.base.key))
    }

    @Test
    fun `women's and men's arrangements go to their own numbers`() {
        fun hymn(name: String) = matcher.match(UploadKind.HymnAudio, name) as Target.HymnRef
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 312), hymn("we_ever_pray_for_thee_women_words_and_music.mp3"))
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 312, accompaniment = true), hymn("we_ever_pray_for_thee_women_music_only.mp3"))
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 23), hymn("we_ever_pray_for_thee_vocal_accompaniment_eng.mp3"))
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 323), hymn("rise_up_o_men_of_god_men_words_and_music.mp3"))
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 324), hymn("rise_up_o_men_of_god_mens_choir_words_and_music.mp3"))
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 326), hymn("come_come_ye_saints_mens_choir_words_and_music.mp3"))
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 174), hymn("2001-01-1740-while-of-these-emblems-we-partake-vocal-and-instrumental-192k-eng.mp3"))
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 173), hymn("while_of_these_emblems_we_partake_saul_vocal_accompaniment_eng.mp3"))
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 232, accompaniment = true), hymn("let_us_oft_speak_kind_words_accompaniment_eng.mp3"))
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 176), hymn("tis_sweet_to_sing_the_matchless_love_meredith_vocal_accompaniment_eng.mp3"))
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 177), hymn("tis_sweet_to_sing_the_matchless_love_hancock_vocal_accompaniment_eng.mp3"))
    }

    @Test
    fun `words never take the accompaniment slot`() {
        assertEquals(Target.HymnRef(Catalog.HYMNS_1985, 1), matcher.match(UploadKind.HymnLyrics, "the_morning_breaks_accompaniment_eng.txt"))
    }
}
