package io.github.anweaver23.scriptures.core

/**
 * The reading text size setting. [scale] multiplies the reader's paragraph size and the home
 * tiles' titles. [homeColumns] is how many book tiles share a row on home: larger text gets fewer,
 * larger squares so titles stay readable.
 */
enum class TextSize(val label: String, val scale: Float, val homeColumns: Int) {
    SMALL("Small", 0.85f, 3),
    DEFAULT("Default", 1f, 3),
    LARGE("Large", 1.15f, 2),
    LARGER("Larger", 1.3f, 2),
    LARGEST("Largest", 1.5f, 2);

    companion object {
        /** Stored by name; anything unknown (or missing) reads as [DEFAULT]. */
        fun fromKey(key: String?): TextSize = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
