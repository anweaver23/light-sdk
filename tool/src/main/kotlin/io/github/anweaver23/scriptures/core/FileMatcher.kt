package io.github.anweaver23.scriptures.core

/**
 * Works out which chapter or hymn an uploaded file belongs to from its name,
 * its folder names, and (as a fallback) its embedded title/album tags.
 *
 * Everything is reduced to lowercase word/number tokens first, so "Alma_032_eng",
 * "Alma 32", "alma32" and "BofM/Alma/032" all look alike to the matcher.
 */
class FileMatcher(private val catalog: Catalog) {

    private data class Alias(val book: Book, val volumeId: String, val tokens: List<String>)

    private val aliases: List<Alias> = catalog.scriptures.volumes.flatMap { volume ->
        volume.books.flatMap { book ->
            bookAliases(book).map { Alias(book, volume.id, it) }
        }
    }

    private data class TitleAlias(val ref: Target.HymnRef, val tokens: List<String>)

    private val allTitles: List<TitleAlias> = catalog.hymnBooks.flatMap { hymnBook ->
        hymnBook.hymns.map { TitleAlias(Target.HymnRef(hymnBook.id, it.n), tokenize(it.title)) }
    }

    // One-word titles are too easy to hit by accident inside a longer filename.
    private val hymnTitles: List<TitleAlias> = allTitles.filter { it.tokens.size >= 2 }

    fun match(kind: UploadKind, relativePath: String, tagText: String? = null): Target? = when (kind) {
        UploadKind.ScriptureAudio -> matchScripture(relativePath, tagText)
        UploadKind.HymnAudio, UploadKind.HymnLyrics -> matchHymn(relativePath, tagText)
    }

    fun matchScripture(relativePath: String, tagText: String? = null): Target.Chapter? {
        val (folders, fileName) = splitPath(relativePath)
        val fileTokens = tokenize(fileName)

        findChapter(fileTokens)?.let { return it }

        // "Alma/032.mp3": the book is in a folder name, the chapter in the file name.
        for (folder in folders.asReversed()) {
            val folderAlias = bestBookAlias(tokenize(folder)) ?: continue
            val number = fileTokens.numbers().lastOrNull { it in 1..folderAlias.book.chapters }
            if (number != null) return Target.Chapter(folderAlias.volumeId, folderAlias.book.id, number)
            if (folderAlias.book.chapters == 1) return Target.Chapter(folderAlias.volumeId, folderAlias.book.id, 1)
        }

        if (!tagText.isNullOrBlank()) findChapter(tokenize(tagText))?.let { return it }
        return null
    }

    fun matchHymn(relativePath: String, tagText: String? = null): Target.HymnRef? {
        val (folders, fileName) = splitPath(relativePath)
        val fileTokens = tokenize(fileName)
        val preferredBook = folders.asReversed().firstNotNullOfOrNull { hymnBookHint(tokenize(it)) }
            ?: hymnBookHint(fileTokens)

        findHymn(fileTokens, preferredBook)?.let { return it }
        if (!tagText.isNullOrBlank()) findHymn(tokenize(tagText), preferredBook)?.let { return it }
        return null
    }

    // ---- scriptures ----

    private data class Hit(val alias: Alias, val start: Int)

    private fun hits(tokens: List<String>): List<Hit> {
        val result = mutableListOf<Hit>()
        for (alias in aliases) {
            val n = alias.tokens.size
            for (start in 0..tokens.size - n) {
                if (tokens.subList(start, start + n) == alias.tokens) result += Hit(alias, start)
            }
        }
        return result
    }

    private fun bestBookAlias(tokens: List<String>): Alias? =
        hits(tokens).maxWithOrNull(compareBy({ it.alias.tokens.size }, { it.start }))?.alias

    private fun findChapter(tokens: List<String>): Target.Chapter? {
        data class Candidate(val target: Target.Chapter, val adjacent: Boolean, val length: Int, val start: Int)

        val candidates = hits(tokens).mapNotNull { hit ->
            val book = hit.alias.book
            var i = hit.start + hit.alias.tokens.size
            while (i < tokens.size && tokens[i] in FILLER) i++
            val adjacent = tokens.getOrNull(i)?.toIntOrNull()
            // A single-chapter book with no number right after it is chapter 1; any later
            // number is noise like the "64" in "joseph-smith-history-male-voice-64k".
            val chapter = adjacent ?: if (book.chapters == 1) null else tokens.drop(i).numbers().firstOrNull()
            when {
                chapter != null && chapter in 1..book.chapters ->
                    Candidate(Target.Chapter(hit.alias.volumeId, book.id, chapter), adjacent != null, hit.alias.tokens.size, hit.start)
                // Single-chapter books (Enos, Jude, JS—History...) often have no number at all.
                chapter == null && book.chapters == 1 ->
                    Candidate(Target.Chapter(hit.alias.volumeId, book.id, 1), false, hit.alias.tokens.size, hit.start)
                else -> null
            }
        }
        return candidates
            .maxWithOrNull(compareBy({ it.adjacent }, { it.length }, { it.start }))
            ?.target
    }

    // ---- hymns ----

    private fun hymnBookHint(tokens: List<String>): String? {
        val text = tokens.joinToString(" ")
        return when {
            "home and church" in text || "home church" in text -> Catalog.HYMNS_HOME_CHURCH
            "1985" in tokens -> Catalog.HYMNS_1985
            else -> null
        }
    }

