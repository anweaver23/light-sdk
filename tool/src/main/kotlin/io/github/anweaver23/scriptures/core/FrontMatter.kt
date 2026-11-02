package io.github.anweaver23.scriptures.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pages before a volume's first book (the Book of Mormon's Title Page and Witnesses), from
 * `assets/scriptures/<volumeId>/front.json` (built by tool/scripts/build_front_matter.py). A page is
 * either text [blocks] or a link to a verse ([ref], a verse key) that opens the reader there.
 */
@Serializable
data class FrontMatter(val pages: List<FrontPage> = emptyList()) {
    companion object {
        val EMPTY = FrontMatter()
        private val json = Json { ignoreUnknownKeys = true }
        fun path(volumeId: String) = "scriptures/$volumeId/front.json"
        fun parse(text: String): FrontMatter = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class FrontPage(
    val id: String,
    val title: String,
    val blocks: List<FrontBlock> = emptyList(),
    val ref: String? = null,
) {
    val verseRef: VerseRef? get() = ref?.let(VerseRef::fromKey)
}

/** [t] is "heading" (centred), "p" (reading text) or "sig" (a signature line). */
@Serializable
data class FrontBlock(val t: String, val text: String)
