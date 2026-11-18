package io.github.anweaver23.scriptures.core.importer

import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.FrontBlock
import io.github.anweaver23.scriptures.core.Span
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Handmade snippets in the shape of the Church's files (no real text beyond a phrase). */
class ImporterParsersTest {
    private val index = Catalog.parseIndex(File("src/main/assets/scriptures/index.json").readText())

    @Test
    fun `references link and carry their book`() {
        val spans = RefText(index).link("Mosiah 4:2; Morm. 9:11; D&C 14:9; 76:24 (20–24); Moses 2:1, 3.")
        assertEquals(
            listOf("bofm/mosiah/4/2", "bofm/morm/9/11", "dc-testament/dc/14/9", "dc-testament/dc/76/24", "pgp/moses/2/1", "pgp/moses/2/3"),
            spans.mapNotNull { it.to },
        )
        assertEquals("Mosiah 4:2; Morm. 9:11; D&C 14:9; 76:24 (20–24); Moses 2:1, 3.", spans.joinToString("") { it.t })
    }

    @Test
    fun `a chapter outside the book is not a link`() {
        assertEquals(emptyList(), RefText(index).link("Jude 9:1 and Gen. 51:1").mapNotNull { it.to })
    }

    @Test
    fun `victor chapters keep italics, small caps, paragraph marks and headings`() {
        val html = """
            <section id="/scriptures/ot/ps/3"><header><h3>Psalm 3</h3>
            <p class="study-summary">David cries unto the Lord—Salvation is of the Lord.</p>
            <p class="intro">A Psalm of David.</p></header><div class="body-block"><h4>1</h4>
            <p class="verse"><span class="deity-name"><span class="small-caps">Lord</span></span>, how many <span class="clarity-word">are</span> they!</p>
            <p class="verse"><span class="verse-number">2 </span><span class="para-mark">¶ </span>Many say&#8212;<span class="selah">Selah</span>.</p>
            </div></section>
        """.trimIndent()
        val volume = VictorParser(index).volume(html, "ot")
        val ps = volume.books.getValue("ps")
        assertEquals(listOf("LORD, how many are they!", "Many say—Selah."), ps.c[2])
        val x = ps.x[2]
        assertEquals("Psalm 3", x.title)
        assertEquals("A Psalm of David.", x.intro.joinToString("") { it.t })
        assertEquals(listOf(3..3), x.italicRuns(1))
        assertEquals(listOf(0..0), x.capsRuns(1))
        assertEquals(listOf(2), x.para)
    }

    @Test
    fun `a title page that is only its heading keeps the heading`() {
        // The Old Testament's title page in the Victor Reader set is nothing but its <h1>.
        val html = """<section id="/scriptures/ot/title-page"><header><h1>The <br />Old Testament</h1></header></section>
            <section id="/scriptures/ot/dedication"><header><h1>To the Prince</h1><p class="subtitle">King</p></header></section>
            <section id="/scriptures/ot/other"><header><h1>Other</h1></header></section>"""
        val pages = VictorParser(index).volume(html, "ot").pages.associateBy { it.id }
        val title = TextBuild.FRONT.getValue("ot/title-page").applyTo(pages.getValue("ot/title-page"))
        assertEquals("Title Page", title.title)
        assertEquals(listOf(FrontBlock("heading", "The Old Testament")), title.blocks)
        val dedication = TextBuild.FRONT.getValue("ot/dedication").applyTo(pages.getValue("ot/dedication"))
        assertEquals(listOf("To the Prince", "King"), dedication.blocks.map { it.text })
        // Pages whose heading is just their name don't repeat it.
        val plain = TextBuild.FrontSpec("Other").applyTo(pages.getValue("ot/other"))
        assertEquals(emptyList(), plain.blocks)
    }

