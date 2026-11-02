package io.github.anweaver23.scriptures.core.importer

import io.github.anweaver23.scriptures.core.BookText
import io.github.anweaver23.scriptures.core.ChapterExtras
import io.github.anweaver23.scriptures.core.FrontBlock
import io.github.anweaver23.scriptures.core.FrontPage
import io.github.anweaver23.scriptures.core.ScriptureIndex
import io.github.anweaver23.scriptures.core.Span
import io.github.anweaver23.scriptures.core.Words

/**
 * Reads the Church's "Standard Works — Victor Reader" HTML (one file per volume or study help, each a
 * run of `<section id="/scriptures/...">` blocks with Gospel Library uris).
 *
 * - Chapter sections ("/scriptures/ot/gen/1") become [BookText] chapters: verses as plain text, with
 *   [ChapterExtras] for the heading, summary, italics (`clarity-word`), small caps and ¶ marks.
 *   Small-caps words are stored upper case ("LORD"), as the KJV text has always been keyed, so word
 *   indexes and the Strong's tags line up; the reader draws them as small caps.
 * - Everything else (title pages, introductions, witnesses, facsimiles, the Topical Guide, Bible
 *   Dictionary, Guide to the Scriptures and JST entries) becomes a [FrontPage] keyed by its uri
 *   without "/scriptures/" ("bofm/three", "tg/faith").
 */
class VictorParser(private val index: ScriptureIndex) {
    private val refs = RefText(index)

    class Volume(val books: Map<String, BookText>, val pages: List<FrontPage>, val problems: List<String>)

    /** One volume's file ([volumeId] "ot", "nt", "bofm", "dc-testament", "pgp"). */
    fun volume(html: String, volumeId: String): Volume {
        val volume = index.volume(volumeId)
        val chapters = HashMap<String, MutableMap<Int, Pair<List<String>, ChapterExtras>>>()
        val titles = HashMap<String, String>()
        val pages = ArrayList<FrontPage>()
        val problems = ArrayList<String>()
        for ((uri, raw) in Html.sections(html)) {
            val path = uri.removePrefix("/scriptures/")
            val parts = path.split('/')
            val chapter = parts.getOrNull(2)?.toIntOrNull()
            val book = parts.getOrNull(1)?.let { id -> volume?.books?.firstOrNull { it.id == id } }
            val root = Html.parse(raw)
            if (parts.size == 3 && chapter != null && book != null) {
                val (verses, extras, bookTitle) = chapter(root)
                chapters.getOrPut(book.id) { HashMap() }[chapter] = verses to extras
                if (bookTitle != null && chapter == 1) titles[book.id] = bookTitle
            } else {
                pages += page(path, root)
            }
        }
        val books = LinkedHashMap<String, BookText>()
        for (book in volume?.books.orEmpty()) {
            val got = chapters[book.id]
            if (got == null) {
                problems += "$volumeId/${book.id}: missing"
                continue
            }
            val missing = (1..book.chapters).filter { it !in got }
            if (missing.isNotEmpty()) problems += "$volumeId/${book.id}: missing chapters $missing"
            val ordered = (1..book.chapters).map { got[it] ?: (emptyList<String>() to ChapterExtras()) }
            books[book.id] = BookText(c = ordered.map { it.first }, x = ordered.map { it.second }, title = titles[book.id])
        }
        return Volume(books, pages, problems)
    }

    /** A study help's file ("tg", "bd", "gs", "jst"): every section is a page. */
    fun pages(html: String): List<FrontPage> =
        Html.sections(html).map { (uri, raw) -> page(uri.removePrefix("/scriptures/"), Html.parse(raw)) }

    // --- chapters ---

    private data class ParsedChapter(val verses: List<String>, val extras: ChapterExtras, val bookTitle: String?)

