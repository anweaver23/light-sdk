package io.github.anweaver23.scriptures.core

/**
 * Something a user file can be attached to. The [key] doubles as the file's
 * path inside the audio/lyrics library, so the filesystem is the index.
 */
sealed interface Target {
    val key: String

    data class Chapter(val volumeId: String, val bookId: String, val chapter: Int) : Target {
        override val key: String get() = "scriptures/$volumeId/$bookId/$chapter"
    }

    /**
     * A hymn's audio slot. The plain slot holds the vocal recording (or any unlabelled one);
     * [accompaniment] is the music-only recording, kept alongside so either can be played.
     */
    data class HymnRef(val hymnBookId: String, val number: Int, val accompaniment: Boolean = false) : Target {
        override val key: String get() = "hymns/$hymnBookId/$number" + if (accompaniment) "/$ACCOMPANIMENT" else ""

        /** The hymn itself, without a version. Words, bookmarks and lists use this. */
        val base: HymnRef get() = copy(accompaniment = false)
    }

    companion object {
        private const val ACCOMPANIMENT = "accompaniment"

        /**
         * Whether a hymn audio file is the music-only version, by its name: "accompaniment",
         * "instrumental" or "music only" without "vocal" or "words" ("the_morning_breaks_accompaniment_eng.mp3",
         * "…-instrumental-192k-eng.mp3", "…_women_music_only.mp3"; but "…_vocal_accompaniment_eng.mp3",
         * "…-vocal-and-instrumental-…" and "…_words_and_music.mp3" are vocal).
         */
        fun isAccompanimentOnly(fileName: String): Boolean {
            val tokens = fileName.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            val words = tokens.toSet()
            val spaced = tokens.joinToString(" ", " ", " ")
            val musicOnly = "accompaniment" in words || "instrumental" in words || " music only " in spaced
            // "words and music" as a phrase: titles have "words" too ("Let Us Oft Speak Kind Words").
            return musicOnly && "vocal" !in words && " words and music " !in spaced
        }

        fun fromKey(key: String): Target? {
            val parts = key.split('/')
            return when {
                parts.size == 4 && parts[0] == "scriptures" ->
                    parts[3].toIntOrNull()?.let { Chapter(parts[1], parts[2], it) }
                parts.size == 3 && parts[0] == "hymns" ->
                    parts[2].toIntOrNull()?.let { HymnRef(parts[1], it) }
                parts.size == 4 && parts[0] == "hymns" && parts[3] == ACCOMPANIMENT ->
                    parts[2].toIntOrNull()?.let { HymnRef(parts[1], it, accompaniment = true) }
                else -> null
            }
        }
    }
}

/**
 * Imported pages (a Topical Guide entry, front matter, a JST passage) read in the reader as one-chapter
 * "books" of the volume [VOLUME], each block a "verse", so bookmarks, highlights, notes and find work
 * on them as on scripture. The page id's slashes become "~" so the target's key still splits in four:
 * "tg/faith" is `Chapter("page", "tg~faith", 1)`, key "scriptures/page/tg~faith/1".
 */
object PageRefs {
    const val VOLUME = "page"

    /** A page's title by id; set once the imported page index is available. */
    @Volatile
    var titleOf: (String) -> String? = { null }

    fun chapter(pageId: String) = Target.Chapter(VOLUME, pageId.replace('/', '~'), 1)

    fun title(pageId: String): String = titleOf(pageId) ?: pageId.substringAfterLast('/')
}

/** Whether this "chapter" is an imported page (see [PageRefs]). */
val Target.Chapter.isPage: Boolean get() = volumeId == PageRefs.VOLUME

/** The page id of a page "chapter", or null for scripture. */
val Target.Chapter.pageId: String? get() = if (isPage) bookId.replace('~', '/') else null

fun Catalog.label(target: Target): String = when (target) {
    is Target.Chapter -> target.pageId?.let(PageRefs::title) ?: run {
        val book = scriptures.book(target.bookId)
        when {
            book == null -> target.key
            book.id == "dc" -> "D&C ${target.chapter}"
            book.chapters == 1 -> book.title
            else -> "${book.title} ${target.chapter}"
        }
    }
    is Target.HymnRef -> {
        val title = hymnBook(target.hymnBookId)?.hymn(target.number)?.title
        val name = if (title == null) "Hymn ${target.number}" else "${target.number}. $title"
        if (target.accompaniment) "$name (accompaniment)" else name
    }
}
