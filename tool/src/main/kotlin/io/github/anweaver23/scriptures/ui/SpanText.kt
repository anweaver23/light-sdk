package io.github.anweaver23.scriptures.ui

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
    for (s in spans) {
        val style = when (s.s) {
            "i" -> SpanStyle(fontStyle = FontStyle.Italic)
            "b" -> SpanStyle(fontWeight = FontWeight.Bold)
            "sc" -> SpanStyle(fontSize = SMALL_CAPS_EM.em)
            else -> null
        }
        val text = if (s.s == "sc") s.t.uppercase() else s.t
        fun body() = if (style != null) withStyle(style) { append(text) } else append(text)
        val to = s.to
        if (to != null && onLink != null) {
            withLink(
                LinkAnnotation.Clickable(
                    tag = to,
                    styles = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)),
                ) { onLink(to) },
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
 * Follows a link from imported text: a verse or chapter opens the reader (going back to one already
 * open, see [Nav]); anything else is a page (Topical Guide entry, front matter, JST passage).
 */
fun SimpleLightScreen<*>.openLink(to: String) {
    LinkTarget.verse(to)?.let { return openReader(it.chapterTarget, it.verse) }
    LinkTarget.chapter(to)?.let { return openReader(it) }
    open(NavKeys.page(to), { PageScreen(it, to) })
}
