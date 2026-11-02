package io.github.anweaver23.scriptures.core

import java.io.File
import kotlin.test.Test

/**
 * A report, not a test: sorts real download file names (one per line, in the files named by
 * AUDIO_NAMES_SCRIPTURE and AUDIO_NAMES_HYMNS) the way the importer would. Does nothing without them.
 */
class AudioNamesCheck {
    @Test
    fun report() {
        val scripture = System.getenv("AUDIO_NAMES_SCRIPTURE")?.let(::File)?.takeIf { it.isFile }
        val hymns = System.getenv("AUDIO_NAMES_HYMNS")?.let(::File)?.takeIf { it.isFile }
        if (scripture == null && hymns == null) return
        val index = Catalog.parseIndex(File("src/main/assets/scriptures/index.json").readText())
        val books = Catalog.HYMN_BOOK_FILES.map { Catalog.parseHymnBook(File("src/main/assets/hymns/$it").readText()) }
        val matcher = FileMatcher(Catalog(index, books))
        val out = StringBuilder()
        fun run(kind: UploadKind, names: List<String>) {
            val decisions = ImportPlanner.plan(names.map { IncomingFile(kind, it) }, matcher) { null }
            val matched = decisions.filterIsInstance<ImportDecision.Matched>()
            val review = decisions.filterIsInstance<ImportDecision.NeedsReview>()
            out.appendLine("$kind: ${names.size} files, ${matched.size} matched, ${review.size} to review")
            matched.groupBy { (it.target as? Target.HymnRef)?.hymnBookId ?: (it.target as Target.Chapter).volumeId }
                .forEach { (k, v) -> out.appendLine("  $k: ${v.size}") }
            review.forEach { out.appendLine("  review ${it.reason}: ${it.file.relativePath}") }
            if (kind == UploadKind.ScriptureAudio) {
                val got = matched.map { it.target }.toSet()
                val missing = index.volumes.flatMap { v -> v.books.flatMap { b -> (1..b.chapters).map { Target.Chapter(v.id, b.id, it) } } }
                    .filter { it !in got }
                out.appendLine("  chapters with no file: ${missing.size} ${missing.take(20).map { it.key }}")
                val watch = setOf("scriptures/bofm/morm/", "scriptures/dc-testament/dc/64", "scriptures/dc-testament/dc/1/", "scriptures/dc-testament/dc/2/")
                matched.filter { m -> watch.any { (m.target.key + "/").startsWith(it) } || !m.file.relativePath.contains(Regex("""-\d\d+-female|section-\d+""")) }
                    .forEach { out.appendLine("  ${it.file.relativePath} -> ${it.target.key}") }
            } else {
                matched.filter { (it.target as Target.HymnRef).hymnBookId != Catalog.CHILDRENS_SONGBOOK }
                    .forEach { out.appendLine("  went to another book: ${it.file.relativePath} -> ${it.target.key}") }
            }
        }
        scripture?.let { run(UploadKind.ScriptureAudio, it.readLines().filter(String::isNotBlank)) }
        hymns?.let { run(UploadKind.HymnAudio, it.readLines().filter(String::isNotBlank)) }
        File("build/audio-names-report.txt").writeText(out.toString())
    }
}
