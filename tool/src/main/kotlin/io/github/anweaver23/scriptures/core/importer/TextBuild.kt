package io.github.anweaver23.scriptures.core.importer

import io.github.anweaver23.scriptures.core.BookFootnotes
import io.github.anweaver23.scriptures.core.BookText
import io.github.anweaver23.scriptures.core.Footnote
import io.github.anweaver23.scriptures.core.FrontPage
import io.github.anweaver23.scriptures.core.ScriptureIndex
import io.github.anweaver23.scriptures.core.Words
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turns the Church's downloads into the app's own text files. The sources:
 * - **Standard Works — Victor Reader** (a zip of `ot_eng.html`, `nt_eng.html`, `bofm_eng.html`,
 *   `dc-testament_eng.html`, `pgp_eng.html`, `tg_eng.html`, `bd_eng.html`, `gs_eng.html`, `jst_eng.html`,
 *   or those files loose): the text of every volume, its headings and front matter, and the study helps.
 * - Scripture **EPUBs** (the Bible's, the Book of Mormon's; any volume in either markup): footnotes only.
 *   A note is kept only when its verse's words match the Victor text, so its word anchors are right.
 *
 * Output, under the [Sink]'s root:
 * - `books/<volume>/<book>.json` [BookText], `notes/<volume>/<book>.json` [BookFootnotes],
 * - `pages/<id>.json` [FrontPage] (ids like "bofm/three", "tg/faith"), `pages.json` [PageIndex],
 * - `manifest.json` [TextManifest].
 */
class TextBuild(private val index: ScriptureIndex) {

    fun interface Sink {
        fun write(path: String, text: String)
    }

    /** Progress for the UI: a short line of what's being read. */
    fun interface Progress {
        fun step(what: String)
    }

    fun build(sources: List<File>, sink: Sink, progress: Progress = Progress { }): TextManifest {
        val victor = LinkedHashMap<String, String>() // "ot" -> html
        val epubs = ArrayList<File>()
        for (f in sources) {
            when {
                f.name.endsWith(".epub", ignoreCase = true) -> epubs += f
                f.name.endsWith(".zip", ignoreCase = true) -> f.inputStream().use { readVictorZip(it, victor) }
                f.name.endsWith("_eng.html", ignoreCase = true) -> victor[f.name.substringBefore("_eng")] = f.readText()
            }
        }
        val problems = ArrayList<String>()
        val parser = VictorParser(index)
        val texts = HashMap<String, BookText>() // "ot/gen"
        val pageIndex = LinkedHashMap<String, MutableList<PageEntry>>()
        fun addPages(group: String, pages: List<FrontPage>) {
            val list = pageIndex.getOrPut(group) { ArrayList() }
            for (p in pages) {
                sink.write("pages/${p.id}.json", json.encodeToString(FrontPage.serializer(), p))
                list += PageEntry(p.id, p.title)
            }
        }
        for (v in index.volumes) {
            val html = victor.remove(v.id) ?: continue
            progress.step(v.title)
            val vol = parser.volume(html, v.id)
            problems += vol.problems
            for ((bookId, text) in vol.books) {
                texts["${v.id}/$bookId"] = text
                sink.write("books/${v.id}/$bookId.json", json.encodeToString(BookText.serializer(), text))
            }
            addPages(v.id, vol.pages)
        }
        for ((id, title) in HELPS) {
            val html = victor.remove(id) ?: continue
            progress.step(title)
            addPages(id, parser.pages(html))
        }

        // Footnotes, checked against the text just built.
        val notes = HashMap<String, MutableMap<String, MutableList<Footnote>>>() // "ot/gen" -> "1:2" -> notes
        var placed = 0
        var dropped = 0
        val reader = EpubNotes(index)
        val files = index.volumes.flatMap { v -> v.books.map { b -> b.id to v.id } }.toMap()
        for (epub in epubs) {
            progress.step(epub.nameWithoutExtension)
            epub.inputStream().use { input ->
                eachEntry(input) { name, read ->
                    val (bookId, chapter) = chapterOf(name, files.keys) ?: return@eachEntry
                    val volumeId = files.getValue(bookId)
                    val verses = texts["$volumeId/$bookId"]?.c?.getOrNull(chapter - 1) ?: return@eachEntry
                    for (n in reader.chapter(read(), bookId)) {
                        val ours = verses.getOrNull(n.verse - 1)?.let(::tokens)
                        if (ours == null || ours != n.tokens.map(::norm)) {
                            dropped++
                            continue
                        }
                        placed++
                        notes.getOrPut("$volumeId/$bookId") { HashMap() }.getOrPut("$chapter:${n.verse}") { ArrayList() } +=
                            Footnote(word = n.word, end = n.end, marker = n.marker, text = n.parts.joinToString("") { it.t }, parts = n.parts)
                    }
                }
            }
        }
        for ((key, verses) in notes) {
            val file = BookFootnotes(version = 2, verses = verses.mapValues { (_, l) -> l.sortedBy { it.word } })
            sink.write("notes/$key.json", json.encodeToString(BookFootnotes.serializer(), file))
        }
        if (dropped > 0) problems += "$dropped footnotes skipped: their verse's words differ from the text"

        sink.write("pages.json", json.encodeToString(PageIndex.serializer(), PageIndex(pageIndex)))
        val manifest = TextManifest(
            books = texts.keys.sorted(),
            noteBooks = notes.keys.sorted(),
            footnotes = placed,
            pages = pageIndex.mapValues { it.value.size },
            problems = problems,
        )
        sink.write("manifest.json", json.encodeToString(TextManifest.serializer(), manifest))
        return manifest
    }

