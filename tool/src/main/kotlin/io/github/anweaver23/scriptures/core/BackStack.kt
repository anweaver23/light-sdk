package io.github.anweaver23.scriptures.core

/** Back-stack arithmetic for closing navigation loops (see ui/Nav). */
object BackStack {
    /**
     * How many screens to pop from the top of [keys] (bottom first) to reach the newest one keyed
     * [key]: 0 when it's already on top, null when it isn't open.
     */
    fun popsTo(keys: List<Any?>, key: Any): Int? {
        val i = keys.indexOfLast { it == key }
        return if (i < 0) null else keys.lastIndex - i
    }
}