    @Test
    fun `victor links point at the verse named in the link text`() {
        val html = """<section id="/scriptures/gs/abraham"><header><h2><strong>Abraham</strong></h2></header>
            <div class="body-block"><p>Born in Ur (<a href="http://www.churchofjesuschrist.org/study/scriptures/ot/gen/11">Gen. 11:26, 31</a>;
            <a href="http://www.churchofjesuschrist.org/study/scriptures/ot/gen/17">17:5</a>). <em>See also</em>
            <a href="http://www.churchofjesuschrist.org/study/scriptures/gs/abrahamic-covenant">Abrahamic Covenant</a>.</p></div></section>"""
        val page = VictorParser(index).pages(html).single()
        assertEquals("gs/abraham", page.id)
        assertEquals("Abraham", page.title)
        assertEquals(listOf("ot/gen/11/26", "ot/gen/17/5", "gs/abrahamic-covenant"), page.blocks.single().spans.mapNotNull { it.to })
    }

    @Test
    fun `book of mormon epub notes anchor on their words`() {
        val xhtml = """<html><body><p class="verse" id="p4"><span class="verse-number">4 </span>upon the hill
            <a class="study-note-ref" href="#note4a" id="ldsBACK_note4a"><sup class="marker">a</sup>Onidah</a>, of whom were
            <a class="study-note-ref" href="#note4c"><sup class="marker">c</sup>poor in heart</a>.</p>
            <footer class="study-notes"><ul><li id="note4a"><a class="BACK" href="#ldsBACK_note4a">◀</a>
            <p class="label-group"><span class="label-verse">4</span><span class="label-marker">a</span></p>
            <p><span><a class="scripture-ref" href="../alma/47.xhtml#p5">Alma 47:5</a>.</span></p></li>
            <li id="note4c"><p class="label-group"><span class="label-marker">c</span></p>
            <p><span data-note-category="tg"><small>TG</small> Poor in Spirit; Humility.</span></p></li></ul></footer></body></html>"""
        val notes = EpubNotes(index).chapter(xhtml, "alma")
        assertEquals(2, notes.size)
        val a = notes[0]
        assertEquals(Triple(4, 3, 3), Triple(a.verse, a.word, a.end))
        assertEquals("a", a.marker)
        assertEquals(listOf("bofm/alma/47/5"), a.parts.mapNotNull { it.to })
        val c = notes[1]
        assertEquals(7 to 9, c.word to c.end)
        assertEquals(listOf("tg/poor-in-spirit", "tg/humility"), c.parts.mapNotNull { it.to })
        assertEquals("TG Poor in Spirit; Humility.", c.parts.joinToString("") { it.t })
    }

    @Test
    fun `bible epub notes and their links`() {
        val xhtml = """<html><body><div class="bodyBlock"><p class="verse-first" id="lds_gen_1_1"><span class="verseNumber">1 </span>In the
            <span id="ldsBACK_1-1a" class="studyNoteReference"><a href="#lds_1-1a"><span class="marker">a</span>beginning</a></span> God.</p></div>
            <div class="studyNotes"><div class="listItem" id="lds_1-1a"><a class="BACK" href="#ldsBACK_1-1a">◀</a>
            <p class="GROUPlabels"><span class="label-verse">1</span><span class="label-marker">a</span></p>
            <p><span class="scriptureRef"><a href="tg_time.xhtml"><span class="small">TG</span> Time</a></span>; see
            <a href="gen_2.xhtml#lds_gen_2_4">Gen. 2:4</a>; Moses 2:1.</p></div></div></body></html>"""
        val n = EpubNotes(index).chapter(xhtml, "gen").single()
        assertEquals(Triple(1, 2, 2), Triple(n.verse, n.word, n.end))
        assertEquals(listOf("tg/time", "ot/gen/2/4", "pgp/moses/2/1"), n.parts.mapNotNull { it.to }.distinct())
    }

    @Test
    fun `html reader decodes entities and survives stray end tags`() {
        val root = Html.parse("<p>a &amp; b</i> &#8212; c<br/>d</p>")
        assertEquals("a & b — c\nd", root.elements.single().text())
        assertNull(Html.parse("<p>x").first { it.name == "span" })
        assertEquals(listOf(Span("x")), RefText.merge(listOf(Span(""), Span("x"))))
    }
}