    private fun readVictorZip(input: InputStream, into: MutableMap<String, String>) {
        eachEntry(input) { name, read ->
            val file = name.substringAfterLast('/')
            if (file.endsWith("_eng.html", ignoreCase = true)) into[file.substringBefore("_eng")] = read()
        }
    }

    /** Book and chapter of an EPUB chapter file: "OEBPS/gen_1.xhtml" or "OEBPS/alma/32.xhtml". */
    private fun chapterOf(name: String, books: Set<String>): Pair<String, Int>? {
        if (!name.endsWith(".xhtml")) return null
        val file = name.substringAfterLast('/').removeSuffix(".xhtml")
        val dir = name.substringBeforeLast('/', "").substringAfterLast('/')
        file.toIntOrNull()?.let { c -> if (dir in books) return dir to c }
        val book = file.substringBeforeLast('_', "")
        val chapter = file.substringAfterLast('_').toIntOrNull()
        return if (book in books && chapter != null) book to chapter else null
    }

    private fun tokens(t: String) = Words.spans(t).map { norm(it.text(t)) }
    private fun norm(s: String) = s.lowercase().replace('’', '\'')

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Study helps in the Victor Reader set, in the order the app lists them. */
        val HELPS = linkedMapOf(
            "tg" to "Topical Guide",
            "bd" to "Bible Dictionary",
            "gs" to "Guide to the Scriptures",
            "jst" to "Joseph Smith Translation",
        )

        /** Every entry of a zip (or EPUB), streamed: [block] gets the name and a reader for its text. */
        fun eachEntry(input: InputStream, block: (String, () -> String) -> Unit) {
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        block(name) { decode(zip.readBytes()) }
                    }
                    entry = zip.nextEntry
                }
            }
        }

        /** UTF-8, or UTF-16 with a byte-order mark (as some Church EPUBs are). */
        fun decode(bytes: ByteArray): String = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16LE).drop(1)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16BE).drop(1)
            else -> bytes.decodeToString().removePrefix("﻿")
        }
    }
}

@Serializable
data class PageEntry(val id: String, val title: String)

/** Every imported page, by group: a volume's front matter ("bofm") or a study help ("tg"). */
@Serializable
data class PageIndex(val groups: Map<String, List<PageEntry>> = emptyMap())

/** What the last import produced. */
@Serializable
data class TextManifest(
    val books: List<String> = emptyList(),
    val noteBooks: List<String> = emptyList(),
    val footnotes: Int = 0,
    val pages: Map<String, Int> = emptyMap(),
    val problems: List<String> = emptyList(),
)
