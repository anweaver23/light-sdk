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

/** Line spacing is a multiplier on each text style's own line height, chosen on a slider in steps. */
object LineSpacing {
    const val DEFAULT = 1f
    val STEPS: List<Float> = listOf(0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.8f, 2f)

    /** The step nearest [value], so stored or dragged values always land on one. */
    fun snap(value: Float): Float = STEPS.minBy { kotlin.math.abs(it - value) }

    /** "1.2×". */
    fun label(value: Float): String = "${"%.1f".format(value)}×"
}
