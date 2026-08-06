package com.andyweaver.chess.engine

/**
 * Draw-by-repetition detection, matching Lichess (scalachess) exactly.
 *
 * Lichess **auto-draws on FIVEFOLD only**; threefold is merely *claimable* by a player.
 * So [isFivefold] is what [GameStatusEvaluator.outcome] ends a game on, and [isThreefold]
 * is purely an offer the UI may surface — nothing in the engine ever ends a game on it.
 * Only the ONLINE board surfaces it (as a "Claim draw" prompt, which sends the claim to
 * Lichess); the in-person game deliberately does not, and offers "Agree draw" in its menu
 * instead. Note that Lichess's own `autoThreefold` preference defaults to ALWAYS, so for
 * most accounts a threefold is auto-drawn server-side before any prompt can appear.
 *
 * ### What counts as "the same position"
 * Verified against scalachess `core/src/main/scala/{Hash,History,Position}.scala`. Two
 * positions repeat iff **all** of the following match (and only positions with the same
 * side to move are ever compared, since side-to-move is part of the identity):
 *
 *  1. Full piece placement.
 *  2. Side to move.
 *  3. Castling rights — **omitted entirely when the variant disallows castling**
 *     (scalachess guards this with `if position.variant.allowsCastling`). That is Racing
 *     Kings and Antichess here; see [allowsCastling].
 *  4. The en-passant **file**, and only when an en-passant capture is *genuinely legal*
 *     (scalachess derives it from `legalMoves.find(_.enpassant)`, not from "a double push
 *     just happened"). [Position.enPassantTarget] is set unconditionally by
 *     [MoveGenerator.applyMove], so this checks real legality — see [enPassantFile].
 *     Getting this wrong makes us UNDER-report repetitions relative to Lichess.
 *  5. Three-check only: both players' running check counts (see [GameStatusEvaluator.checkCounts]).
 *  6. Crazyhouse only: both pockets and the set of promoted-piece squares.
 *
 * ### The comparison window
 * Only positions since the most recent **irreversible** move are compared — nothing before
 * it can ever recur. scalachess's `Variant.isIrreversible(move)` is
 * `pawn move || captures || promotes || castles`, **overridden in Crazyhouse to
 * `move.castles` only** (captured material returns to a pocket, so a capture is reversible
 * there; drops never clear the window either).
 *
 * scalachess seeds its position list with the pre-move position, so the position produced
 * by an irreversible move is occurrence **#1** of that window — and by the same token the
 * game's initial position is occurrence #1 when no irreversible move has happened yet.
 * That is exactly what starting the window *at* [windowStart] (inclusive) gives.
 */
object Repetition {

    /** How many times the position at [index] has occurred in this game (>= 1). */
    fun count(replay: Replay, index: Int = replay.positions.lastIndex): Int =
        count(replay.positions, replay.steps, replay.initial.variant, index)

    /** True when the position at [index] has occurred three or more times. Claimable, NOT an auto-draw. */
    fun isThreefold(replay: Replay, index: Int = replay.positions.lastIndex): Boolean = count(replay, index) >= 3

    /** True when the position at [index] has occurred five or more times. This IS an auto-draw on Lichess. */
    fun isFivefold(replay: Replay, index: Int = replay.positions.lastIndex): Boolean = count(replay, index) >= 5

