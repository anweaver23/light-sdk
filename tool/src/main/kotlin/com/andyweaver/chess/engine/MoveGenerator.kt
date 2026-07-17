package com.andyweaver.chess.engine

/**
 * Move generation, attack detection, and move application.
 *
 * Standard chess is the default path; each supported [Variant] layers its rule
 * differences on top (Atomic explosions, Chess960 castling, Crazyhouse drops,
 * Antichess forced-captures / no-check, Racing Kings' no-check-either-side). All
 * generation produces fully legal moves for the active variant. Everything here is
 * pure: nothing mutates a [Position].
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

    /** True if [color]'s king is attacked in the standard sense (ignores variant nuance). */
    fun isInCheck(position: Position, color: Color): Boolean {
        val king = position.kingSquare(color)
        if (king < 0) return false
        return isSquareAttacked(position, king, color.opposite)
    }

    /**
     * Variant-aware "is [color] in check". Antichess kings aren't royal (never in
     * check); Atomic kings are not in check while adjacent to the enemy king (neither
     * can capture the other); everything else is the standard king-attacked test.
     */
    fun inCheck(position: Position, color: Color): Boolean = when (position.variant) {
        Variant.ANTICHESS -> false
        Variant.ATOMIC -> isAtomicCheck(position, color)
        else -> isInCheck(position, color)
    }

    private fun isAtomicCheck(position: Position, color: Color): Boolean {
        val king = position.kingSquare(color)
        if (king < 0) return false
        val enemyKing = position.kingSquare(color.opposite)
        if (enemyKing >= 0 && areAdjacent(king, enemyKing)) return false
        return isSquareAttacked(position, king, color.opposite)
    }

    private fun areAdjacent(a: Int, b: Int): Boolean {
        if (a == b) return false
        val df = kotlin.math.abs(Square.file(a) - Square.file(b))
        val dr = kotlin.math.abs(Square.rank(a) - Square.rank(b))
        return df <= 1 && dr <= 1
    }

    // ----- Legal move generation -------------------------------------------

    /** All fully legal moves for the side to move in [position]. */
    fun legalMoves(position: Position): List<Move> {
        val pseudo = pseudoLegalMoves(position)
        val side = position.sideToMove
        return when (position.variant) {
            // King isn't royal; captures are forced (if any exist, only captures are legal).
            Variant.ANTICHESS -> {
                val captures = pseudo.filter { isCaptureMove(position, it) }
                if (captures.isNotEmpty()) captures else pseudo
            }
            // Explosions decide legality (see [legalAtomic]).
            Variant.ATOMIC -> pseudo.filter { legalAtomic(position, it, side) }
            // Checks are forbidden entirely: not to yourself and not to the opponent.
            Variant.RACING_KINGS -> pseudo.filter { move ->
                val after = applyMove(position, move)
                !isInCheck(after, side) && !isInCheck(after, side.opposite)
            }
            // Standard-style: a move is legal iff it doesn't leave your own king in check.
            else -> pseudo.filter { !isInCheck(applyMove(position, it), side) }
        }
    }

    /** Legal target squares for the piece on [from] (for move highlighting). */
    fun legalDestinations(position: Position, from: Int): Set<Int> =
        legalMoves(position).filter { !it.isDrop && it.from == from }.map { it.to }.toSet()

    /** Legal drop target squares for [type] from the side-to-move's pocket (Crazyhouse). */
    fun legalDropSquares(position: Position, type: PieceType): Set<Int> =
        legalMoves(position).filter { it.drop == type }.map { it.to }.toSet()

    // Atomic legality: a move is legal if it explodes the enemy king (win, even out of
    // check); illegal if it explodes your own king (suicide, incl. any king capture);
    // otherwise you must not be left in atomic check.
    private fun legalAtomic(position: Position, move: Move, side: Color): Boolean {
        val after = applyMove(position, move)
        val myKing = after.kingSquare(side)
        if (myKing < 0) return false
        val oppKing = after.kingSquare(side.opposite)
        if (oppKing < 0) return true
        return !isAtomicCheck(after, side)
    }

    private fun isCaptureMove(position: Position, move: Move): Boolean {
        if (move.isDrop || move.isCastle) return false
        return move.isEnPassant || position.pieceAt(move.to) != null
    }

    /**
     * Pseudo-legal moves: correct in every respect except they may leave the mover's
     * king in check (the per-variant filter in [legalMoves] handles that). Castling is
     * only emitted when the king does not pass through / land on an attacked square.
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
        if (position.variant == Variant.CRAZYHOUSE) addDrops(position, moves)
        return moves
    }

    // Crazyhouse: drop each held piece type onto any empty square (pawns not on the
    // 1st/8th ranks). Legality (not leaving your king in check) is filtered later.
    private fun addDrops(position: Position, out: MutableList<Move>) {
        val side = position.sideToMove
        val types = position.pocket.types(side)
        if (types.isEmpty()) return
        for (sq in 0 until Square.COUNT) {
            if (position.pieceAt(sq) != null) continue
            val rank = Square.rank(sq)
            for (type in types) {
                if (type == PieceType.PAWN && (rank == 0 || rank == 7)) continue
                out.add(Move(from = sq, to = sq, drop = type))
            }
        }
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
        // Horde: White's Pawns on the first rank may also move two squares.
        val canDouble = rank == startRank ||
            (position.variant == Variant.HORDE && side == Color.WHITE && rank == 0)

        // Single push.
        val oneRank = rank + dir
        val one = Square.ofOrNull(file, oneRank)
        if (one != null && position.pieceAt(one) == null) {
            addPawnMove(position, from, one, oneRank == promoRank, out)
            // Double push.
            if (canDouble) {
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
                addPawnMove(position, from, to, oneRank == promoRank, out)
            } else if (to == position.enPassantTarget && target == null) {
                out.add(Move(from, to, isEnPassant = true))
            }
        }
    }

    private fun addPawnMove(position: Position, from: Int, to: Int, isPromotion: Boolean, out: MutableList<Move>) {
        if (isPromotion) {
            out.add(Move(from, to, promotion = PieceType.QUEEN))
            out.add(Move(from, to, promotion = PieceType.ROOK))
            out.add(Move(from, to, promotion = PieceType.BISHOP))
            out.add(Move(from, to, promotion = PieceType.KNIGHT))
            // Antichess allows promotion to a king.
            if (position.variant == Variant.ANTICHESS) out.add(Move(from, to, promotion = PieceType.KING))
        } else {
            out.add(Move(from, to))
        }
    }

    // Castling generation, Chess960-aware (works for standard positions too: the
    // castling rook is the outermost own rook on that side of the king). No castling
    // in Antichess (king not royal), Racing Kings, or Horde.
    private fun castlingMoves(position: Position, kingSquare: Int, out: MutableList<Move>) {
        when (position.variant) {
            Variant.ANTICHESS, Variant.RACING_KINGS, Variant.HORDE -> return
            else -> {}
        }
        val side = position.sideToMove
        val backRank = if (side == Color.WHITE) 0 else 7
        if (Square.rank(kingSquare) != backRank) return
        if (inCheck(position, side)) return

        val rights = position.castlingRights
        val kingSide = if (side == Color.WHITE) rights.whiteKingSide else rights.blackKingSide
        val queenSide = if (side == Color.WHITE) rights.whiteQueenSide else rights.blackQueenSide
        if (kingSide) tryCastle(position, kingSquare, backRank, kingSideCastle = true, out)
        if (queenSide) tryCastle(position, kingSquare, backRank, kingSideCastle = false, out)
    }

    private fun tryCastle(
        position: Position,
        kingSquare: Int,
        backRank: Int,
        kingSideCastle: Boolean,
        out: MutableList<Move>,
    ) {
        val side = position.sideToMove
        val enemy = side.opposite
        val kingFile = Square.file(kingSquare)

        // The castling rook is the outermost own rook on that side of the king.
        val rookFile = if (kingSideCastle) {
            (7 downTo kingFile + 1).firstOrNull { f -> isOwnRook(position, f, backRank, side) }
        } else {
            (0 until kingFile).firstOrNull { f -> isOwnRook(position, f, backRank, side) }
        } ?: return
        val rookFrom = Square.of(rookFile, backRank)

        val kingTargetFile = if (kingSideCastle) 6 else 2
        val rookTargetFile = if (kingSideCastle) 5 else 3

        // All squares the king and rook traverse must be empty except for the king and
        // the castling rook themselves.
        val exempt = setOf(kingSquare, rookFrom)
        if (!pathClear(position, kingFile, kingTargetFile, backRank, exempt)) return
        if (!pathClear(position, rookFile, rookTargetFile, backRank, exempt)) return

        // The king may not pass through (or land on) an attacked square. Atomic uses
        // the final-position atomic-check filter instead (adjacent-king nuance).
        if (position.variant != Variant.ATOMIC) {
            val lo = minOf(kingFile, kingTargetFile)
            val hi = maxOf(kingFile, kingTargetFile)
            for (f in lo..hi) {
                if (isSquareAttacked(position, Square.of(f, backRank), enemy)) return
            }
        }

        // Chess960 records castling as king-onto-rook; standard as king-to-g/c file.
        val to = if (position.variant == Variant.CHESS960) rookFrom else Square.of(kingTargetFile, backRank)
        out.add(Move(kingSquare, to, isCastle = true))
    }

    private fun isOwnRook(position: Position, file: Int, rank: Int, side: Color): Boolean {
        val p = position.pieceAt(Square.of(file, rank))
        return p != null && p.color == side && p.type == PieceType.ROOK
    }

    private fun pathClear(position: Position, fromFile: Int, toFile: Int, rank: Int, exempt: Set<Int>): Boolean {
        for (f in minOf(fromFile, toFile)..maxOf(fromFile, toFile)) {
            val sq = Square.of(f, rank)
            if (sq !in exempt && position.pieceAt(sq) != null) return false
        }
        return true
    }

    // ----- Move application -------------------------------------------------

    /**
     * Apply [move] to [position], returning the resulting position. Handles drops
     * (Crazyhouse), castling (standard and Chess960 king-onto-rook), en passant,
     * promotion, Atomic explosions, and Crazyhouse pocket/promoted bookkeeping. The
     * move is assumed to be legal (or at least well-formed, as produced by the
     * generator or [Move.fromUci]).
     */
    fun applyMove(position: Position, move: Move): Position {
        if (move.isDrop) return applyDrop(position, move)
        if (move.isCastle) return applyCastle(position, move)

        val board = position.board.toMutableList()
        val mover = board[move.from] ?: throw IllegalArgumentException("No piece on ${Square.name(move.from)}")
        val side = mover.color

        // The captured square is [to] for a normal capture, or the pawn behind [to] for en passant.
        val capturedSquare = if (move.isEnPassant) Square.of(Square.file(move.to), Square.rank(move.from)) else move.to
        val capturedPiece = board[capturedSquare]
        val isCapture = capturedPiece != null

        // Move the piece (applying promotion if any).
        board[move.from] = null
        board[move.to] = if (move.promotion != null) Piece(side, move.promotion) else mover
        if (move.isEnPassant) board[capturedSquare] = null

        // Crazyhouse: captured piece flips to the mover's pocket (a captured promoted
        // piece returns as a pawn); track which squares hold promoted pieces.
        var pocket = position.pocket
        var promoted: Set<Int> = position.promoted
        if (position.variant == Variant.CRAZYHOUSE) {
            val next = position.promoted.toHashSet()
            if (isCapture && capturedPiece != null && capturedPiece.type != PieceType.KING) {
                val addType = if (capturedSquare in position.promoted) PieceType.PAWN else capturedPiece.type
                pocket = pocket.add(side, addType)
            }
            next.remove(capturedSquare)
            next.remove(move.from)
            when {
                move.promotion != null -> next.add(move.to)
                move.from in position.promoted -> next.add(move.to)
            }
            promoted = next
        }

        // Atomic: a capture explodes the capturing piece and all non-pawn pieces within
        // one square of the capture. The captured piece is already gone (overwritten by
        // the landing piece, or removed for en passant).
        if (position.variant == Variant.ATOMIC && isCapture) {
            board[move.to] = null // the capturing piece explodes too
            val cf = Square.file(move.to)
            val cr = Square.rank(move.to)
            for ((df, dr) in KING_DELTAS) {
                val sq = Square.ofOrNull(cf + df, cr + dr) ?: continue
                val p = board[sq]
                if (p != null && p.type != PieceType.PAWN) board[sq] = null
            }
        }

        val rights = updateCastlingRights(position, move.from, move.to, mover)

        // En passant target: only after a double push from the standard start rank. A
        // Horde first-rank double push does NOT create an en-passant target.
        val standardStart = if (side == Color.WHITE) 1 else 6
        val newEnPassant = if (move.isDoublePawnPush && Square.rank(move.from) == standardStart) {
            Square.of(Square.file(move.from), (Square.rank(move.from) + Square.rank(move.to)) / 2)
        } else {
            null
        }

        val newHalfmove = if (mover.type == PieceType.PAWN || isCapture) 0 else position.halfmoveClock + 1
        val newFullmove = if (side == Color.BLACK) position.fullmoveNumber + 1 else position.fullmoveNumber

        return position.copy(
            board = board,
            sideToMove = side.opposite,
            castlingRights = rights,
            enPassantTarget = newEnPassant,
            halfmoveClock = newHalfmove,
            fullmoveNumber = newFullmove,
            pocket = pocket,
            promoted = promoted,
        )
    }

    private fun applyDrop(position: Position, move: Move): Position {
        val side = position.sideToMove
        val type = move.drop!!
        val board = position.board.toMutableList()
        board[move.to] = Piece(side, type)
        val newHalfmove = if (type == PieceType.PAWN) 0 else position.halfmoveClock + 1
        val newFullmove = if (side == Color.BLACK) position.fullmoveNumber + 1 else position.fullmoveNumber
        return position.copy(
            board = board,
            sideToMove = side.opposite,
            enPassantTarget = null,
            halfmoveClock = newHalfmove,
            fullmoveNumber = newFullmove,
            pocket = position.pocket.remove(side, type),
        )
    }

    /**
     * The squares involved in a castle [move]: (kingTo, rookFrom, rookTo). Handles the
     * standard encoding (king lands on the g/c file) and the Chess960 encoding (the
     * destination is the friendly rook). Exposed so the UI can animate the rook too —
     * [move.to] alone doesn't identify the king's real destination in Chess960.
     */
    fun castleSquares(position: Position, move: Move): Triple<Int, Int, Int> {
        val backRank = Square.rank(move.from)
        val kingFile = Square.file(move.from)
        val side = position.board[move.from]?.color ?: position.sideToMove
        // Chess960 records the destination as the rook square; standard as the g/c file.
        val destIsRook = position.board[move.to]?.let { it.color == side && it.type == PieceType.ROOK } == true
        val kingSide: Boolean
        val rookFrom: Int
        if (destIsRook) {
            rookFrom = move.to
            kingSide = Square.file(move.to) > kingFile
        } else {
            kingSide = Square.file(move.to) == 6
            rookFrom = Square.of(if (kingSide) 7 else 0, backRank)
        }
        val kingTo = Square.of(if (kingSide) 6 else 2, backRank)
        val rookTo = Square.of(if (kingSide) 5 else 3, backRank)
        return Triple(kingTo, rookFrom, rookTo)
    }

    private fun applyCastle(position: Position, move: Move): Position {
        val board = position.board.toMutableList()
        val side = position.sideToMove
        val (kingTo, rookFrom, rookTo) = castleSquares(position, move)

        board[move.from] = null
        board[rookFrom] = null
        board[kingTo] = Piece(side, PieceType.KING)
        board[rookTo] = Piece(side, PieceType.ROOK)

        val rights = if (side == Color.WHITE) {
            position.castlingRights.copy(whiteKingSide = false, whiteQueenSide = false)
        } else {
            position.castlingRights.copy(blackKingSide = false, blackQueenSide = false)
        }
        val newFullmove = if (side == Color.BLACK) position.fullmoveNumber + 1 else position.fullmoveNumber

        return position.copy(
            board = board,
            sideToMove = side.opposite,
            castlingRights = rights,
            enPassantTarget = null,
            halfmoveClock = position.halfmoveClock + 1,
            fullmoveNumber = newFullmove,
        )
    }

    private fun updateCastlingRights(position: Position, from: Int, to: Int, mover: Piece): CastlingRights {
        val orig = position.castlingRights
        var wk = orig.whiteKingSide
        var wq = orig.whiteQueenSide
        var bk = orig.blackKingSide
        var bq = orig.blackQueenSide

        // King moved: lose both rights for that color.
        if (mover.type == PieceType.KING) {
            if (mover.color == Color.WHITE) { wk = false; wq = false } else { bk = false; bq = false }
        }

        // A castling rook leaving its square, or being captured on it, removes that
        // right. The rook's square is derived from the pre-move position (outermost own
        // rook on that side of the king), so this is correct for Chess960 rooks on any
        // file — not just the standard corners. Checking both from and to covers a rook
        // that moves and a rook captured in place.
        val wkRook = if (orig.whiteKingSide) castlingRook(position, Color.WHITE, kingSide = true) else -1
        val wqRook = if (orig.whiteQueenSide) castlingRook(position, Color.WHITE, kingSide = false) else -1
        val bkRook = if (orig.blackKingSide) castlingRook(position, Color.BLACK, kingSide = true) else -1
        val bqRook = if (orig.blackQueenSide) castlingRook(position, Color.BLACK, kingSide = false) else -1
        for (sq in intArrayOf(from, to)) {
            when (sq) {
                wkRook -> wk = false
                wqRook -> wq = false
                bkRook -> bk = false
                bqRook -> bq = false
            }
        }
        return CastlingRights(wk, wq, bk, bq)
    }

    /** Outermost own rook on [kingSide]/queenside of [color]'s king (the castling rook), or -1. */
    private fun castlingRook(position: Position, color: Color, kingSide: Boolean): Int {
        val king = position.kingSquare(color)
        if (king < 0) return -1
        val backRank = Square.rank(king)
        val kingFile = Square.file(king)
        val range = if (kingSide) (7 downTo kingFile + 1) else (0 until kingFile)
        for (f in range) {
            if (isOwnRook(position, f, backRank, color)) return Square.of(f, backRank)
        }
        return -1
    }
}
