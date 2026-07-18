package com.andyweaver.chess.engine

/**
 * The status of a position, from the perspective of the side to move.
 *
 * [Checkmate] and [Stalemate] are terminal; [Draw] carries the reason. [Check]
 * and [Ongoing] are live states. This is enough for the board screen to render
 * a "game over" state and a "check" state as logic even before the visuals exist.
 */
sealed class GameStatus {
    /** No check, legal moves available. */
    object Ongoing : GameStatus()

    /** Side to move is in check but has legal moves. */
    object Check : GameStatus()

    /** Side to move is in check with no legal moves — the other side won. */
    object Checkmate : GameStatus()

    /** Side to move has no legal moves and is not in check — draw. */
    object Stalemate : GameStatus()

    /** A draw by rule; [reason] distinguishes which. */
    data class Draw(val reason: DrawReason) : GameStatus()

    val isTerminal: Boolean
        get() = this is Checkmate || this is Stalemate || this is Draw
}

enum class DrawReason {
    INSUFFICIENT_MATERIAL,
    SEVENTY_FIVE_MOVE_RULE,
}

object GameStatusEvaluator {

    /**
     * Full status of [position]. Checkmate/stalemate take precedence over draw
     * rules; among the latter, insufficient material is reported before the
     * move-count rule. Threefold repetition is not tracked here (it needs move
     * history — see [Chess.replay] callers if needed).
     *
     * The move-count draw uses the FIDE **75-move rule** (150 half-moves), not the
     * classic 50-move rule — Lichess auto-draws a game at 75 moves without a pawn
     * push or capture, but only *permits a claim* at 50 (a claim this app has no UI
     * for). Using 50 here would freeze a still-live Lichess game as "over" locally.
     */
    fun status(position: Position): GameStatus {
        val inCheck = MoveGenerator.inCheck(position, position.sideToMove)
        val hasMoves = MoveGenerator.legalMoves(position).isNotEmpty()

        if (!hasMoves) {
            return if (inCheck) GameStatus.Checkmate else GameStatus.Stalemate
        }
        if (isInsufficientMaterial(position)) {
            return GameStatus.Draw(DrawReason.INSUFFICIENT_MATERIAL)
        }
        if (position.halfmoveClock >= 150) {
            return GameStatus.Draw(DrawReason.SEVENTY_FIVE_MOVE_RULE)
        }
        return if (inCheck) GameStatus.Check else GameStatus.Ongoing
    }

    /**
     * Insufficient-material draw detection. Covers: K vs K, K + single minor
     * (bishop or knight) vs K, and K+B vs K+B where both bishops are on
     * same-colored squares. Any pawn, rook, or queen means material is sufficient.
     */
    fun isInsufficientMaterial(position: Position): Boolean {
        val minors = ArrayList<Pair<PieceType, Int>>() // (type, square) for bishops/knights
        for (sq in 0 until Square.COUNT) {
            val p = position.pieceAt(sq) ?: continue
            when (p.type) {
                PieceType.KING -> {}
                PieceType.PAWN, PieceType.ROOK, PieceType.QUEEN -> return false
                PieceType.BISHOP, PieceType.KNIGHT -> minors.add(p.type to sq)
            }
        }
        return when (minors.size) {
            0, 1 -> true
            2 -> {
                // Two bishops on the same square color cannot deliver mate.
                val bothBishops = minors.all { it.first == PieceType.BISHOP }
                bothBishops && Square.isLight(minors[0].second) == Square.isLight(minors[1].second)
            }
            else -> false
        }
    }
}
