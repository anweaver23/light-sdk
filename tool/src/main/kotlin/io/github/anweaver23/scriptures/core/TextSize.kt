package io.github.anweaver23.scriptures.core

/** The reading text size setting: [scale] multiplies scripture and note text (never system UI). */
enum class TextSize(val label: String, val scale: Float) {
    SMALL("Small", 0.85f),
    DEFAULT("Default", 1f),
    LARGE("Large", 1.15f),
    LARGER("Larger", 1.3f),
    LARGEST("Largest", 1.5f);

    companion object {
        /** Stored by name; anything unknown (or missing) reads as [DEFAULT]. */
        fun fromKey(key: String?): TextSize = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
