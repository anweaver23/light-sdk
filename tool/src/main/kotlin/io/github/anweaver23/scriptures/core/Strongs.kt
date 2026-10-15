package io.github.anweaver23.scriptures.core

object Strongs {
    private val NUMBER = Regex("""\b([HG])0*(\d{1,5})\b""")

    /**
     * Strong's numbers mentioned in a derivation note ("from H7218; ..."), in order, without
     * duplicates or leading zeros, and never [self].
     */
    fun refsIn(text: String?, self: String? = null): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val selfNorm = self?.let(::normalize)
        return NUMBER.findAll(text)
            .map { "${it.groupValues[1]}${it.groupValues[2].toInt()}" }
            .filter { it != selfNorm }
            .distinct()
            .toList()
    }

    fun normalize(number: String): String =
        NUMBER.matchEntire(number.trim())?.let { "${it.groupValues[1]}${it.groupValues[2].toInt()}" } ?: number.trim()

    /** The two volumes the Strong's data covers. */
    fun covers(volumeId: String): Boolean = volumeId == "ot" || volumeId == "nt"
}
