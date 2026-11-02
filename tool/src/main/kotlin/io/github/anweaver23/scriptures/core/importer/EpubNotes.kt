package io.github.anweaver23.scriptures.core.importer

import io.github.anweaver23.scriptures.core.ScriptureIndex
import io.github.anweaver23.scriptures.core.Span
import io.github.anweaver23.scriptures.core.Words

/**
 * Reads the footnotes out of one chapter of the Church's scripture EPUBs. Two markups exist and both
 * are handled:
 * - the Book of Mormon (2013): `<a class="study-note-ref" href="#note2a"><sup class="marker">a</sup>poor</a>`,
 *   notes in `<footer class="study-notes"><li id="note2a">`, links like `../alma/34.xhtml#p40`;
 * - the Bible: `<span class="studyNoteReference"><a href="#lds_1-2a"><span class="marker">a</span>form</a></span>`,
 *   notes in `<div class="studyNotes"><div class="listItem" id="lds_1-2a">`, links like
 *   `gen_2.xhtml#lds_gen_2_4` and `tg_time.xhtml`.
 *
 * Each note is located by words: the verse's text as the EPUB has it is tokenized with [Words.spans],
 * and [Note.tokens] keeps those tokens so the importer can check they match the imported verse before
 * trusting the word indexes.
 */
class EpubNotes(private val index: ScriptureIndex) {
    private val refs = RefText(index)
    private val volumeOf: Map<String, String> = index.volumes.flatMap { v -> v.books.map { it.id to v.id } }.toMap()

    class Note(val verse: Int, val word: Int, val end: Int, val marker: String, val parts: List<Span>, val tokens: List<String>)

    /** The footnotes in one chapter file, in reading order. [bookId] is the file's own book, for relative links. */
    fun chapter(xhtml: String, bookId: String): List<Note> {
        val root = Html.parse(xhtml)
        val bodies = HashMap<String, List<Span>>()
        for (item in root.all { (it.name == "li" || it.hasClass("listItem")) && it.id != null }) {
            if (item.isNoteBody()) bodies[item.id!!] = body(item, bookId)
        }
        val out = ArrayList<Note>()
        for (p in root.all { it.name == "p" && (it.hasClass("verse") || it.hasClass("verse-first")) }) {
            val number = p.first { it.hasClass("verse-number") || it.hasClass("verseNumber") }?.text()?.trim()?.toIntOrNull()
                ?: continue
            val anchors = ArrayList<Triple<Int, Int, Pair<String, String>>>() // start, end, (marker, noteId)
            val text = StringBuilder()
            var space = true
            fun append(s: String) {
                for (ch in s) {
                    if (ch.isWhitespace()) {
                        if (!space) text.append(' ')
                        space = true
                    } else {
                        text.append(ch)
                        space = false
                    }
                }
            }
            fun walk(node: Node) {
                when (node) {
                    is TextNode -> append(node.text)
                    is Element -> when {
                        node.hasClass("verse-number") || node.hasClass("verseNumber") || node.isMarker() -> Unit
                        node.name == "br" -> append(" ")
                        node.isNoteRef() -> {
                            val link = if (node.name == "a") node else node.first { it.name == "a" }
                            val id = link?.attrs?.get("href")?.substringAfter('#')
                            val marker = node.first { it.isMarker() }?.text()?.trim().orEmpty()
                            val start = text.length
                            node.children.forEach(::walk)
                            if (id != null) anchors += Triple(start, text.length, marker to id)
                        }
                        else -> node.children.forEach(::walk)
                    }
                }
            }
            p.children.forEach(::walk)
            val verseText = text.toString().trim()
            val lead = text.length - text.trimStart().length
            val spans = Words.spans(verseText)
            val tokens = spans.map { it.text(verseText) }
            for ((s0, e0, me) in anchors) {
                val s = s0 - lead
                val e = e0 - lead
                val first = spans.firstOrNull { it.end > s }?.index ?: continue
                val last = spans.lastOrNull { it.start < e }?.index ?: first
                out += Note(number, first, maxOf(first, last), me.first, bodies[me.second].orEmpty(), tokens)
            }
        }
        return out
    }

    /** "marker", and the Bible's "marker_low-case" (in all-caps inscriptions) and "marker_asterisk". */
    private fun Element.isMarker() = classes.any { it.startsWith("marker") }

    private fun Element.isNoteRef() = hasClass("study-note-ref") || hasClass("studyNoteReference")

    /** A note body's id: "note2a" (Book of Mormon) or "lds_1-2a" (Bible), not a table of contents entry. */
    private fun Element.isNoteBody(): Boolean {
        val id = id ?: return false
        return id.startsWith("note") || id.startsWith("lds_")
    }

