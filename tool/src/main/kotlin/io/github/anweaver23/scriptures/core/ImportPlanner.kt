package io.github.anweaver23.scriptures.core

/** A file sitting in one of the Tool Manager upload folders. */
data class IncomingFile(
    val kind: UploadKind,
    /** Path relative to the upload folder, e.g. "Book of Mormon/Alma 32.mp3". */
    val relativePath: String,
)

sealed interface ImportDecision {
    val file: IncomingFile

    /** Move the file into the library under [target]. */
    data class Matched(override val file: IncomingFile, val target: Target, val extension: String) : ImportDecision

    /** Leave it in the inbox for the user to sort out on the phone. */
    data class NeedsReview(override val file: IncomingFile, val reason: Reason) : ImportDecision

    /** Not something we can use (cover art, playlists, ...). Left alone, never shown as an error. */
    data class Ignored(override val file: IncomingFile) : ImportDecision

    enum class Reason(val message: String) {
        NoMatch("Couldn't tell what this is"),
        Duplicate("Another file in this upload matched the same item"),
        WrongType("Not a supported file type"),
    }
}

object ImportPlanner {
    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "mp4", "ogg", "oga", "opus", "flac", "wav")
    val LYRICS_EXTENSIONS = setOf("txt", "md")

    /** Files we expect to see next to audio and quietly skip. */
    private val IGNORED_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "m3u", "m3u8", "pls", "nfo", "db", "ini", "json", "xml", "pdf")

    /**
     * Decides what to do with each file. [tagText] supplies embedded title/album
     * metadata for audio files and is only consulted when the name alone doesn't match.
     * A file whose name matches claims its slot first; a match from tags alone only takes a slot no
     * name claims (a foreign-language recording tagged with the English title must not displace the
     * English one).
     */
    fun plan(
        files: List<IncomingFile>,
        matcher: FileMatcher,
        tagText: (IncomingFile) -> String? = { null },
    ): List<ImportDecision> {
        val sorted = files.sortedBy { it.relativePath.lowercase() }
        val byName = sorted.associateWith { file ->
            if (accepts(file)) matcher.match(file.kind, file.relativePath) else null
        }
        val claimed = mutableSetOf<Target>()
        fun decide(file: IncomingFile, target: Target?, extension: String) = when {
            target == null -> ImportDecision.NeedsReview(file, ImportDecision.Reason.NoMatch)
            !claimed.add(target) -> ImportDecision.NeedsReview(file, ImportDecision.Reason.Duplicate)
            else -> ImportDecision.Matched(file, target, extension)
        }
        val named = sorted.filter { byName[it] != null }
            .associateWith { decide(it, byName[it], extensionOf(it)) }
        return sorted.map { file ->
            val name = file.relativePath.substringAfterLast('/')
            val extension = extensionOf(file)
            named[file] ?: when {
                name.startsWith(".") || extension in IGNORED_EXTENSIONS -> ImportDecision.Ignored(file)
                !accepts(file) -> ImportDecision.NeedsReview(file, ImportDecision.Reason.WrongType)
                else -> decide(file, matcher.match(file.kind, file.relativePath, tagText(file)), extension)
            }
        }
    }

    private fun extensionOf(file: IncomingFile) = file.relativePath.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private fun accepts(file: IncomingFile): Boolean {
        val name = file.relativePath.substringAfterLast('/')
        val accepted = if (file.kind == UploadKind.HymnLyrics) LYRICS_EXTENSIONS else AUDIO_EXTENSIONS
        return !name.startsWith(".") && extensionOf(file) in accepted
    }
}
