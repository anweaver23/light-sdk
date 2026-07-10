package com.andyweaver.chess.engine

/**
 * A single move.
 *
 * [from] and [to] are `0..63` square indices. [promotion] is the piece type a
 * pawn promotes to (one of QUEEN/ROOK/BISHOP/KNIGHT) or null. The flags describe
 * special moves and are resolved against a position (e.g. by [fromUci] or the
 * move generator) so that [applyMove] and SAN generation have everything they
 * need without re-deriving it.
 */
data class Move(
    val from: Int,
    val to: Int,
    val promotion: PieceType? = null,
    val isEnPassant: Boolean = false,
    val isCastle: Boolean = false,
    val isDoublePawnPush: Boolean = false,
) {
    /** UCI string, e.g. "e2e4" or "e7e8q". */
    fun toUci(): String {
        val promo = promotion?.let { it.sanLetter.lowercase() } ?: ""
        return Square.name(from) + Square.name(to) + promo
    }

    override fun toString(): String = toUci()

    companion object {
        /**
         * Parse a UCI move string against [position], resolving flags (capture is
         * derived on demand elsewhere; en passant / castle / double-push / promotion
         * are set here). Returns null if the string is malformed or the from-square
         * does not hold a piece of the side to move.
         *
         * This does NOT verify the move is legal — use the move generator for that.
         * It only produces a well-formed [Move] with correct flags.
         */
        fun fromUci(uci: String, position: Position): Move? {
            val s = uci.trim()
            if (s.length !in 4..5) return null
            val from = Square.fromName(s.substring(0, 2)) ?: return null
            val to = Square.fromName(s.substring(2, 4)) ?: return null
            val promotion = if (s.length == 5) {
                when (s[4].lowercaseChar()) {
                    'q' -> PieceType.QUEEN
                    'r' -> PieceType.ROOK
                    'b' -> PieceType.BISHOP
                    'n' -> PieceType.KNIGHT
                    // Antichess/Giveaway allows promoting to a king.
                    'k' -> PieceType.KING
                    else -> return null
                }
            } else null

            val piece = position.pieceAt(from) ?: return null

            val isDoublePush = piece.type == PieceType.PAWN &&
                kotlin.math.abs(Square.rank(to) - Square.rank(from)) == 2
            val isEnPassant = piece.type == PieceType.PAWN &&
                to == position.enPassantTarget &&
                Square.file(to) != Square.file(from)
            val isCastle = piece.type == PieceType.KING &&
                kotlin.math.abs(Square.file(to) - Square.file(from)) == 2

            return Move(
                from = from,
                to = to,
                promotion = promotion,
                isEnPassant = isEnPassant,
                isCastle = isCastle,
                isDoublePawnPush = isDoublePush,
            )
        }
    }
}
