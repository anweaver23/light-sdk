package com.andyweaver.chess.engine

/** Castling rights. Each flag is whether that side may still castle in that direction. */
data class CastlingRights(
    val whiteKingSide: Boolean = false,
    val whiteQueenSide: Boolean = false,
    val blackKingSide: Boolean = false,
    val blackQueenSide: Boolean = false,
) {
    /** FEN field, e.g. "KQkq", or "-" if none. */
    fun toFen(): String {
        val sb = StringBuilder()
        if (whiteKingSide) sb.append('K')
        if (whiteQueenSide) sb.append('Q')
        if (blackKingSide) sb.append('k')
        if (blackQueenSide) sb.append('q')
        return if (sb.isEmpty()) "-" else sb.toString()
    }

    companion object {
        val NONE = CastlingRights()

        fun fromFen(field: String): CastlingRights =
            CastlingRights(
                whiteKingSide = field.contains('K'),
                whiteQueenSide = field.contains('Q'),
                blackKingSide = field.contains('k'),
                blackQueenSide = field.contains('q'),
            )
    }
}

/**
 * An immutable chess position. [board] is a 64-entry list indexed as described
 * in [Square] (a1 = 0 ... h8 = 63); null means an empty square.
 *
 * All state needed to reconstruct a FEN and to generate legal moves lives here.
 * Instances are never mutated; [applyMove] returns a new [Position].
 */
data class Position(
    val board: List<Piece?>,
    val sideToMove: Color,
    val castlingRights: CastlingRights,
    val enPassantTarget: Int?,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
) {
    init {
        require(board.size == Square.COUNT) { "board must have ${Square.COUNT} squares, got ${board.size}" }
    }

    fun pieceAt(square: Int): Piece? = board[square]

    /** Square index of [color]'s king, or -1 if (illegally) absent. */
    fun kingSquare(color: Color): Int {
        for (sq in 0 until Square.COUNT) {
            val p = board[sq]
            if (p != null && p.color == color && p.type == PieceType.KING) return sq
        }
        return -1
    }

    /** Serialize to a full FEN string (all six fields). */
    fun toFen(): String {
        val sb = StringBuilder()
        // Piece placement is written rank 8 down to rank 1, files a..h.
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = board[Square.of(file, rank)]
                if (p == null) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(p.fenChar)
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        sb.append(' ')
        sb.append(if (sideToMove == Color.WHITE) 'w' else 'b')
        sb.append(' ')
        sb.append(castlingRights.toFen())
        sb.append(' ')
        sb.append(enPassantTarget?.let { Square.name(it) } ?: "-")
        sb.append(' ')
        sb.append(halfmoveClock)
        sb.append(' ')
        sb.append(fullmoveNumber)
        return sb.toString()
    }

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        /** The standard chess starting position. */
        val START: Position = fromFen(START_FEN)

        /**
         * Parse a FEN string. Accepts the standard 6-field FEN; the halfmove clock
         * and fullmove number are optional (default 0 and 1) so that partial FENs
         * still parse. Throws [IllegalArgumentException] on malformed input.
         */
        fun fromFen(fen: String): Position {
            val fields = fen.trim().split(Regex("\\s+"))
            require(fields.size >= 4) { "FEN must have at least 4 fields: '$fen'" }

            val placement = fields[0]
            val board = arrayOfNulls<Piece>(Square.COUNT)
            val ranks = placement.split('/')
            require(ranks.size == 8) { "FEN placement must have 8 ranks: '$placement'" }
            // First rank string is rank 8.
            for ((i, rankStr) in ranks.withIndex()) {
                val rank = 7 - i
                var file = 0
                for (c in rankStr) {
                    if (c.isDigit()) {
                        file += c - '0'
                    } else {
                        val piece = Piece.fromFenChar(c)
                            ?: throw IllegalArgumentException("Invalid FEN piece '$c' in '$placement'")
                        require(file in 0..7) { "FEN rank overflows: '$rankStr'" }
                        board[Square.of(file, rank)] = piece
                        file++
                    }
                }
                require(file == 8) { "FEN rank '$rankStr' does not fill 8 files" }
            }

            val sideToMove = when (fields[1]) {
                "w" -> Color.WHITE
                "b" -> Color.BLACK
                else -> throw IllegalArgumentException("Invalid side to move: '${fields[1]}'")
            }

            val castling = CastlingRights.fromFen(fields[2])

            val enPassant = if (fields[3] == "-") null else {
                Square.fromName(fields[3])
                    ?: throw IllegalArgumentException("Invalid en passant square: '${fields[3]}'")
            }

            val halfmove = fields.getOrNull(4)?.toIntOrNull() ?: 0
            val fullmove = fields.getOrNull(5)?.toIntOrNull() ?: 1

            return Position(
                board = board.toList(),
                sideToMove = sideToMove,
                castlingRights = castling,
                enPassantTarget = enPassant,
                halfmoveClock = halfmove,
                fullmoveNumber = fullmove,
            )
        }
    }
}
