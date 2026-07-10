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
    /** Crazyhouse drop: the piece type dropped from the pocket onto [to] ([from] == [to]). */
    val drop: PieceType? = null,
) {
    /** True if this is a Crazyhouse piece drop rather than a board move. */
    val isDrop: Boolean get() = drop != null

    /** UCI string, e.g. "e2e4", "e7e8q", or a drop "N@f3". */
    fun toUci(): String {
        if (drop != null) {
            val letter = if (drop == PieceType.PAWN) "P" else drop.sanLetter
            return "$letter@${Square.name(to)}"
        }
        val promo = promotion?.sanLetter?.lowercase() ?: ""
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
         * Handles the two variant notations Lichess emits: Crazyhouse drops ("N@f3",
         * "P@e4") and Chess960 castling written as king-onto-own-rook (the [to] square
         * holds the friendly rook rather than being two files away).
         *
         * This does NOT verify the move is legal — use the move generator for that.
         * It only produces a well-formed [Move] with correct flags.
         */
        fun fromUci(uci: String, position: Position): Move? {
            val s = uci.trim()

            // Crazyhouse drop: "<Piece>@<square>" (piece letter blank/'P' for a pawn).
            val at = s.indexOf('@')
            if (at >= 0) {
                val typeChar = if (at == 0) 'P' else s[at - 1]
                val dropType = when (typeChar.uppercaseChar()) {
                    'P' -> PieceType.PAWN
                    'N' -> PieceType.KNIGHT
                    'B' -> PieceType.BISHOP
                    'R' -> PieceType.ROOK
                    'Q' -> PieceType.QUEEN
                    else -> return null
                }
                val to = Square.fromName(s.substring(at + 1)) ?: return null
                return Move(from = to, to = to, drop = dropType)
            }

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
            // Standard: king moves two files. Chess960: king moves onto its own rook.
            val destPiece = position.pieceAt(to)
            val isCastle = piece.type == PieceType.KING && (
                kotlin.math.abs(Square.file(to) - Square.file(from)) == 2 ||
                    (destPiece != null && destPiece.color == piece.color && destPiece.type == PieceType.ROOK)
                )

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
