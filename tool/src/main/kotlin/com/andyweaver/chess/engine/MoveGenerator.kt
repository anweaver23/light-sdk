package com.andyweaver.chess.engine

/**
 * Move generation, attack detection, and move application.
 *
 * All generation produces fully legal moves (a move that leaves your own king in
 * check is discarded). Everything here is pure: nothing mutates a [Position].
 */
object MoveGenerator {

    private val KNIGHT_DELTAS = listOf(
        -2 to -1, -2 to 1, -1 to -2, -1 to 2,
        1 to -2, 1 to 2, 2 to -1, 2 to 1,
    )
    private val KING_DELTAS = listOf(
        -1 to -1, -1 to 0, -1 to 1, 0 to -1,
        0 to 1, 1 to -1, 1 to 0, 1 to 1,
    )
    private val BISHOP_DIRS = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
    private val ROOK_DIRS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    // ----- Attack detection -------------------------------------------------

    /** True if [square] is attacked by any piece of [byColor] in [position]. */
    fun isSquareAttacked(position: Position, square: Int, byColor: Color): Boolean {
        val file = Square.file(square)
        val rank = Square.rank(square)

        // Pawn attacks: a pawn of byColor attacks diagonally "forward" toward us.
        // A white pawn on (f-1,r-1)/(f+1,r-1) attacks (f,r); black is one rank above.
        val pawnRank = if (byColor == Color.WHITE) rank - 1 else rank + 1
        for (df in intArrayOf(-1, 1)) {
            val sq = Square.ofOrNull(file + df, pawnRank) ?: continue
            val p = position.pieceAt(sq)
            if (p != null && p.color == byColor && p.type == PieceType.PAWN) return true
        }

        // Knight attacks.
        for ((df, dr) in KNIGHT_DELTAS) {
            val sq = Square.ofOrNull(file + df, rank + dr) ?: continue
            val p = position.pieceAt(sq)
            if (p != null && p.color == byColor && p.type == PieceType.KNIGHT) return true
        }

        // King attacks (adjacent).
        for ((df, dr) in KING_DELTAS) {
            val sq = Square.ofOrNull(file + df, rank + dr) ?: continue
            val p = position.pieceAt(sq)
            if (p != null && p.color == byColor && p.type == PieceType.KING) return true
        }

        // Sliding: bishops/queens along diagonals.
        if (slidingAttack(position, file, rank, BISHOP_DIRS, byColor, PieceType.BISHOP)) return true
        // Rooks/queens along ranks/files.
        if (slidingAttack(position, file, rank, ROOK_DIRS, byColor, PieceType.ROOK)) return true

        return false
    }

    private fun slidingAttack(
        position: Position,
        file: Int,
        rank: Int,
        dirs: List<Pair<Int, Int>>,
        byColor: Color,
        // The straight-line piece type (BISHOP for diagonals, ROOK for orthogonals);
        // QUEEN attacks along both and is checked here too.
        lineType: PieceType,
    ): Boolean {
        for ((df, dr) in dirs) {
            var f = file + df
            var r = rank + dr
            while (f in 0..7 && r in 0..7) {
                val p = position.pieceAt(Square.of(f, r))
                if (p != null) {
                    if (p.color == byColor && (p.type == lineType || p.type == PieceType.QUEEN)) return true
                    break
                }
                f += df
                r += dr
            }
        }
        return false
    }

    /** True if [color]'s king is currently in check. */
    fun isInCheck(position: Position, color: Color): Boolean {
        val king = position.kingSquare(color)
        if (king < 0) return false
        return isSquareAttacked(position, king, color.opposite)
    }

    // ----- Legal move generation -------------------------------------------

    /** All fully legal moves for the side to move in [position]. */
    fun legalMoves(position: Position): List<Move> {
        val pseudo = pseudoLegalMoves(position)
        val side = position.sideToMove
        return pseudo.filter { move ->
            // Castling squares' safety is validated during pseudo-legal generation;
            // every move still must not leave our own king in check.
            !isInCheck(applyMove(position, move), side)
        }
    }

    /** Legal target squares for the piece on [from] (for move highlighting). */
    fun legalDestinations(position: Position, from: Int): Set<Int> =
        legalMoves(position).filter { it.from == from }.map { it.to }.toSet()

