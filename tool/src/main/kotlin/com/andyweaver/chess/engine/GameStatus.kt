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
    FIFTY_MOVE_RULE,
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

    /** 100 half-moves without a pawn move or capture — Lichess's auto-draw. Draw. */
    FIFTY_MOVE_RULE,

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

    /**
     * The same position occurred five times — Lichess's automatic repetition draw. Draw.
     * See [Repetition] for what "the same position" means per variant.
     */
    FIVEFOLD_REPETITION,

    /**
     * A player resigned. The OTHER side is the [GameOutcome.Decided.winner].
     *
     * Nothing in the engine ever produces this — it is a human decision, reported by the
     * in-person game controller when a player picks "White resigns" / "Black resigns".
     */
    RESIGNATION,

    /**
     * Both players agreed to a draw. Draw (winner is null).
     *
     * Like [RESIGNATION] this is never produced by the engine; the in-person game
     * controller reports it when the players pick "Agree draw".
     */
    DRAW_AGREED,
}

object GameStatusEvaluator {

    /**
     * Full status of [position]. Checkmate/stalemate take precedence over draw
     * rules; among the latter, insufficient material is reported before the
     * move-count rule. Threefold repetition is not tracked here (it needs move
     * history — see [Chess.replay] callers if needed).
     *
     * Repetition is not tracked here (it needs move history). Note that Lichess
     * auto-draws only on FIVEfold; threefold is merely claimable, so a client must never
     * end a game on it.
     */
    fun status(position: Position): GameStatus {
        val inCheck = MoveGenerator.inCheck(position, position.sideToMove)
        val hasMoves = MoveGenerator.legalMoves(position).isNotEmpty()

        if (!hasMoves) {
            return if (inCheck) GameStatus.Checkmate else GameStatus.Stalemate
        }
        if (isDeadPosition(position)) {
            return GameStatus.Draw(DrawReason.INSUFFICIENT_MATERIAL)
        }
        if (usesMoveRuleDraw(position.variant) && position.halfmoveClock >= MOVE_RULE_HALFMOVES) {
            return GameStatus.Draw(DrawReason.FIFTY_MOVE_RULE)
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
     *
     * [steps] are the moves that produced [positions] (i.e. [Replay.steps]) and are needed
     * ONLY for the fivefold-repetition auto-draw: repetition's comparison window starts at
     * the last IRREVERSIBLE move, which cannot be inferred from the positions alone (the
     * half-move clock is not a substitute — castling is irreversible for repetition yet
     * doesn't reset the clock, and Crazyhouse's clock rules differ again). It defaults to
     * empty, which simply means "no repetition detection" for callers that don't have the
     * moves to hand; [Chess.outcome] always passes them.
     */
    fun outcome(
        positions: List<Position>,
        variant: Variant,
        steps: List<MoveRecord> = emptyList(),
    ): GameOutcome {
        val pos = positions.lastOrNull() ?: return GameOutcome.Ongoing
        val toMove = pos.sideToMove
        val justMoved = toMove.opposite
        val hasMoves = MoveGenerator.legalMoves(pos).isNotEmpty()
        val inCheck = MoveGenerator.inCheck(pos, toMove)

        return when (variant) {
            Variant.STANDARD, Variant.CHESS960 -> {
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    ?: if (isInsufficientMaterial(pos)) GameOutcome.Decided(null, OutcomeReason.INSUFFICIENT_MATERIAL)
                    else autoDrawOr(pos, positions, steps, variant, GameOutcome.Ongoing)
            }

            Variant.CRAZYHOUSE -> {
                // No insufficient-material draw: captured material can re-enter via drops.
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    ?: autoDrawOr(pos, positions, steps, variant, GameOutcome.Ongoing)
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
                    ?: autoDrawOr(pos, positions, steps, variant, GameOutcome.Ongoing)
            }

            Variant.KING_OF_THE_HILL -> {
                // A king reaching a centre square wins immediately.
                pos.kingSquare(Color.WHITE).let { if (it in CENTER_SQUARES) return GameOutcome.Decided(Color.WHITE, OutcomeReason.KING_IN_CENTER) }
                pos.kingSquare(Color.BLACK).let { if (it in CENTER_SQUARES) return GameOutcome.Decided(Color.BLACK, OutcomeReason.KING_IN_CENTER) }
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    ?: autoDrawOr(pos, positions, steps, variant, GameOutcome.Ongoing)
            }

            Variant.THREE_CHECK -> {
                // Count checks delivered across the game — see [checkCounts]. Position doesn't
                // store check counts, so they come from the replay: accurate for a game played
                // from the start; a mid-game FEN carrying prior checks would not be reflected.
                val (whiteChecks, blackChecks) = checkCounts(positions)
                if (whiteChecks >= 3) return GameOutcome.Decided(Color.WHITE, OutcomeReason.THREE_CHECKS)
                if (blackChecks >= 3) return GameOutcome.Decided(Color.BLACK, OutcomeReason.THREE_CHECKS)
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    // Kings-only is the only dead position here — with any other material a
                    // third check is still reachable. See [isDeadPosition].
                    ?: if (isKingsOnly(pos)) GameOutcome.Decided(null, OutcomeReason.INSUFFICIENT_MATERIAL)
                    else autoDrawOr(pos, positions, steps, variant, GameOutcome.Ongoing)
            }

            Variant.RACING_KINGS -> {
                val wk = pos.kingSquare(Color.WHITE)
                val bk = pos.kingSquare(Color.BLACK)
                val whiteOn8 = wk >= 0 && Square.rank(wk) == 7
                val blackOn8 = bk >= 0 && Square.rank(bk) == 7
                // Black gets a matching final ply, so reaching rank 8 does NOT end the game
                // on the spot. Mirrors scalachess `RacingKings.specialEnd`/`specialDraw`:
                //   • White to move: over iff exactly ONE king is home (that side wins);
                //     both home is the compensation DRAW.
                //   • Black to move: White is home and Black cannot match it → White wins.
                //     If Black CAN still reach rank 8, the game continues — Black has to
                //     actually play it, and may instead throw the draw away.
                // Declaring the draw as soon as Black *could* finish (what this did before)
                // calls the game a ply early, at its single most decisive moment.
                when {
                    toMove == Color.WHITE && whiteOn8 && blackOn8 ->
                        GameOutcome.Decided(null, OutcomeReason.RACING_KINGS_FINISH)
                    toMove == Color.WHITE && whiteOn8 ->
                        GameOutcome.Decided(Color.WHITE, OutcomeReason.RACING_KINGS_FINISH)
                    toMove == Color.WHITE && blackOn8 ->
                        GameOutcome.Decided(Color.BLACK, OutcomeReason.RACING_KINGS_FINISH)
                    toMove == Color.BLACK && whiteOn8 -> {
                        val blackCanMatch = MoveGenerator.legalMoves(pos).any { m ->
                            val k = MoveGenerator.applyMove(pos, m).kingSquare(Color.BLACK)
                            k >= 0 && Square.rank(k) == 7
                        }
                        if (blackCanMatch) autoDrawOr(pos, positions, steps, variant, GameOutcome.Ongoing)
                        else GameOutcome.Decided(Color.WHITE, OutcomeReason.RACING_KINGS_FINISH)
                    }
                    // No check is ever legal, so "no moves" is always a stalemate = draw.
                    // Checked AFTER the goal cases, so a finish is never reported as one.
                    !hasMoves -> GameOutcome.Decided(null, OutcomeReason.STALEMATE)
                    else -> autoDrawOr(pos, positions, steps, variant, GameOutcome.Ongoing)
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
                autoDrawOr(pos, positions, steps, variant, GameOutcome.Ongoing)
            }

            Variant.HORDE -> {
                // White is the horde (pawns/pieces, no king); Black has a normal army.
                // Black wins by capturing the entire horde; White wins by checkmating Black;
                // stalemate on either side is a draw.
                if (countPieces(pos, Color.WHITE) == 0) return GameOutcome.Decided(Color.BLACK, OutcomeReason.HORDE_DESTROYED)
                standardTermination(pos, hasMoves, inCheck, justMoved)
                    ?: autoDrawOr(pos, positions, steps, variant, GameOutcome.Ongoing)
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

    /**
     * Is this a dead position — no one can win, so the game ends now?
     *
     * The standard material test applies to standard rules ONLY. Applying it elsewhere
     * declares a live game over, and on the board screen that LOCKS THE USER OUT of a game
     * Lichess is still happily running. Each variant below overrides the rule in
     * scalachess, and for the same reason: the win condition isn't checkmate, so bare
     * material doesn't mean a dead game.
     *  • Antichess — the king is an ordinary, non-royal piece and the goal is to LOSE
     *    everything, so King vs King is a perfectly normal position. This is what wrongly
     *    froze a live Antichess game.
     *  • Crazyhouse — captured material comes back via drops; nothing is ever dead.
     *  • Horde — White has no king at all; a lone bishop vs a bare king read as a draw.
     *  • Atomic — a bare king can't be mated but CAN be exploded.
     *  • King of the Hill / Racing Kings — a lone king still has a game to play: walk to
     *    the centre, race to rank 8.
     *  • Three-check — you can still win by checking three times, so scalachess narrows the
     *    rule to kings-only (`ThreeCheck.isInsufficientMaterial = position.kingsOnly`),
     *    which is the one case where no check can ever be delivered again.
     *
     * Where scalachess has a bespoke test we don't reproduce (Atomic's closed positions,
     * Horde's fortress test, Antichess's blocked opposite-coloured bishops), this returns
     * false and the game is left running. That errs toward under-terminating, which is the
     * safe direction: online, Lichess ends the game and tells us.
     */
    private fun isDeadPosition(position: Position): Boolean = when (position.variant) {
        Variant.STANDARD, Variant.CHESS960 -> isInsufficientMaterial(position)
        Variant.THREE_CHECK -> isKingsOnly(position)
        Variant.ANTICHESS, Variant.CRAZYHOUSE, Variant.HORDE, Variant.ATOMIC,
        Variant.KING_OF_THE_HILL, Variant.RACING_KINGS,
        -> false
    }

    /** Nothing but the two kings left — no check can ever be delivered again. */
    private fun isKingsOnly(pos: Position): Boolean =
        (0 until Square.COUNT).all { pos.pieceAt(it)?.type?.let { t -> t == PieceType.KING } ?: true }

    /**
     * Half-moves without a pawn move or capture before the game auto-draws: **100**, i.e.
     * the 50-move rule.
     *
     * Verified against scalachess `Variant.fiftyMoves` (`halfMoveClock >= HalfMoveClock(100)`),
     * which feeds `autoDraw` directly. An earlier version of this file used 150 on the
     * belief that Lichess only auto-draws at 75 moves and merely permits a *claim* at 50 —
     * that is FIDE's rule, not Lichess's. Lichess ends the game itself at 100 half-moves.
     */
    const val MOVE_RULE_HALFMOVES = 100

    /**
     * Does this variant have a move-count draw at all? Every one does except **Crazyhouse**,
     * which disables it outright (`Crazyhouse.fiftyMoves = false`). That is not a detail:
     * Crazyhouse also treats only castling as irreversible, so the half-move clock barely
     * ever resets and would sail past any threshold in an ordinary game — applying the rule
     * there ends live games mid-play.
     */
    private fun usesMoveRuleDraw(variant: Variant): Boolean = variant != Variant.CRAZYHOUSE

    private fun moveRuleDrawOr(pos: Position, fallback: GameOutcome): GameOutcome =
        if (usesMoveRuleDraw(pos.variant) && pos.halfmoveClock >= MOVE_RULE_HALFMOVES) {
            GameOutcome.Decided(null, OutcomeReason.FIFTY_MOVE_RULE)
        } else {
            fallback
        }

    /**
     * The automatic draws that apply once no variant win condition has fired: the move-count
     * rule, then **fivefold repetition**. Both sit in the same place in scalachess's
     * `Position.status` precedence chain (checkmate → variant end → stalemate → autoDraw), so
     * this is deliberately the LAST thing consulted, never ahead of a variant win.
     *
     * Threefold is NOT here: Lichess only lets a player *claim* it (see [Repetition]).
     */
    private fun autoDrawOr(
        pos: Position,
        positions: List<Position>,
        steps: List<MoveRecord>,
        variant: Variant,
        fallback: GameOutcome,
    ): GameOutcome {
        val moveRule = moveRuleDrawOr(pos, fallback)
        if (moveRule !== fallback) return moveRule
        // Repetition needs the moves (for the irreversible-move window); without them the
        // caller has opted out. See [outcome]'s `steps` parameter.
        if (steps.size == positions.size - 1 &&
            Repetition.count(positions, steps, variant, positions.lastIndex) >= 5
        ) {
            return GameOutcome.Decided(null, OutcomeReason.FIVEFOLD_REPETITION)
        }
        return fallback
    }

    /**
     * Checks delivered across a game's [positions] prefix up to [upTo], as
     * `(byWhite, byBlack)`. A position whose side-to-move is in check means the side that
     * just moved delivered one.
     *
     * Used by the Three-check win condition AND by [Repetition] (check counts are part of
     * position identity in that variant), so it lives here once rather than twice.
     */
    fun checkCounts(positions: List<Position>, upTo: Int = positions.lastIndex): Pair<Int, Int> {
        var white = 0
        var black = 0
        for (i in 1..minOf(upTo, positions.lastIndex)) {
            val p = positions[i]
            if (MoveGenerator.inCheck(p, p.sideToMove)) {
                if (p.sideToMove == Color.BLACK) white++ else black++
            }
        }
        return white to black
    }

    /**
     * [checkCounts] evaluated at every index `0..upTo` in one pass — what [Repetition] needs
     * to compare the check tallies of two positions within a game without re-scanning the
     * prefix for each candidate.
     */
    internal fun checkCountPrefix(positions: List<Position>, upTo: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(upTo + 1)
        var white = 0
        var black = 0
        out.add(white to black)
        for (i in 1..minOf(upTo, positions.lastIndex)) {
            val p = positions[i]
            if (MoveGenerator.inCheck(p, p.sideToMove)) {
                if (p.sideToMove == Color.BLACK) white++ else black++
            }
            out.add(white to black)
        }
        return out
    }

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
