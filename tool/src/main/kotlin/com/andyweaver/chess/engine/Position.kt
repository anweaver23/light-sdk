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
    /** The active variant. Standard-chess positions leave this [Variant.STANDARD]. */
    val variant: Variant = Variant.STANDARD,
    /** Crazyhouse reserves (empty for every other variant). */
    val pocket: Pocket = Pocket.EMPTY,
    /**
     * Crazyhouse only: squares currently holding a piece that was promoted from a
     * pawn. When such a piece is captured it returns to the pocket as a pawn.
     */
    val promoted: Set<Int> = emptySet(),
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
         *
         * Variant extensions parsed when present (harmless for standard FENs):
         * - a trailing `[...]` on the placement field = the Crazyhouse pocket
         *   (uppercase = White, lowercase = Black; empty brackets = no reserves);
         * - a `~` after a piece char marks it as promoted (Crazyhouse);
         * - file letters (A-H/a-h) in the castling field = Chess960 castling rights,
         *   resolved to king/queen side by the rook's file vs the king's.
         */
        fun fromFen(fen: String, variant: Variant = Variant.STANDARD): Position {
            val fields = fen.trim().split(Regex("\\s+"))
            require(fields.size >= 4) { "FEN must have at least 4 fields: '$fen'" }

            // Split an optional Crazyhouse pocket "[...]" off the placement field.
            var placement = fields[0]
            var pocketSpec = ""
            val bracket = placement.indexOf('[')
            if (bracket >= 0) {
                pocketSpec = placement.substring(bracket + 1).trimEnd(']')
                placement = placement.substring(0, bracket)
            }

            val board = arrayOfNulls<Piece>(Square.COUNT)
            val promoted = HashSet<Int>()
            val ranks = placement.split('/')
            require(ranks.size == 8) { "FEN placement must have 8 ranks: '$placement'" }
            // First rank string is rank 8.
            for ((i, rankStr) in ranks.withIndex()) {
                val rank = 7 - i
                var file = 0
                var j = 0
                while (j < rankStr.length) {
                    val c = rankStr[j]
                    if (c.isDigit()) {
                        file += c - '0'
                    } else {
                        val piece = Piece.fromFenChar(c)
                            ?: throw IllegalArgumentException("Invalid FEN piece '$c' in '$placement'")
                        require(file in 0..7) { "FEN rank overflows: '$rankStr'" }
                        val sq = Square.of(file, rank)
                        board[sq] = piece
                        // A '~' right after the piece marks it as promoted (Crazyhouse).
                        if (j + 1 < rankStr.length && rankStr[j + 1] == '~') {
                            promoted.add(sq)
                            j++
                        }
                        file++
                    }
                    j++
                }
                require(file == 8) { "FEN rank '$rankStr' does not fill 8 files" }
            }

            val sideToMove = when (fields[1]) {
                "w" -> Color.WHITE
                "b" -> Color.BLACK
                else -> throw IllegalArgumentException("Invalid side to move: '${fields[1]}'")
            }

            val castling = parseCastling(fields[2], board)

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
                variant = variant,
                pocket = parsePocket(pocketSpec),
                promoted = promoted,
            )
        }

        /**
         * Castling field parse that also accepts Chess960 file letters. `KQkq` map
         * straight to the flags; a file letter (A-H white, a-h black) is resolved to
         * king/queen side by comparing the rook's file to that colour's king file
         * (the standard X-FEN interpretation: outermost rook on that side).
         */
        private fun parseCastling(field: String, board: Array<Piece?>): CastlingRights {
            if (field == "-" || field.isEmpty()) return CastlingRights.NONE
            var wk = false; var wq = false; var bk = false; var bq = false
            for (c in field) {
                when {
                    c == 'K' -> wk = true
                    c == 'Q' -> wq = true
                    c == 'k' -> bk = true
                    c == 'q' -> bq = true
                    c in 'A'..'H' -> {
                        val kf = kingFile(board, Color.WHITE)
                        if (kf != null && (c - 'A') > kf) wk = true else wq = true
                    }
                    c in 'a'..'h' -> {
                        val kf = kingFile(board, Color.BLACK)
                        if (kf != null && (c - 'a') > kf) bk = true else bq = true
                    }
                }
            }
            return CastlingRights(wk, wq, bk, bq)
        }

        private fun kingFile(board: Array<Piece?>, color: Color): Int? {
            for (sq in 0 until Square.COUNT) {
                val p = board[sq]
                if (p != null && p.color == color && p.type == PieceType.KING) return Square.file(sq)
            }
            return null
        }

        // Crazyhouse pocket spec, e.g. "PPNq" — uppercase White, lowercase Black.
        private fun parsePocket(spec: String): Pocket {
            var pocket = Pocket.EMPTY
            for (c in spec) {
                val piece = Piece.fromFenChar(c) ?: continue
                pocket = pocket.add(piece.color, piece.type)
            }
            return pocket
        }
    }
}