    private fun chapter(root: Element): ParsedChapter {
        val header = root.first { it.name == "header" }
        val bookTitle = header?.first { it.name == "h1" }?.let { clean(it.text()) }
        val title = header?.first { it.name == "h2" || it.name == "h3" }?.let { clean(it.text()) }
        val summary = header?.firstByClass("study-summary")?.let(::spans).orEmpty()
        val intro = buildList {
            header?.allByClass("study-intro")?.forEach { addAll(spans(it)) }
            // A Psalm's title ("A Psalm of David…"), in the header or the body.
            root.allByClass("intro").forEach {
                if (isNotEmpty()) add(Span(" "))
                addAll(spans(it))
            }
        }
        val verses = ArrayList<String>()
        val italic = ArrayList<List<Int>>()
        val caps = ArrayList<List<Int>>()
        val para = ArrayList<Int>()
        for (p in root.allByClass("verse")) {
            val v = verse(p)
            verses += v.text
            italic += v.italic
            caps += v.caps
            if (v.para) para += verses.size
        }
        val extras = ChapterExtras(
            title = title,
            intro = RefText.merge(intro),
            summary = summary,
            italic = italic.takeIf { l -> l.any { it.isNotEmpty() } }.orEmpty(),
            caps = caps.takeIf { l -> l.any { it.isNotEmpty() } }.orEmpty(),
            para = para,
        )
        return ParsedChapter(verses, extras, bookTitle)
    }

    private class Verse(val text: String, val italic: List<Int>, val caps: List<Int>, val para: Boolean)

    /** Flags per character while walking a verse. */
    private class Run(val text: String, val italic: Boolean, val caps: Boolean)

    private fun verse(p: Element): Verse {
        val runs = ArrayList<Run>()
        var para = false
        fun walk(node: Node, italic: Boolean, caps: Boolean) {
            when (node) {
                is TextNode -> runs += Run(node.text, italic, caps)
                is Element -> when {
                    node.hasClass("verse-number") -> Unit
                    node.hasClass("para-mark") -> para = true
                    node.name == "br" -> runs += Run(" ", italic, caps)
                    // A poetic line (D&C 84:99) is its own block: keep the words apart.
                    node.hasClass("line") -> {
                        runs += Run(" ", false, false)
                        node.children.forEach { walk(it, italic, caps) }
                        runs += Run(" ", false, false)
                    }
                    else -> {
                        val i = italic || node.hasClass("clarity-word")
                        val c = caps || node.hasClass("small-caps")
                        node.children.forEach { walk(it, i, c) }
                    }
                }
            }
        }
        p.children.forEach { walk(it, false, false) }
        // Fold whitespace, keeping each kept character's flags.
        val text = StringBuilder()
        val italicAt = ArrayList<Boolean>()
        val capsAt = ArrayList<Boolean>()
        var space = true
        for (run in runs) {
            for (ch in run.text) {
                if (ch.isWhitespace()) {
                    if (!space) {
                        text.append(' ')
                        italicAt += false
                        capsAt += false
                        space = true
                    }
                } else {
                    text.append(if (run.caps) ch.uppercaseChar() else ch)
                    italicAt += run.italic
                    capsAt += run.caps
                    space = false
                }
            }
        }
        while (text.isNotEmpty() && text.last() == ' ') {
            text.setLength(text.length - 1)
            italicAt.removeAt(italicAt.lastIndex)
            capsAt.removeAt(capsAt.lastIndex)
        }
        val s = text.toString()
        val words = Words.spans(s)
        fun wordRuns(flags: List<Boolean>) = runs(words.filter { w -> (w.start until w.end).any { flags[it] } }.map { it.index })
        return Verse(s, wordRuns(italicAt), wordRuns(capsAt), para)
    }

    // --- pages ---

