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

/**
 * The authoritative outcome of a game, for the LOCAL two-human "in person" mode where
 * there is no Lichess stream to lean on — the engine itself must decide game-over and
 * the result for every supported [Variant].
 *
 * [Ongoing] means play continues. [Decided] carries the [winner] ([Color] that won, or
 * null for a draw) and the [reason] it ended. Deriving a PGN result token is then just
 * a function of [winner] (see [Pgn]).
 *
 * This is a superset of [GameStatus]: [GameStatus] is a single-position snapshot the
 * board/review screens already use for check/mate/draw rendering (unchanged); [GameOutcome]
 * additionally understands the variant-specific ways a game ends (king in the centre,
 * three checks, a king reaching the goal rank, the horde being wiped out, …) — some of
 * which need move history — and is the API the local-game controller should call.
 */
sealed class GameOutcome {
    /** The game is still in progress. */
    object Ongoing : GameOutcome()

    /** The game is over. [winner] is the winning [Color], or null for a draw. */
    data class Decided(val winner: Color?, val reason: OutcomeReason) : GameOutcome()

    val isOver: Boolean get() = this is Decided

    /** The winning colour, or null (a draw, or still ongoing). */
    val winnerOrNull: Color? get() = (this as? Decided)?.winner
}

/**
 * Why a [GameOutcome.Decided] game ended. For draws the [GameOutcome.Decided.winner] is
 * null; a few reasons can be either a win or a draw depending on the winner:
 * [STALEMATE] is a draw in standard chess but a WIN for the stalemated side in Antichess,
 * and [RACING_KINGS_FINISH] is a win when one king reached the goal rank but a draw when
 * both did (the first-move-compensation rule).
 */
enum class OutcomeReason {
    /** Side to move is in check with no legal move — the other side wins. */
    CHECKMATE,

    /** Side to move has no legal move and is not in check. Draw (standard); WIN for that side in Antichess. */
    STALEMATE,

    /** K vs K and similar dead positions. Draw. (Not applied to Crazyhouse — material can re-enter via drops.) */
    INSUFFICIENT_MATERIAL,

    /** 150 half-moves without a pawn move or capture (Lichess's 75-move auto-draw). Draw. */
    SEVENTY_FIVE_MOVE_RULE,

    /** King of the Hill: a king reached a centre square (d4/e4/d5/e5). Win. */
    KING_IN_CENTER,

    /** Three-check: a side delivered its third check. Win. */
    THREE_CHECKS,

    /** Racing Kings: a king reached the 8th rank (win), or both did on consecutive moves (draw). */
    RACING_KINGS_FINISH,

    /** Atomic: a king was blown off the board. The side whose king survives wins. */
    ATOMIC_KING_EXPLODED,

    /** Antichess: a side has no pieces left — that side wins (the goal is to lose everything). */
    ANTICHESS_NO_PIECES,