    private fun findHymn(tokens: List<String>, preferredBook: String?): Target.HymnRef? {
        // Older Church downloads: "2001-01-0300-come-come-ye-saints-..." is 1985 hymn 30.
        val legacy = tokens.windowed(3).firstOrNull { it[0] == "2001" && it[1] == "1" }
        legacy?.get(2)?.toIntOrNull()?.takeIf { it % 10 == 0 }?.let { n ->
            catalog.hymnBook(Catalog.HYMNS_1985)?.hymn(n / 10)?.let { return Target.HymnRef(Catalog.HYMNS_1985, it.n) }
        }

        // Church downloads are "<title_slug>[_variant]_eng.mp3", so once the variant words are
        // stripped the name is the title, even for one-word titles.
        val core = tokens.withoutVariantWords()
        allTitles.filter { core.isNotEmpty() && it.tokens.withoutVariantWords() == core }
            .maxWithOrNull(compareBy { it.ref.hymnBookId == preferredBook })
            ?.let { return it.ref }

        val numbers = tokens.numbers().toSet()

        // A title in the name is the strongest signal. Prefer the longest title,
        // then one whose number also appears, then the hinted hymnbook.
        val titleHits = hymnTitles.filter { title ->
            val n = title.tokens.size
            (0..tokens.size - n).any { tokens.subList(it, it + n) == title.tokens }
        }
        titleHits.maxWithOrNull(
            compareBy<TitleAlias>(
                { it.tokens.size },
                { it.ref.number in numbers },
                { it.ref.hymnBookId == preferredBook },
            )
        )?.let { return it.ref }

        // "Hymn 85", "No. 85", "#85"
        tokens.forEachIndexed { i, token ->
            if (token in HYMN_NUMBER_WORDS) {
                tokens.getOrNull(i + 1)?.toIntOrNull()?.let { n -> hymnByNumber(n, preferredBook)?.let { return it } }
            }
        }

        // Otherwise accept a bare number only when exactly one number in the name is a real hymn.
        val valid = tokens.numbers().distinct().mapNotNull { hymnByNumber(it, preferredBook) }
        return valid.singleOrNull()
    }

    private fun hymnByNumber(number: Int, preferredBook: String?): Target.HymnRef? {
        // The new hymnbook numbers from 1000 up, so the number alone usually says which book.
        val books = catalog.hymnBooks.filter { it.hymn(number) != null }
        val book = books.firstOrNull { it.id == preferredBook } ?: books.firstOrNull() ?: return null
        return Target.HymnRef(book.id, number)
    }

    companion object {
        private val AUDIO_OR_TEXT_EXTENSION = Regex("\\.[A-Za-z0-9]{1,5}$")

        private val FILLER = setOf("chapter", "chap", "ch", "section", "sec", "psalm", "no", "number")
        /** Words the Church adds to hymn downloads to name the arrangement. */
        private val HYMN_VARIANT_WORDS = setOf(
            "accompaniment", "vocal", "instrumental", "eng", "k", "music", "only", "words", "and",
            "guitar", "congregational", "childrens", "men", "mens", "women", "womens", "choir",
        )
        private val HYMN_NUMBER_WORDS = setOf("hymn", "hymns", "no", "number", "num", "n")

        /** Extra names people use that aren't the title, abbreviation or URL slug. */
        private val EXTRA_ALIASES = mapOf(
            "ps" to listOf("psalm"),
            "song" to listOf("song of songs", "solomons song", "canticles"),
            "dc" to listOf("dc", "dandc", "d c", "section", "sec"),
            "js-h" to listOf("jsh", "js history", "joseph smith history"),
            "js-m" to listOf("jsm", "js matthew", "joseph smith matthew"),
            "a-of-f" to listOf("aof", "articles of faith"),
            "w-of-m" to listOf("wom", "words of mormon"),
            "abr" to listOf("abraham"),
        )

        private val ORDINALS = mapOf(
            "1" to listOf("first", "i", "1st"),
            "2" to listOf("second", "ii", "2nd"),
            "3" to listOf("third", "iii", "3rd"),
            "4" to listOf("fourth", "iv", "4th"),
        )

        internal fun bookAliases(book: Book): Set<List<String>> {
            val names = mutableSetOf(book.id, book.title, book.abbrev)
            names += EXTRA_ALIASES[book.id].orEmpty()
            val tokenLists = names.map(::tokenize).filter { it.isNotEmpty() }.toMutableSet()
            // "1 Nephi" -> "First Nephi", "I Nephi", "1st Nephi"
            for (tokens in tokenLists.toList()) {
                val variants = ORDINALS[tokens.first()] ?: continue
                variants.forEach { tokenLists += listOf(it) + tokens.drop(1) }
            }
            return tokenLists
        }

        /** Lowercase words and numbers. Numbers lose leading zeros; letters and digits are split apart. */
        fun tokenize(text: String): List<String> {
            val spaced = text.lowercase()
                .replace("&", " and ")
                .replace("'", "")
                .replace("’", "")
                .replace(Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])"), " ")
            return spaced.split(Regex("[^a-z0-9]+"))
                .filter { it.isNotEmpty() }
                .map { token -> token.toIntOrNull()?.toString() ?: token }
        }

        internal fun splitPath(relativePath: String): Pair<List<String>, String> {
            val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            val fileName = parts.lastOrNull().orEmpty().replace(AUDIO_OR_TEXT_EXTENSION, "")
            return parts.dropLast(1) to fileName
        }

        private fun List<String>.withoutVariantWords() =
            filterNot { it in HYMN_VARIANT_WORDS || it.toIntOrNull() != null }

        private fun List<String>.numbers(): List<Int> = mapNotNull { it.toIntOrNull() }
    }
}

/** Which Tool Manager upload folder a file came in through. */
enum class UploadKind(val folder: String, val label: String) {
    ScriptureAudio("scripture-audio", "Scripture audio"),
    HymnAudio("hymn-audio", "Hymn audio"),
    HymnLyrics("hymn-lyrics", "Hymn lyrics"),
}
