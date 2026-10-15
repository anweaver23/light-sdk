package io.github.anweaver23.scriptures.core

/** One verse, e.g. Alma 32:21 = VerseRef("bofm", "alma", 32, 21). Chapter and verse are 1-based. */
data class VerseRef(val volumeId: String, val bookId: String, val chapter: Int, val verse: Int) {
    val chapterTarget: Target.Chapter get() = Target.Chapter(volumeId, bookId, chapter)
    val key: String get() = "$volumeId/$bookId/$chapter/$verse"

    companion object {
        fun fromKey(key: String): VerseRef? {
            val p = key.split('/')
            if (p.size != 4) return null
            val chapter = p[2].toIntOrNull() ?: return null
            val verse = p[3].toIntOrNull() ?: return null
            return VerseRef(p[0], p[1], chapter, verse)
        }
    }
}

/** An English dictionary entry (Webster's 1828). */
data class Definition(
    val headword: String,
    /** Part of speech as printed, e.g. "n." or "v.t.", if the source gives one. */
    val partOfSpeech: String?,
    /** Numbered senses in source order. */
    val senses: List<String>,
)

/** Secular English dictionary. Lookups are case-insensitive and may fall back to a base form. */
interface Dictionary {
    /** Entries for [word], best match first. Empty when nothing matches. */
    fun define(word: String): List<Definition>
}

/** The Hebrew or Greek word behind an English word in the KJV, from Strong's. */
data class OriginalWord(
    /** Strong's number with language prefix: "H7225" or "G3056". */
    val strongs: String,
    /** The word in its original script, e.g. "רֵאשִׁית" or "λόγος". */
    val lemma: String,
    val transliteration: String,
    val pronunciation: String?,
    /** Strong's definition text. */
    val definition: String,
    /** Strong's derivation/root note, e.g. "from H7218". Root numbers can be looked up with [OriginalLanguage.entry]. */
    val derivation: String?,
    /** How the KJV renders this word elsewhere, if available. */
    val kjvUsage: String?,
)

/** Maps words in the bundled Bible text to their original-language words. Only "ot" and "nt" have data. */
interface OriginalLanguage {
    /**
     * Original words behind the word at [wordIndex] (as numbered by [Words.spans]) in [ref].
     * Usually one; can be several when one English word renders a phrase. Empty when untagged
     * (function words like "the", words supplied by the translators, or non-Bible volumes).
     */
    fun wordsAt(ref: VerseRef, wordIndex: Int): List<OriginalWord>

    /** A Strong's entry by number, for following derivation links. */
    fun entry(strongs: String): OriginalWord?
}

object NoDictionary : Dictionary {
    override fun define(word: String): List<Definition> = emptyList()
}

object NoOriginalLanguage : OriginalLanguage {
    override fun wordsAt(ref: VerseRef, wordIndex: Int): List<OriginalWord> = emptyList()
    override fun entry(strongs: String): OriginalWord? = null
}
