package io.github.anweaver23.scriptures.ui

import io.github.anweaver23.scriptures.core.PageRefs
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.thelightphone.sdk.SimpleLightScreen
import io.github.anweaver23.scriptures.core.LinkTarget
import io.github.anweaver23.scriptures.core.Span

/** Small caps drawn as capitals at this size, for fonts without a small-caps face. */
const val SMALL_CAPS_EM = 0.8f

/**
 * Imported runs as styled text: "i" italic, "b" bold, "sc" small caps; runs with a target are
 * underlined links that call [onLink].
 */
fun annotated(spans: List<Span>, onLink: ((String) -> Unit)?): AnnotatedString = buildAnnotatedString {
    // A range tail ("–6" after "Alma 32:5") taken into the link before it, and left out of its own run.
    var skip = 0
    spans.forEachIndexed { i, s ->
        val style = when (s.s) {
            "i" -> SpanStyle(fontStyle = FontStyle.Italic)
            "b" -> SpanStyle(fontWeight = FontWeight.Bold)
            "sc" -> SpanStyle(fontSize = SMALL_CAPS_EM.em)
            else -> null
        }
        val raw = if (s.s == "sc") s.t.uppercase() else s.t
        var text = raw.substring(skip.coerceAtMost(raw.length))
        skip = 0
        var to = s.to
        // A verse link followed by "–6": link the whole range, so following it marks verses 5 to 6.
        val next = spans.getOrNull(i + 1)
        val first = to?.let(LinkTarget::verse)
        if (to != null && first != null && next != null && next.to == null && next.s == s.s) {
            LinkTarget.rangeTail(next.t, first.verse)?.let { (last, length) ->
                text += next.t.substring(0, length)
                to = "$to-$last"
                skip = length
            }
        }
        fun body() = if (style != null) withStyle(style) { append(text) } else append(text)
        val target = to
        if (target != null && onLink != null) {
            withLink(
                LinkAnnotation.Clickable(
                    tag = target,
                    styles = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)),
                ) { onLink(target) },
            ) { body() }
        } else {
            body()
        }
    }
}

/** [spans] in [style], links tappable. */
@Composable
fun SpanText(spans: List<Span>, style: TextStyle, modifier: Modifier = Modifier, onLink: ((String) -> Unit)? = null) {
    val text = remember(spans, onLink != null) { annotated(spans, onLink) }
    BasicText(text = text, style = style, modifier = modifier)
}

/**
 * Follows a link from imported text: a verse or range of verses opens the reader with them marked
 * until the chapter changes (going back to a reader already open, see [Nav]); a chapter opens the
 * reader; anything else is a page (Topical Guide entry, front matter, JST passage), read in the reader
 * like a chapter (see [PageRefs]).
 */
fun SimpleLightScreen<*>.openLink(to: String) {
    LinkTarget.verses(to)?.let { (first, last) -> return openReader(first.chapterTarget, first.verse, through = last, hold = true) }
    LinkTarget.chapter(to)?.let { return openReader(it) }
    openReader(PageRefs.chapter(to))
}
