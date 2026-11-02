package io.github.anweaver23.scriptures.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Shapes of the bundled assets under assets/scriptures and assets/hymns.
// Kept free of Android imports so they can be unit tested on the JVM.

@Serializable
data class ScriptureIndex(val volumes: List<Volume>) {
    val books: List<Book> get() = volumes.flatMap { it.books }

    fun volume(id: String): Volume? = volumes.firstOrNull { it.id == id }

    fun book(id: String): Book? = books.firstOrNull { it.id == id }

    fun volumeOf(bookId: String): Volume? = volumes.firstOrNull { v -> v.books.any { it.id == bookId } }
}

@Serializable
data class Volume(val id: String, val title: String, val books: List<Book>)

@Serializable
data class Book(
    val id: String,
    val title: String,
    val abbrev: String,
    val chapters: Int,
    val chapterLabel: String = "Chapter",
)

/** One book's text: c[chapter - 1][verse - 1]. */
@Serializable
data class BookText(val c: List<List<String>>)

@Serializable
data class HymnBook(
    val id: String,
    val title: String,
    val hymns: List<Hymn>,
    val asOf: String? = null,
) {
    fun hymn(number: Int): Hymn? = hymns.firstOrNull { it.n == number }

    /** A hymn by its printed number ("85", "20a"). */
    fun hymn(label: String): Hymn? = hymns.firstOrNull { it.number.equals(label, ignoreCase = true) }
}

/**
 * One hymn or song. [n] is its number, unique in its book; where the book prints a number that isn't
 * a plain integer (the Children's Songbook's "20a" and "20b", two songs on page 20), [label] is what's
 * shown and [n] is an internal page × 1000 + 1 or 2. [files] maps the Church's own download filenames
 * (lowercase) to "vocal" or "accompaniment", for names the matcher couldn't place from the title.
 */
@Serializable
data class Hymn(
    val n: Int,
    val title: String,
    val label: String? = null,
    val files: Map<String, String> = emptyMap(),
) {
    /** The number as the book prints it. */
    val number: String get() = label ?: n.toString()
}

/** Everything the app knows about, loaded once at startup. */
data class Catalog(val scriptures: ScriptureIndex, val hymnBooks: List<HymnBook>) {
    fun hymnBook(id: String): HymnBook? = hymnBooks.firstOrNull { it.id == id }

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        const val HYMNS_1985 = "hymns-1985"
        const val HYMNS_HOME_CHURCH = "hymns-home-church"
        const val CHILDRENS_SONGBOOK = "childrens-songbook"
        val HYMN_BOOK_FILES = listOf("$HYMNS_1985.json", "$HYMNS_HOME_CHURCH.json", "$CHILDRENS_SONGBOOK.json")

        fun parseIndex(text: String): ScriptureIndex = json.decodeFromString(text)
        fun parseBook(text: String): BookText = json.decodeFromString(text)
        fun parseHymnBook(text: String): HymnBook = json.decodeFromString(text)
    }
}
