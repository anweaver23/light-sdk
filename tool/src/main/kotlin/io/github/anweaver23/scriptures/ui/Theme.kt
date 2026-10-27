package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.designVerticalPxToSp
import io.github.anweaver23.scriptures.data.AppGraph

/**
 * Whether the app supplies its own tap haptics when LightOS doesn't. A sideloaded tool isn't
 * Light-signed, so LightOS refuses it a service token, the SDK never learns the user's haptics
 * preference, and `LocalHapticsEnabled` stays false: every tap and key goes silent. Same chain and fix
 * as the chess tool (its `ui/Haptics.kt`). Provided as "LightOS says yes OR this", so a Light-signed
 * build defers to LightOS and never double-buzzes.
 */
const val HAPTICS_FALLBACK = true

/**
 * [LightTheme] with the app's policies: the haptics fallback, and, on [reading] screens only (the
 * reader, the note editor, a note), Display's invert colours (light ⇄ dark). Everything else is
 * "system" UI and follows LightOS. Every screen draws inside this, never [LightTheme] directly.
 */
@Composable
fun AppTheme(reading: Boolean = false, content: @Composable () -> Unit) {
    val system by LightThemeController.colors.collectAsState()
    val display by AppGraph.display.collectAsState()
    val sdkHaptics = LocalHapticsEnabled.current
    val colors = if (reading && display.invertColors) inverted(system) else system
    CompositionLocalProvider(LocalHapticsEnabled provides (sdkHaptics || HAPTICS_FALLBACK)) {
        LightTheme(colors = colors) { content() }
    }
}

fun inverted(colors: LightColors): LightColors =
    if (colors == LightThemeColors.Dark) LightThemeColors.Light else LightThemeColors.Dark

/** The Light style for [variant], at screen scale, exactly as LightText draws it. */
@Composable
fun appTextStyle(variant: LightTextVariant): TextStyle {
    val t = LightThemeTokens.typography
    val base = when (variant) {
        LightTextVariant.Title -> t.title
        LightTextVariant.Subtitle -> t.subtitle
        LightTextVariant.Heading -> t.heading
        LightTextVariant.Subheading -> t.subheading
        LightTextVariant.Copy -> t.copy
        LightTextVariant.Button -> t.button
        LightTextVariant.Paragraph -> t.paragraph
        LightTextVariant.ParagraphWide -> t.paragraphWide
        LightTextVariant.Detail -> t.detail
        LightTextVariant.Fine -> t.fine
        LightTextVariant.Superfine -> t.superfine
        LightTextVariant.Micro -> t.micro
    }
    return base.copy(
        fontSize = base.fontSize.scaled(),
        lineHeight = base.lineHeight.scaled(),
        letterSpacing = base.letterSpacing.scaled(),
    )
}

/**
 * [variant] as reading text (Define, footnotes): sized by the Display text size, with its line height
 * also stretched by line spacing, like scripture.
 */
@Composable
fun readingStyle(variant: LightTextVariant): TextStyle {
    val display by AppGraph.display.collectAsState()
    val base = appTextStyle(variant)
    val size = display.textSize.scale
    return base.copy(
        fontSize = base.fontSize.times(size),
        lineHeight = base.lineHeight.times(size * display.lineSpacing),
        letterSpacing = base.letterSpacing.times(size),
    )
}

private fun TextUnit.times(by: Float): TextUnit = if (this == TextUnit.Unspecified) this else this * by

@Composable
private fun TextUnit.scaled(): TextUnit =
    if (this == TextUnit.Unspecified) this else value.designVerticalPxToSp()

/**
 * [com.thelightphone.sdk.ui.LightText]'s look and arguments, plus styled text ([withScriptFonts]) and a
 * font override. "System" text: fixed at Light's sizes; only reading text follows Display settings.
 */
@Composable
fun AppText(
    text: String,
    variant: LightTextVariant,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    lighten: Boolean = false,
    underline: Boolean = false,
    monospace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    color: Color? = null,
    fontFamily: FontFamily? = null,
    reading: Boolean = false,
) = AppText(AnnotatedString(text), variant, modifier, align, lighten, underline, monospace, maxLines, overflow, color, fontFamily, reading)

/** [AppText] for styled text, e.g. Greek or Hebrew runs in their own font ([withScriptFonts]). */
@Composable
fun AppText(
    text: AnnotatedString,
    variant: LightTextVariant,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    lighten: Boolean = false,
    underline: Boolean = false,
    monospace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    color: Color? = null,
    fontFamily: FontFamily? = null,
    /** Follow the Display text size and line spacing ([readingStyle]); system text never does. */
    reading: Boolean = false,
) {
    val colors = LightThemeTokens.colors
    val style = (if (reading) readingStyle(variant) else appTextStyle(variant)).copy(
        color = color ?: if (lighten) colors.contentSecondary else colors.content,
        textAlign = align ?: TextAlign.Unspecified,
        textDecoration = if (underline) TextDecoration.Underline else null,
    ).let {
        when {
            fontFamily != null -> it.copy(fontFamily = fontFamily)
            monospace -> it.copy(fontFamily = FontFamily.Monospace)
            else -> it
        }
    }
    BasicText(text = text, modifier = modifier, style = style, maxLines = maxLines, overflow = overflow)
}

/**
 * Fonts for scripts LightOS's Akkurat doesn't have: it covers 6 of the 133 Greek characters Strong's
 * uses, no Hebrew points and a few transliteration letters, so the system fallback mixed fonts letter
 * by letter. Roboto (Greek, Latin Extended Additional, modifier letters) and Noto Sans Hebrew are on
 * the LP3 (checked: every character in the bundled Strong's data is covered); elsewhere fall back to
 * the platform sans-serif.
 */
private val GREEK_FONT: FontFamily by lazy { systemFont("Roboto-Regular.ttf") ?: FontFamily.SansSerif }
private val HEBREW_FONT: FontFamily by lazy { systemFont("NotoSansHebrew-Regular.ttf") ?: FontFamily.SansSerif }

private fun systemFont(name: String): FontFamily? =
    java.io.File("/system/fonts/$name").takeIf { it.canRead() }?.let { FontFamily(Font(it)) }

private fun scriptOf(c: Char): FontFamily? = when (c.code) {
    in 0x0590..0x05FF -> HEBREW_FONT
    in 0x0370..0x03FF, in 0x1F00..0x1FFF, // Greek, Greek Extended
    in 0x1E00..0x1EFF, in 0x02B0..0x02FF, in 0x1D00..0x1DBF, // transliteration letters and modifiers
    -> GREEK_FONT
    else -> null
}

/**
 * [text] with each run of Greek, Hebrew or transliteration characters set in a font that has them.
 * Combining marks (U+0300–036F) stay with the run before them. The invisible combining grapheme
 * joiner (U+034F) in some Hebrew lemmas is dropped: no font draws it and it only splits runs.
 */
fun withScriptFonts(text: String): AnnotatedString = buildAnnotatedString {
    val clean = text.replace("\u034F", "")
    append(clean)
    var start = -1
    var font: FontFamily? = null
    fun close(end: Int) {
        if (start >= 0 && font != null) addStyle(SpanStyle(fontFamily = font), start, end)
        start = -1
        font = null
    }
    for ((i, c) in clean.withIndex()) {
        if (c.code in 0x0300..0x036F && start >= 0) continue
        val f = scriptOf(c)
        when {
            f == null -> close(i)
            f != font -> { close(i); start = i; font = f }
        }
    }
    close(clean.length)
}

