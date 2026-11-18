package io.github.anweaver23.scriptures.core

/**
 * The Display settings: text size, line spacing and inverted colours. Size and spacing apply to the
 * app's own content (reader, lists, notes, study screens); never to the top bar, bottom bar or menus.
 */
data class DisplaySettings(
    val textSize: TextSize = TextSize.DEFAULT,
    val lineSpacing: Float = LineSpacing.DEFAULT,
    val invertColors: Boolean = false,
)

/** How home shows the volumes and Hymns: square tiles (the default) or a plain list. */
enum class HomeLayout(val label: String) {
    Tiles("Tiles"),
    List("List"),
    ;

    companion object {
        fun fromKey(key: String?): HomeLayout = entries.firstOrNull { it.name == key } ?: Tiles
    }
}

/**
 * Playback: whether it rolls on to the next chapter / hymn when one ends (both on by default), the
 * speed scripture chapters play at (hymns always play at 1×), and whether the reader scrolls along
 * with a chapter as it plays.
 */
data class PlaybackSettings(
    val autoplayChapters: Boolean = true,
    val autoplayHymns: Boolean = true,
    val scriptureSpeed: Float = PlaybackSpeed.DEFAULT,
    val autoScroll: Boolean = true,
)

/** Scripture playback speeds, picked from a list. */
object PlaybackSpeed {
    const val DEFAULT = 1f
    val STEPS: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** The step nearest [value], so stored values always land on one. */
    fun snap(value: Float): Float = STEPS.minBy { kotlin.math.abs(it - value) }

    /** "1×", "1.25×". */
    fun label(value: Float): String =
        (if (value == value.toInt().toFloat()) value.toInt().toString() else "%.2f".format(value).trimEnd('0')) + "×"
}

/** Line spacing is a multiplier on each text style's own line height, chosen on a slider in steps. */
object LineSpacing {
    const val DEFAULT = 1f
    val STEPS: List<Float> = listOf(0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.8f, 2f)

    /** The step nearest [value], so stored or dragged values always land on one. */
    fun snap(value: Float): Float = STEPS.minBy { kotlin.math.abs(it - value) }

    /** "1.2×". */
    fun label(value: Float): String = "${"%.1f".format(value)}×"
}
