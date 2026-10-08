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

    data class HymnRef(val hymnBookId: String, val number: Int) : Target {
        override val key: String get() = "hymns/$hymnBookId/$number"
    }

    companion object {
        fun fromKey(key: String): Target? {
            val parts = key.split('/')
            return when {
                parts.size == 4 && parts[0] == "scriptures" ->
                    parts[3].toIntOrNull()?.let { Chapter(parts[1], parts[2], it) }
                parts.size == 3 && parts[0] == "hymns" ->
                    parts[2].toIntOrNull()?.let { HymnRef(parts[1], it) }
                else -> null
            }
        }
    }
}

fun Catalog.label(target: Target): String = when (target) {
    is Target.Chapter -> {
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
        if (title == null) "Hymn ${target.number}" else "${target.number}. $title"
    }
}