    /**
     * Pseudo-legal moves: correct in every respect except they may leave the
     * mover's king in check. Castling is only emitted when the king does not
     * pass through / land on an attacked square and is not currently in check,
     * so the king-safety filter in [legalMoves] handles the rest uniformly.
     */
    fun pseudoLegalMoves(position: Position): List<Move> {
        val moves = ArrayList<Move>(48)
        val side = position.sideToMove
        for (sq in 0 until Square.COUNT) {
            val piece = position.pieceAt(sq) ?: continue
            if (piece.color != side) continue
            when (piece.type) {
                PieceType.PAWN -> pawnMoves(position, sq, moves)
                PieceType.KNIGHT -> stepMoves(position, sq, KNIGHT_DELTAS, moves)
                PieceType.KING -> {
                    stepMoves(position, sq, KING_DELTAS, moves)
                    castlingMoves(position, sq, moves)
                }
                PieceType.BISHOP -> slideMoves(position, sq, BISHOP_DIRS, moves)
                PieceType.ROOK -> slideMoves(position, sq, ROOK_DIRS, moves)
                PieceType.QUEEN -> slideMoves(position, sq, BISHOP_DIRS + ROOK_DIRS, moves)
            }
        }
        return moves
    }

    private fun stepMoves(position: Position, from: Int, deltas: List<Pair<Int, Int>>, out: MutableList<Move>) {
        val side = position.sideToMove
        val file = Square.file(from)
        val rank = Square.rank(from)
        for ((df, dr) in deltas) {
            val to = Square.ofOrNull(file + df, rank + dr) ?: continue
            val target = position.pieceAt(to)
            if (target == null || target.color != side) {
                out.add(Move(from, to))
            }
        }
    }

    private fun slideMoves(position: Position, from: Int, dirs: List<Pair<Int, Int>>, out: MutableList<Move>) {
        val side = position.sideToMove
        val file = Square.file(from)
        val rank = Square.rank(from)
        for ((df, dr) in dirs) {
            var f = file + df
            var r = rank + dr
            while (f in 0..7 && r in 0..7) {
                val to = Square.of(f, r)
                val target = position.pieceAt(to)
                if (target == null) {
                    out.add(Move(from, to))
                } else {
                    if (target.color != side) out.add(Move(from, to))
                    break
                }
                f += df
                r += dr
            }
        }
    }

    private fun pawnMoves(position: Position, from: Int, out: MutableList<Move>) {
        val side = position.sideToMove
        val file = Square.file(from)
        val rank = Square.rank(from)
        val dir = if (side == Color.WHITE) 1 else -1
        val startRank = if (side == Color.WHITE) 1 else 6
        val promoRank = if (side == Color.WHITE) 7 else 0

        // Single push.
        val oneRank = rank + dir
        val one = Square.ofOrNull(file, oneRank)
        if (one != null && position.pieceAt(one) == null) {
            addPawnMove(from, one, oneRank == promoRank, out)
            // Double push.
            if (rank == startRank) {
                val two = Square.of(file, rank + 2 * dir)
                if (position.pieceAt(two) == null) {
                    out.add(Move(from, two, isDoublePawnPush = true))
                }
            }
        }

        // Captures (including en passant).
        for (df in intArrayOf(-1, 1)) {
            val to = Square.ofOrNull(file + df, oneRank) ?: continue
            val target = position.pieceAt(to)
            if (target != null && target.color == side.opposite) {
                addPawnMove(from, to, oneRank == promoRank, out)
            } else if (to == position.enPassantTarget && target == null) {
                out.add(Move(from, to, isEnPassant = true))
            }
        }
    }

    private fun addPawnMove(from: Int, to: Int, isPromotion: Boolean, out: MutableList<Move>) {
        if (isPromotion) {
            out.add(Move(from, to, promotion = PieceType.QUEEN))
            out.add(Move(from, to, promotion = PieceType.ROOK))
            out.add(Move(from, to, promotion = PieceType.BISHOP))
            out.add(Move(from, to, promotion = PieceType.KNIGHT))
        } else {
            out.add(Move(from, to))
        }
    }