    /** A note's text as linked runs, without its label ("2a") and back arrow. */
    private fun body(item: Element, bookId: String): List<Span> {
        val out = ArrayList<Span>()
        fun walk(node: Node, style: String?, to: String?) {
            when (node) {
                is TextNode -> {
                    val t = Scan.collapse(node.text)
                    if (to != null || style == "sc") out += Span(t, to, style) else out += refs.link(t).map { if (style != null && it.to == null) it.copy(s = style) else it }
                }
                is Element -> {
                    if (node.hasClass("BACK") || node.hasClass("label-group") || node.hasClass("GROUPlabels")) return
                    val s = when {
                        node.name == "small" || node.hasClass("small") -> "sc"
                        node.name == "em" || node.name == "i" -> "i"
                        else -> style
                    }
                    val link = if (node.name == "a") target(node.attrs["href"], bookId) else null
                    node.children.forEach { walk(it, s, link ?: to) }
                }
            }
        }
        item.children.forEach { walk(it, null, null) }
        return linkTopics(RefText.merge(out)).let { spans ->
            if (spans.isEmpty()) spans else spans.toMutableList().also {
                it[0] = it[0].copy(t = it[0].t.trimStart())
                it[it.lastIndex] = it[it.lastIndex].copy(t = it[it.lastIndex].t.trimEnd())
            }.filter { it.t.isNotEmpty() }
        }
    }

    /**
     * The Book of Mormon's notes name Topical Guide and Bible Dictionary entries without linking them
     * ("TG Assembly for Worship; Poor."): link each name after a TG / BD / GS label to its page id
     * ("tg/assembly-for-worship"). The importer drops links to pages that don't exist.
     */
    private fun linkTopics(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        var helper: String? = null
        for (span in spans) {
            if (span.s == "sc") {
                helper = HELPERS[span.t.trim()]
                out += span
                continue
            }
            val h = helper
            if (h == null || span.to != null) {
                if (span.to != null) helper = null
                out += span
                continue
            }
            // Topic names run to the end of the sentence, separated by "; ".
            val text = span.t
            val stop = text.indexOf('.').let { if (it < 0) text.length else it }
            val names = text.substring(0, stop)
            var at = 0
            for (piece in names.split(';')) {
                val lead = piece.length - piece.trimStart().length
                val name = piece.trim()
                if (name.isNotEmpty()) {
                    out += Span(text.substring(at, at + lead))
                    out += Span(name, to = "$h/${slug(name)}")
                }
                at += piece.length
                if (at < names.length) {
                    out += Span(";")
                    at += 1
                }
            }
            out += Span(text.substring(stop))
            helper = null
        }
        return RefText.merge(out)
    }

    /** An EPUB href → a link target: a verse key, or a study help page id. */
    private fun target(href: String?, bookId: String): String? {
        if (href == null || href.startsWith("#")) return null
        val file = href.substringAfterLast('/').substringBefore('#').removeSuffix(".xhtml")
        val anchor = href.substringAfter('#', "")
        // Study helps: tg_time.xhtml, bd_aaron.xhtml.
        for ((prefix, helper) in listOf("tg_" to "tg", "bd_" to "bd", "gs_" to "gs")) {
            if (file.startsWith(prefix)) return "$helper/${file.removePrefix(prefix)}"
        }
        // Book of Mormon: ../alma/34.xhtml#p40.
        val dir = href.substringBeforeLast('/', "").substringAfterLast('/')
        if (dir.isNotEmpty() && file.toIntOrNull() != null) {
            val volume = volumeOf[dir] ?: return null
            val verse = anchor.removePrefix("p").toIntOrNull()
            return "$volume/$dir/$file" + (verse?.let { "/$it" } ?: "")
        }
        // Bible: gen_2.xhtml#lds_gen_2_4.
        val book = file.substringBeforeLast('_')
        val chapter = file.substringAfterLast('_').toIntOrNull()
        val volume = volumeOf[book]
        if (volume != null && chapter != null) {
            val verse = anchor.substringAfterLast('_').toIntOrNull()?.takeIf { anchor.startsWith("lds_") }
            return "$volume/$book/$chapter" + (verse?.let { "/$it" } ?: "")
        }
        return null
    }

    companion object {
        private val HELPERS = mapOf("TG" to "tg", "BD" to "bd", "GS" to "gs")

        /** "God, Creator" → "god-creator", "Poor in Spirit" → "poor-in-spirit": the Church's page ids. */
        fun slug(name: String): String = Scan.slug(name)
    }
}
