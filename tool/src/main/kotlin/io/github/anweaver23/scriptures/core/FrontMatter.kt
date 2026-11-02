package io.github.anweaver23.scriptures.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One imported page: a volume's front matter ("bofm/three"), a study help entry ("tg/faith") or a JST
 * passage ("jst/jst-gen/9"), as blocks of linked text. Built by core/importer/VictorParser.
 */
@Serializable
data class FrontPage(
    val id: String,
    val title: String,
    val blocks: List<FrontBlock> = emptyList(),
    val ref: String? = null,
)

/**
 * [t] is "heading" (centred), "p" (reading text), "sig" (a signature line), "entry" (an index line in
 * the Topical Guide), "verse" (numbered [n]) or "subheading". [s], when set, is the block as linked or
 * styled runs and [text] is its plain form.
 */
@Serializable
data class FrontBlock(val t: String, val text: String = "", val s: List<Span> = emptyList(), val n: Int? = null) {
    val spans: List<Span> get() = s.ifEmpty { listOf(Span(text)) }
}