    private fun castlingMoves(position: Position, kingSquare: Int, out: MutableList<Move>) {
        val side = position.sideToMove
        val rights = position.castlingRights
        val backRank = if (side == Color.WHITE) 0 else 7
        // King must be on its home square for castling to be relevant.
        if (kingSquare != Square.of(4, backRank)) return
        // Can't castle out of check.
        if (isInCheck(position, side)) return

        val enemy = side.opposite

        val kingSide = if (side == Color.WHITE) rights.whiteKingSide else rights.blackKingSide
        if (kingSide) {
            val f1 = Square.of(5, backRank)
            val g1 = Square.of(6, backRank)
            val rookSq = Square.of(7, backRank)
            val rook = position.pieceAt(rookSq)
            if (position.pieceAt(f1) == null && position.pieceAt(g1) == null &&
                rook?.type == PieceType.ROOK && rook.color == side &&
                !isSquareAttacked(position, f1, enemy) && !isSquareAttacked(position, g1, enemy)
            ) {
                out.add(Move(kingSquare, g1, isCastle = true))
            }
        }

        val queenSide = if (side == Color.WHITE) rights.whiteQueenSide else rights.blackQueenSide
        if (queenSide) {
            val d1 = Square.of(3, backRank)
            val c1 = Square.of(2, backRank)
            val b1 = Square.of(1, backRank)
            val rookSq = Square.of(0, backRank)
            val rook = position.pieceAt(rookSq)
            if (position.pieceAt(d1) == null && position.pieceAt(c1) == null && position.pieceAt(b1) == null &&
                rook?.type == PieceType.ROOK && rook.color == side &&
                !isSquareAttacked(position, d1, enemy) && !isSquareAttacked(position, c1, enemy)
            ) {
                out.add(Move(kingSquare, c1, isCastle = true))
            }
        }
    }

    // ----- Move application -------------------------------------------------

    /**
     * Apply [move] to [position], returning the resulting position. Updates side
     * to move, castling rights, en passant target, halfmove clock, and fullmove
     * number. The move is assumed to be legal (or at least pseudo-legal with
     * correct flags — as produced by the generator or [Move.fromUci]).
     */
    fun applyMove(position: Position, move: Move): Position {
        val board = position.board.toMutableList()
        val mover = board[move.from] ?: throw IllegalArgumentException("No piece on ${Square.name(move.from)}")
        val side = mover.color

        val isCapture = board[move.to] != null || move.isEnPassant

        // Move the piece (applying promotion if any).
        board[move.from] = null
        board[move.to] = if (move.promotion != null) Piece(side, move.promotion) else mover

        // En passant: remove the captured pawn, which sits behind the target square.
        if (move.isEnPassant) {
            val capturedPawnSq = Square.of(Square.file(move.to), Square.rank(move.from))
            board[capturedPawnSq] = null
        }

        // Castling: move the rook to the other side of the king.
        if (move.isCastle) {
            val backRank = Square.rank(move.to)
            if (Square.file(move.to) == 6) {
                // King side: rook h -> f.
                board[Square.of(5, backRank)] = board[Square.of(7, backRank)]
                board[Square.of(7, backRank)] = null
            } else {
                // Queen side: rook a -> d.
                board[Square.of(3, backRank)] = board[Square.of(0, backRank)]
                board[Square.of(0, backRank)] = null
            }
        }

        // Update castling rights.
        var rights = position.castlingRights
        rights = updateCastlingRights(rights, move.from, move.to, mover)

        // En passant target: only set on a double pawn push, to the skipped square.
        val newEnPassant = if (move.isDoublePawnPush) {
            Square.of(Square.file(move.from), (Square.rank(move.from) + Square.rank(move.to)) / 2)
        } else {
            null
        }

        // Halfmove clock resets on a pawn move or any capture.
        val newHalfmove = if (mover.type == PieceType.PAWN || isCapture) 0 else position.halfmoveClock + 1

        // Fullmove number increments after Black moves.
        val newFullmove = if (side == Color.BLACK) position.fullmoveNumber + 1 else position.fullmoveNumber

        return Position(
            board = board,
            sideToMove = side.opposite,
            castlingRights = rights,
            enPassantTarget = newEnPassant,
            halfmoveClock = newHalfmove,
            fullmoveNumber = newFullmove,
        )
    }

    private fun updateCastlingRights(rights: CastlingRights, from: Int, to: Int, mover: Piece): CastlingRights {
        var r = rights
        // King moved: lose both rights for that color.
        if (mover.type == PieceType.KING) {
            r = if (mover.color == Color.WHITE) {
                r.copy(whiteKingSide = false, whiteQueenSide = false)
            } else {
                r.copy(blackKingSide = false, blackQueenSide = false)
            }
        }
        // A rook leaving its home square, or being captured on its home square,
        // removes the corresponding right. Checking both from and to covers both.
        for (sq in intArrayOf(from, to)) {
            when (sq) {
                Square.of(0, 0) -> r = r.copy(whiteQueenSide = false) // a1
                Square.of(7, 0) -> r = r.copy(whiteKingSide = false)  // h1
                Square.of(0, 7) -> r = r.copy(blackQueenSide = false) // a8
                Square.of(7, 7) -> r = r.copy(blackKingSide = false)  // h8
            }
        }
        return r
    }
}
