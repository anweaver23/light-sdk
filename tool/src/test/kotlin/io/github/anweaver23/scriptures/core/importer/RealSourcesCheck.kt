package io.github.anweaver23.scriptures.core.importer

import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Words
import java.io.File
import kotlin.test.Test

/**
 * A report, not a test: runs the parsers over the real Church files when SCRIPTURE_SOURCES points at a
 * folder holding `victor/` (the Victor Reader HTML) and the unzipped `bible-epub/` and `bom-epub/`.
 * Does nothing otherwise (CI and normal builds). The files are copyrighted and never committed.
 */
class RealSourcesCheck {
    @Test
    fun report() {
        val root = System.getenv("SCRIPTURE_SOURCES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val assets = File("src/main/assets/scriptures")
        val index = Catalog.parseIndex(File(assets, "index.json").readText())
        val victor = VictorParser(index)
        val out = StringBuilder()
        var verses = 0
        var same = 0
        val texts = HashMap<String, List<List<String>>>()
        for (v in index.volumes) {
            val vol = victor.volume(File(root, "victor/${v.id}_eng.html").readText(), v.id)
            out.appendLine("${v.id}: ${vol.books.size} books, ${vol.pages.size} pages ${vol.pages.map { it.id }.take(12)} problems=${vol.problems}")
            for ((bookId, text) in vol.books) {
                texts["${v.id}/$bookId"] = text.c
                val old = File(assets, "${v.id}/$bookId.json").takeIf { it.isFile }?.let { Catalog.parseBook(it.readText()).c } ?: continue
                text.c.forEachIndexed { ci, ch ->
                    ch.forEachIndexed { vi, verse ->
                        verses++
                        val was = old.getOrNull(ci)?.getOrNull(vi).orEmpty()
                        if (tokens(verse) == tokens(was)) {
                            same++
                        } else if (verses - same <= 8) {
                            out.appendLine("  differs ${v.id}/$bookId ${ci + 1}:${vi + 1}: $verse | $was")
                        }
                    }
                }
            }
        }
        out.appendLine("verses $verses, same words as bundled: $same")
        val helps = listOf("tg", "bd", "gs", "jst").associateWith { victor.pages(File(root, "victor/${it}_eng.html").readText()) }
        helps.forEach { (k, p) -> out.appendLine("$k: ${p.size} pages, ${p.sumOf { it.blocks.size }} blocks") }
        val pageIds = helps.values.flatten().map { it.id }.toSet()

        val notes = EpubNotes(index)
        var placed = 0
        var mismatched = 0
        var links = 0
        var deadPages = 0
        val dead = LinkedHashSet<String>()
        fun epub(file: (String, Int) -> File) {
            for (v in index.volumes) for (b in v.books) for (c in 1..b.chapters) {
                val f = file(b.id, c).takeIf { it.isFile } ?: continue
                val verseTexts = texts["${v.id}/${b.id}"]?.getOrNull(c - 1) ?: continue
                for (n in notes.chapter(f.readText(), b.id)) {
                    val ours = verseTexts.getOrNull(n.verse - 1)?.let(::tokens)
                    if (ours == null || ours != n.tokens.map(::norm)) {
                        mismatched++
                        if (mismatched <= 5) out.appendLine("  token mismatch ${b.id} $c:${n.verse}")
                        continue
                    }
                    placed++
                    for (p in n.parts) {
                        val to = p.to ?: continue
                        links++
                        if (to.count { it == '/' } < 2 && to !in pageIds) {
                            deadPages++
                            if (dead.size < 20) dead += to
                        }
                    }
                }
            }
        }
        epub { b, c -> File(root, "bom-epub/OEBPS/$b/$c.xhtml") }
        val bomPlaced = placed
        epub { b, c -> File(root, "bible-epub/OEBPS/${b}_$c.xhtml") }
        out.appendLine("notes placed: bofm $bomPlaced, bible ${placed - bomPlaced}; token mismatches $mismatched; links $links, dead page links $deadPages e.g. $dead")
        File(System.getenv("SCRIPTURE_REPORT") ?: "build/scripture-sources-report.txt").writeText(out.toString())
    }

    /** The full import, as the phone runs it, into build/text-import (SCRIPTURE_ZIPS: the downloads folder). */
    @Test
    fun build() {
        val dir = System.getenv("SCRIPTURE_ZIPS")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val index = Catalog.parseIndex(File("src/main/assets/scriptures/index.json").readText())
        val out = File("build/text-import").apply { deleteRecursively(); mkdirs() }
        val started = System.currentTimeMillis()
        val manifest = TextBuild(index).build(dir.listFiles()!!.sortedBy { it.name }, { path, text ->
            File(out, path).apply { parentFile?.mkdirs() }.writeText(text)
        })
        val ms = System.currentTimeMillis() - started
        File(out, "report.txt").writeText("$ms ms\n$manifest\n")
    }

    private fun norm(s: String) = s.lowercase().replace('’', '\'')
    private fun tokens(t: String) = Words.spans(t).map { norm(it.text(t)) }
}
