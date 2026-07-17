package com.andyweaver.chess.engine

/**
 * Standard Algebraic Notation generation.
 *
 * SAN is produced relative to the position the move is made in (needed for
 * disambiguation and capture detection). Includes piece letter, minimal
 * file/rank/both disambiguation, `x` for captures, `=Q` for promotion, castling
 * as `O-O`/`O-O-O`, and `+`/`#` for check/checkmate. En passant is written as a
 * normal pawn capture, with an optional `e.p.` suffix if [enPassantSuffix] is set.
 */
object San {

    fun of(position: Position, move: Move, enPassantSuffix: Boolean = false): String {
        // Crazyhouse drop: "<Piece>@<square>" (pawn writes 'P'), matching Lichess's SAN.
        if (move.isDrop) {
            val dropType = move.drop!!
            val letter = if (dropType == PieceType.PAWN) "P" else dropType.sanLetter
            return "$letter@${Square.name(move.to)}" + checkSuffix(position, move)
        }

        val mover = position.pieceAt(move.from)
            ?: throw IllegalArgumentException("No piece on ${Square.name(move.from)} for SAN")

        val core = if (move.isCastle) {
            if (Square.file(move.to) == 6) "O-O" else "O-O-O"
        } else {
            buildCore(position, move, mover, enPassantSuffix)
        }

        return core + checkSuffix(position, move)
    }

    private fun buildCore(position: Position, move: Move, mover: Piece, enPassantSuffix: Boolean): String {
        val isCapture = position.pieceAt(move.to) != null || move.isEnPassant
        val sb = StringBuilder()

        if (mover.type == PieceType.PAWN) {
            // Pawn captures lead with the origin file; quiet pawn moves have no prefix.
            if (isCapture) {
                sb.append('a' + Square.file(move.from))
                sb.append('x')
            }
            sb.append(Square.name(move.to))
            if (move.promotion != null) {
                sb.append('=')
                sb.append(move.promotion.sanLetter)
            }
            if (move.isEnPassant && enPassantSuffix) sb.append(" e.p.")
        } else {
            sb.append(mover.type.sanLetter)
            sb.append(disambiguation(position, move, mover))
            if (isCapture) sb.append('x')
            sb.append(Square.name(move.to))
        }
        return sb.toString()
    }

    /**
     * Minimal disambiguation for a non-pawn move: if another piece of the same
     * type can legally move to the same square, add file, else rank, else both.
     */
    private fun disambiguation(position: Position, move: Move, mover: Piece): String {
        val rivals = MoveGenerator.legalMoves(position).filter { other ->
            other.to == move.to &&
                other.from != move.from &&
                position.pieceAt(other.from)?.type == mover.type
        }
        if (rivals.isEmpty()) return ""

        val sameFile = rivals.any { Square.file(it.from) == Square.file(move.from) }
        val sameRank = rivals.any { Square.rank(it.from) == Square.rank(move.from) }

        return when {
            !sameFile -> ('a' + Square.file(move.from)).toString()
            !sameRank -> ('1' + Square.rank(move.from)).toString()
            else -> Square.name(move.from)
        }
    }

    private fun checkSuffix(position: Position, move: Move): String {
        val after = MoveGenerator.applyMove(position, move)
        val opponent = after.sideToMove
        if (!MoveGenerator.isInCheck(after, opponent)) return ""
        val opponentHasMoves = MoveGenerator.legalMoves(after).isNotEmpty()
        return if (opponentHasMoves) "+" else "#"
    }
}
