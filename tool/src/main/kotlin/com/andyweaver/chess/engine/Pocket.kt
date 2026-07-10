package com.andyweaver.chess.engine

/**
 * Crazyhouse reserves: how many of each piece type each side currently holds and
 * may drop onto the board. Kings are never pocketed. A captured *promoted* pawn is
 * added back as a [PieceType.PAWN] (the caller passes the base type), matching the
 * Crazyhouse rule.
 *
 * Immutable, like [Position]; [add]/[remove] return a new [Pocket].
 */
data class Pocket(
    val white: Map<PieceType, Int> = emptyMap(),
    val black: Map<PieceType, Int> = emptyMap(),
) {
    fun forColor(color: Color): Map<PieceType, Int> = if (color == Color.WHITE) white else black

    /** Count of [type] held by [color]. */
    fun count(color: Color, type: PieceType): Int = forColor(color)[type] ?: 0

    /** Distinct droppable piece types held by [color], in a stable display order. */
    fun types(color: Color): List<PieceType> =
        DROP_ORDER.filter { (forColor(color)[it] ?: 0) > 0 }

    fun add(color: Color, type: PieceType): Pocket {
        val m = forColor(color).toMutableMap()
        m[type] = (m[type] ?: 0) + 1
        return if (color == Color.WHITE) copy(white = m) else copy(black = m)
    }

    fun remove(color: Color, type: PieceType): Pocket {
        val m = forColor(color).toMutableMap()
        val next = (m[type] ?: 0) - 1
        if (next <= 0) m.remove(type) else m[type] = next
        return if (color == Color.WHITE) copy(white = m) else copy(black = m)
    }

    val isEmpty: Boolean get() = white.isEmpty() && black.isEmpty()

    companion object {
        val EMPTY = Pocket()

        // Queen-first display order (most valuable first), pawns last.
        private val DROP_ORDER = listOf(
            PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT, PieceType.PAWN,
        )
    }
}