    /** Horde: the horde (White) has been completely captured — Black wins. */
    HORDE_DESTROYED,
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
     * The authoritative [GameOutcome] for a game, given its full position [positions]
     * timeline (index 0 = initial, then one per ply — exactly [Replay.positions]) and its
     * [variant]. Most variants only need the final position; Three-check needs the history
     * (to count checks), which is why this takes the whole timeline rather than a single
     * position.
     *
     * Terminal rules per variant were taken from the Lichess variant pages
     * (lichess.org/variant/…). Where a rule is genuinely ambiguous the code prefers
     * [GameOutcome.Ongoing] and says so in a comment, so the humans decide rather than the
     * engine reporting a possibly-wrong result.
     *
     * Does NOT replace [status] — that single-position snapshot API is untouched and still
     * drives the board/review screens' check/mate/draw rendering.
     */
    fun outcome(positions: List<Position>, variant: Variant): GameOutcome {
        val pos = positions.lastOrNull() ?: return GameOutcome.Ongoing
        val toMove = pos.sideToMove
        val justMoved = toMove.opposite
        val hasMoves = MoveGenerator.legalMoves(pos).isNotEmpty()
        val inCheck = MoveGenerator.inCheck(pos, toMove)

        return when (variant) {
            Variant.STANDARD, Variant.CHESS960 -> {
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    ?: if (isInsufficientMaterial(pos)) GameOutcome.Decided(null, OutcomeReason.INSUFFICIENT_MATERIAL)
                    else seventyFiveMoveOr(pos, GameOutcome.Ongoing)
            }

            Variant.CRAZYHOUSE -> {
                // No insufficient-material draw: captured material can re-enter via drops.
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    ?: seventyFiveMoveOr(pos, GameOutcome.Ongoing)
            }

            Variant.ATOMIC -> {
                // A king blown off the board decides it immediately: the surviving king wins.
                // (Exploding your OWN king is never a legal move, so at most the enemy king is gone.)
                if (pos.kingSquare(Color.WHITE) < 0) return GameOutcome.Decided(Color.BLACK, OutcomeReason.ATOMIC_KING_EXPLODED)
                if (pos.kingSquare(Color.BLACK) < 0) return GameOutcome.Decided(Color.WHITE, OutcomeReason.ATOMIC_KING_EXPLODED)
                // Otherwise normal (atomic-aware) checkmate/stalemate. Atomic insufficient-material
                // is subtle (a bare king can never be mated but can be exploded), so it is NOT
                // claimed here — an otherwise-quiet position is left Ongoing.
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    ?: seventyFiveMoveOr(pos, GameOutcome.Ongoing)
            }

            Variant.KING_OF_THE_HILL -> {
                // A king reaching a centre square wins immediately.
                pos.kingSquare(Color.WHITE).let { if (it in CENTER_SQUARES) return GameOutcome.Decided(Color.WHITE, OutcomeReason.KING_IN_CENTER) }
                pos.kingSquare(Color.BLACK).let { if (it in CENTER_SQUARES) return GameOutcome.Decided(Color.BLACK, OutcomeReason.KING_IN_CENTER) }
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    ?: seventyFiveMoveOr(pos, GameOutcome.Ongoing)
            }

            Variant.THREE_CHECK -> {
                // Count checks delivered across the game. A position whose side-to-move is in
                // check means the side that just moved delivered a check. (Position doesn't store
                // check counts, so this counts from the replay — accurate for a game played from
                // the start; a mid-game FEN carrying prior checks would not be reflected.)
                var whiteChecks = 0
                var blackChecks = 0
                for (i in 1 until positions.size) {
                    val p = positions[i]
                    if (MoveGenerator.inCheck(p, p.sideToMove)) {
                        if (p.sideToMove == Color.BLACK) whiteChecks++ else blackChecks++
                    }
                }
                if (whiteChecks >= 3) return GameOutcome.Decided(Color.WHITE, OutcomeReason.THREE_CHECKS)
                if (blackChecks >= 3) return GameOutcome.Decided(Color.BLACK, OutcomeReason.THREE_CHECKS)
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    ?: seventyFiveMoveOr(pos, GameOutcome.Ongoing)
            }

            Variant.RACING_KINGS -> {
                val wk = pos.kingSquare(Color.WHITE)
                val bk = pos.kingSquare(Color.BLACK)
                val whiteOn8 = wk >= 0 && Square.rank(wk) == 7
                val blackOn8 = bk >= 0 && Square.rank(bk) == 7
                when {
                    // Both kings home on the 8th → the first-move-compensation draw.
                    whiteOn8 && blackOn8 -> GameOutcome.Decided(null, OutcomeReason.RACING_KINGS_FINISH)
                    blackOn8 -> GameOutcome.Decided(Color.BLACK, OutcomeReason.RACING_KINGS_FINISH)
                    whiteOn8 -> {
                        // White reached the 8th. If Black is to move and can also reach the 8th
                        // rank immediately, it's a draw; otherwise White wins.
                        val blackCanFinish = toMove == Color.BLACK && MoveGenerator.legalMoves(pos).any { m ->
                            val k = MoveGenerator.applyMove(pos, m).kingSquare(Color.BLACK)
                            k >= 0 && Square.rank(k) == 7
                        }
                        if (blackCanFinish) GameOutcome.Decided(null, OutcomeReason.RACING_KINGS_FINISH)
                        else GameOutcome.Decided(Color.WHITE, OutcomeReason.RACING_KINGS_FINISH)
                    }
                    // No check is ever legal, so "no moves" is always a stalemate = draw.
                    !hasMoves -> GameOutcome.Decided(null, OutcomeReason.STALEMATE)
                    else -> seventyFiveMoveOr(pos, GameOutcome.Ongoing)
                }
            }

            Variant.ANTICHESS -> {
                // Win by losing all your pieces, OR by being stalemated (ICC rules, as Lichess uses).
                // The king is a normal, non-royal piece here (never in check).
                if (countPieces(pos, Color.WHITE) == 0) return GameOutcome.Decided(Color.WHITE, OutcomeReason.ANTICHESS_NO_PIECES)
                if (countPieces(pos, Color.BLACK) == 0) return GameOutcome.Decided(Color.BLACK, OutcomeReason.ANTICHESS_NO_PIECES)
                if (!hasMoves) return GameOutcome.Decided(toMove, OutcomeReason.STALEMATE)
                // A dead position (e.g. bishops locked on opposite colours) is a draw on Lichess,
                // but detecting it reliably is non-trivial, so it's left Ongoing rather than guessed.
                seventyFiveMoveOr(pos, GameOutcome.Ongoing)
            }

            Variant.HORDE -> {
                // White is the horde (pawns/pieces, no king); Black has a normal army.
                // Black wins by capturing the entire horde; White wins by checkmating Black;
                // stalemate on either side is a draw.
                if (countPieces(pos, Color.WHITE) == 0) return GameOutcome.Decided(Color.BLACK, OutcomeReason.HORDE_DESTROYED)
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    ?: seventyFiveMoveOr(pos, GameOutcome.Ongoing)
            }
        }
    }

    // Standard checkmate/stalemate resolution, or null if the side to move can still play.
    private fun standardTermination(
        pos: Position,
        hasMoves: Boolean,
        inCheck: Boolean,
        justMoved: Color,
    ): GameOutcome? {
        if (hasMoves) return null
        return if (inCheck) {
            GameOutcome.Decided(justMoved, OutcomeReason.CHECKMATE)
        } else {
            GameOutcome.Decided(null, OutcomeReason.STALEMATE)
        }
    }

    private fun seventyFiveMoveOr(pos: Position, fallback: GameOutcome): GameOutcome =
        if (pos.halfmoveClock >= 150) GameOutcome.Decided(null, OutcomeReason.SEVENTY_FIVE_MOVE_RULE) else fallback

    private fun countPieces(pos: Position, color: Color): Int {
        var n = 0
        for (sq in 0 until Square.COUNT) if (pos.pieceAt(sq)?.color == color) n++
        return n
    }

    // King-of-the-Hill centre squares: d4, e4, d5, e5.
    private val CENTER_SQUARES = setOf(
        Square.of(3, 3), Square.of(4, 3), Square.of(3, 4), Square.of(4, 4),
    )

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