    private fun page(id: String, root: Element): FrontPage {
        val title = root.first { it.name in HEADINGS }?.let { clean(it.text()) } ?: id.substringAfterLast('/')
        val blocks = ArrayList<FrontBlock>()
        var skippedTitle = false
        fun block(el: Element) {
            when {
                el.name in HEADINGS -> {
                    val text = clean(el.text())
                    if (!skippedTitle && text == title) {
                        skippedTitle = true
                        return
                    }
                    if (text.isNotEmpty()) blocks += FrontBlock(if (el.name in setOf("h1", "h2")) "heading" else "subheading", text)
                }
                el.hasClass("verse") -> {
                    val number = el.firstByClass("verse-number")?.text()?.trim()?.toIntOrNull()
                    val s = spans(el, skipClass = "verse-number")
                    blocks += FrontBlock("verse", s.plainText(), s, n = number)
                }
                el.name == "p" || el.name == "figcaption" || el.name == "dd" || el.name == "dt" -> {
                    val s = spans(el)
                    if (s.plainText().isBlank()) return
                    val t = when {
                        el.hasClass("signature") -> "sig"
                        el.hasClass("entry") -> "entry"
                        el.hasClass("title") || el.hasClass("subtitle") -> "subheading"
                        else -> "p"
                    }
                    blocks += FrontBlock(t, s.plainText(), s)
                }
                el.name == "tr" -> {
                    val cells = el.elements.filter { it.name == "td" || it.name == "th" }
                    val s = RefText.merge(cells.flatMapIndexed { i, c -> (if (i > 0) listOf(Span("  ")) else emptyList()) + spans(c) })
                    if (s.plainText().isNotBlank()) blocks += FrontBlock("p", s.plainText(), s)
                }
                else -> el.elements.forEach(::block)
            }
        }
        root.elements.forEach(::block)
        return FrontPage(id = id, title = title, blocks = blocks)
    }

    /** An element's inline content as styled, linked runs. Plain-text references are linked too. */
    private fun spans(el: Element, skipClass: String? = null): List<Span> {
        val out = ArrayList<Span>()
        fun walk(node: Node, style: String?, to: String?) {
            when (node) {
                is TextNode -> {
                    val t = Scan.collapse(node.text)
                    if (to != null || style != null) out += Span(t, to, style) else out += refs.link(t)
                }
                is Element -> {
                    if (skipClass != null && node.hasClass(skipClass)) return
                    if (node.name == "br") {
                        out += Span(" ")
                        return
                    }
                    if (node.hasClass("verse-number") && skipClass == null) return
                    val s = when {
                        node.name == "em" || node.name == "i" || node.hasClass("clarity-word") -> "i"
                        node.name == "small" || node.hasClass("small-caps") -> "sc"
                        node.name == "strong" || node.name == "b" || node.hasClass("key-word") -> "b"
                        else -> style
                    }
                    val link = if (node.name == "a") linkTarget(node.attrs["href"], node.text()) else null
                    node.children.forEach { walk(it, s, link ?: to) }
                }
            }
        }
        el.children.forEach { walk(it, null, null) }
        return RefText.merge(trim(out))
    }

    /**
     * "http://www.churchofjesuschrist.org/study/scriptures/ot/gen/11" with link text "Gen. 11:26, 31" →
     * "ot/gen/11/26"; "…/scriptures/gs/abrahamic-covenant" → "gs/abrahamic-covenant".
     */
    private fun linkTarget(href: String?, text: String): String? {
        val path = href?.substringAfter("/study/scriptures/", "")?.substringBefore('?')?.substringBefore('#')
            ?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
        val parts = path.split('/')
        val chapter = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size == 3 && chapter != null) {
            val m = Scan.firstChapterVerse(text)
            val verse = if (m != null) {
                m.second.takeIf { m.first == chapter }
            } else {
                // A bare verse number ("31") or range ("1–4") pointing into the linked chapter.
                Scan.leadingNumber(text)
            }
            return if (verse != null) "$path/$verse" else path
        }
        return path
    }

    private fun trim(spans: List<Span>): List<Span> {
        if (spans.isEmpty()) return spans
        val out = spans.toMutableList()
        out[0] = out[0].copy(t = out[0].t.trimStart())
        out[out.lastIndex] = out[out.lastIndex].copy(t = out[out.lastIndex].t.trimEnd())
        return out.filter { it.t.isNotEmpty() }
    }

    private fun List<Span>.plainText() = joinToString("") { it.t }

    companion object {
        private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

        fun clean(s: String) = Scan.clean(s)

        /** Word indexes → flat inclusive runs `[first, last, ...]`. */
        fun runs(indexes: List<Int>): List<Int> {
            val out = ArrayList<Int>()
            for (i in indexes.sorted()) {
                if (out.isNotEmpty() && out.last() == i - 1) out[out.lastIndex] = i else out += listOf(i, i)
            }
            return out
        }
    }
}