    /**
     * As [count], but over a raw timeline. [positions] is `initial + one per ply` (exactly
     * [Replay.positions]) and [steps] the matching moves — `steps.size` must be
     * `positions.size - 1` or this returns 1 (repetition can't be judged without knowing
     * which moves were irreversible; see [GameStatusEvaluator.outcome]'s defaulted `steps`).
     */
    fun count(
        positions: List<Position>,
        steps: List<MoveRecord>,
        variant: Variant,
        index: Int = positions.lastIndex,
    ): Int {
        if (index !in positions.indices) return 0
        if (steps.size != positions.size - 1) return 1
        val target = positions[index]
        val start = windowStart(steps, variant, index)

        // Three-check folds the check counts into position identity, so build the running
        // tallies once for the whole prefix rather than per candidate.
        val checks: List<Pair<Int, Int>>? =
            if (variant == Variant.THREE_CHECK) GameStatusEvaluator.checkCountPrefix(positions, index) else null

        val useCastling = allowsCastling(variant)
        val crazyhouse = variant == Variant.CRAZYHOUSE
        val targetEpFile = enPassantFile(target)

        var n = 0
        for (j in start..index) {
            val p = positions[j]
            // Cheap discriminators first: the expensive part is [enPassantFile], which runs
            // the move generator, so never reach it for an unrelated position.
            if (p.sideToMove != target.sideToMove) continue
            if (p.board != target.board) continue
            if (useCastling && p.castlingRights != target.castlingRights) continue
            if (crazyhouse && (p.pocket != target.pocket || p.promoted != target.promoted)) continue
            if (checks != null && checks[j] != checks[index]) continue
            if (enPassantFile(p) != targetEpFile) continue
            n++
        }
        return n
    }

    /**
     * Index of the first position that could possibly repeat the one at [index]: the
     * position produced by the most recent irreversible move at or before [index], or 0 if
     * there hasn't been one. `steps[i]` transitions `positions[i]` to `positions[i + 1]`,
     * so an irreversible `steps[i]` puts the window start at `i + 1`.
     */
    private fun windowStart(steps: List<MoveRecord>, variant: Variant, index: Int): Int {
        var start = 0
        for (i in 0 until minOf(index, steps.size)) {
            if (isIrreversible(steps[i], variant)) start = i + 1
        }
        return start
    }

    /**
     * scalachess `Variant.isIrreversible`: `pawn move || captures || promotes || castles`.
     *
     * **Crazyhouse overrides it to castling only** (`Crazyhouse.isIrreversible = move.castles`):
     * a captured piece goes to a pocket and can come back, and a pawn move doesn't remove
     * material from play either, so neither closes off earlier positions. Drops are never
     * irreversible in any variant (only Crazyhouse has them at all).
     */
    internal fun isIrreversible(step: MoveRecord, variant: Variant): Boolean {
        val move = step.move
        if (variant == Variant.CRAZYHOUSE) return move.isCastle
        if (move.isDrop) return false
        if (move.isCastle) return true
        if (move.promotion != null) return true
        if (step.before.pieceAt(move.from)?.type == PieceType.PAWN) return true
        return isCapture(step.before, move)
    }

    /**
     * Did [move] capture in [before]? Mirrors `MoveGenerator`'s own (private) capture test:
     * drops and castles never capture — note that a Chess960 castle's destination square
     * holds the mover's OWN rook, so the piece-at-destination test would misread it — and
     * en passant captures a piece that isn't standing on the destination square.
     */
    private fun isCapture(before: Position, move: Move): Boolean {
        if (move.isDrop || move.isCastle) return false
        return move.isEnPassant || before.pieceAt(move.to) != null
    }

    /**
     * Does the variant have castling at all? When it doesn't, scalachess leaves castling
     * rights OUT of the position hash entirely, so two positions that differ only in
     * (meaningless, unusable) rights still repeat.
     *
     * Racing Kings has no rooks-with-rights and Antichess has no royal king, so neither
     * generates castling — matching [MoveGenerator]'s own early-out in `castlingMoves`.
     */
    internal fun allowsCastling(variant: Variant): Boolean =
        variant != Variant.RACING_KINGS && variant != Variant.ANTICHESS

    /**
     * The en-passant file that participates in position identity, or null.
     *
     * scalachess only hashes an en-passant square when an en-passant capture is actually
     * available (`legalMoves.find(_.enpassant)`), not merely because a double push just
     * happened. Our [Position.enPassantTarget] is set unconditionally, so the legality has
     * to be re-derived — but only ever when a target exists, which keeps the move-generator
     * call rare.
     */
    private fun enPassantFile(position: Position): Int? {
        val target = position.enPassantTarget ?: return null
        val legal = MoveGenerator.legalMoves(position).any { it.isEnPassant }
        return if (legal) Square.file(target) else null
    }
}
