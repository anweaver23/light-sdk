package com.andyweaver.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Repetition] tests, checked against the scalachess rules they mirror.
 *
 * The interesting cases are the ones where a naive implementation silently disagrees with
 * Lichess: the exact ply threefold lands on (the INITIAL position is occurrence #1), the
 * en-passant square only counting when an en-passant capture is genuinely legal, castling
 * rights dropping out of position identity in variants that can't castle, Three-check
 * folding check counts into identity, and Crazyhouse treating captures/drops as reversible.
 */
class RepetitionTest {

    // 1. Nf3 Nf6 2. Ng1 Ng8 — one full cycle back to the starting position.
    private val shuffle = listOf("g1f3", "g8f6", "f3g1", "f6g8")

    private fun shuffles(times: Int): Replay = Chess.replay(List(times) { shuffle }.flatten())

    // ----- The classic knight shuffle ---------------------------------------

    @Test
    fun knightShuffleReachesThreefoldOnTheEighthPly() {
        val replay = shuffles(2)
        // The start position is occurrence #1, so it is the SECOND completed cycle
        // (ply 8) that makes three — not the first (ply 4).
        assertEquals(1, Repetition.count(replay, 0))
        assertEquals(2, Repetition.count(replay, 4))
        assertEquals(3, Repetition.count(replay, 8))
        assertTrue(Repetition.isThreefold(replay, 8))
    }

    @Test
    fun knightShuffleIsNotThreefoldAPlyEarlier() {
        val replay = shuffles(2)
        // Ply 7 is a different position (knights half-way home), seen only twice.
        assertEquals(2, Repetition.count(replay, 7))
        assertFalse(Repetition.isThreefold(replay, 7))
        // And the position at ply 8 had only occurred twice as of ply 4.
        assertFalse(Repetition.isThreefold(replay, 4))
    }

    @Test
    fun defaultIndexIsTheCurrentPosition() {
        assertEquals(3, Repetition.count(shuffles(2)))
        assertTrue(Repetition.isThreefold(shuffles(2)))
    }

    // ----- Fivefold, and what auto-draws -------------------------------------

    @Test
    fun knightShuffleReachesFivefoldOnTheSixteenthPly() {
        val replay = shuffles(4)
        assertEquals(4, Repetition.count(replay, 12))
        assertFalse(Repetition.isFivefold(replay, 12))
        assertEquals(5, Repetition.count(replay, 16))
        assertTrue(Repetition.isFivefold(replay, 16))
    }

    @Test
    fun fivefoldIsAnAutoDrawButThreefoldIsNot() {
        // Lichess auto-draws on fivefold only; threefold is merely claimable, so a client
        // must never end the game on it.
        assertEquals(GameOutcome.Ongoing, Chess.outcome(shuffles(2)))
        assertEquals(
            GameOutcome.Decided(null, OutcomeReason.FIVEFOLD_REPETITION),
            Chess.outcome(shuffles(4)),
        )
    }

    @Test
    fun outcomeWithoutMovesDoesNoRepetitionDetection() {
        // The defaulted `steps` parameter means "no repetition detection" — the window
        // start can't be derived from positions alone, so callers that don't have the
        // moves opt out rather than get a guess.
        val replay = shuffles(4)
        assertEquals(
            GameOutcome.Ongoing,
            GameStatusEvaluator.outcome(replay.positions, Variant.STANDARD),
        )
        // Same guard inside Repetition itself.
        assertEquals(1, Repetition.count(replay.positions, emptyList(), Variant.STANDARD))
    }

    @Test
    fun claimHelpersOnChessFacade() {
        assertTrue(Chess.canClaimThreefold(shuffles(2)))
        assertFalse(Chess.canClaimThreefold(shuffles(1)))
        assertEquals(3, Chess.repetitionCount(shuffles(2)))
    }

    // ----- Irreversible moves reset the window -------------------------------

    @Test
    fun pawnMoveMidShuffleStopsTheCountReachingThree() {
        // Two full knight cycles would be threefold; a pawn move in the middle starts a
        // fresh window, and the pre-pawn-move positions can never recur anyway.
        val replay = Chess.replay(shuffle + listOf("a2a3", "a7a6") + shuffle)
        assertEquals(2, Repetition.count(replay, 10))
        assertFalse(Repetition.isThreefold(replay, 10))
    }

    @Test
    fun captureMidShuffleStopsTheCountReachingThree() {
        // The knights complete two full cycles, but exd5 in the middle starts a fresh
        // window — so the final position is only its window's SECOND occurrence.
        // (The trailing cycle is Black-first: it's Black to move after the capture.)
        val replay = Chess.replay(
            listOf("e2e4", "d7d5", "g1f3", "g8f6", "f3g1", "f6g8", "e4d5") +
                listOf("g8f6", "g1f3", "f6g8", "f3g1"),
        )
        assertEquals(2, Repetition.count(replay, 11))
        assertFalse(Repetition.isThreefold(replay, 11))
    }

    /**
     * Castling is irreversible for repetition purposes even though it moves no pawn,
     * captures nothing and does NOT reset the half-move clock — which is exactly why the
     * window has to be derived from the moves rather than from [Position.halfmoveClock],
     * and why many engines get it wrong.
     */
    @Test
    fun castlingIsIrreversible() {
        val replay = Chess.replay(listOf("e1g1"), "4k3/8/8/8/8/8/8/4K2R w K - 0 1")
        assertTrue(replay.steps[0].move.isCastle)
        assertTrue(Repetition.isIrreversible(replay.steps[0], Variant.STANDARD))
        // Sanity: the half-move clock did NOT reset, so clock-based inference would miss it.
        assertEquals(1, replay.finalPosition.halfmoveClock)
    }

    @Test
    fun pawnMovesCapturesAndPromotionsAreIrreversible() {
        val pawn = Chess.replay(listOf("e2e4"))
        assertTrue(Repetition.isIrreversible(pawn.steps[0], Variant.STANDARD))

        // A piece capture (no pawn involved), so it's the capture itself being detected.
        val capture = Chess.replay(listOf("g1f3", "g8f6", "f3e5", "f6e4", "e5f7"))
        assertTrue(capture.steps[4].move.let { !it.isDrop && !it.isCastle })
        assertTrue(Repetition.isIrreversible(capture.steps[4], Variant.STANDARD))
        // ...while the quiet knight moves before it are not.
        assertFalse(Repetition.isIrreversible(capture.steps[2], Variant.STANDARD))

        val promotion = Chess.replay(listOf("a7a8q"), "8/P3k3/8/8/8/8/8/4K3 w - - 0 1")
        assertTrue(Repetition.isIrreversible(promotion.steps[0], Variant.STANDARD))

        // En passant captures a piece that is not on the destination square.
        val ep = Chess.replay(listOf("e2e4", "a7a6", "e4e5", "d7d5", "e5d6"))
        assertTrue(ep.steps[4].move.isEnPassant)
        assertTrue(Repetition.isIrreversible(ep.steps[4], Variant.STANDARD))
    }

    // ----- Crazyhouse: only castling is irreversible --------------------------

    @Test
    fun crazyhouseCaptureDoesNotClearTheWindow() {
        // Captured material goes to a pocket and can come back, so scalachess overrides
        // `isIrreversible` to `move.castles` only.
        val capture = Chess.replay(
            listOf("g1f3", "g8f6", "f3e5", "f6e4", "e5f7"),
            startFen = null,
            variant = Variant.CRAZYHOUSE,
        )
        assertTrue(Repetition.isIrreversible(capture.steps[4], Variant.STANDARD))
        assertFalse(Repetition.isIrreversible(capture.steps[4], Variant.CRAZYHOUSE))
    }

    @Test
    fun crazyhousePawnMoveAndDropDoNotClearTheWindow() {
        val pawn = Chess.replay(listOf("e2e4"), startFen = null, variant = Variant.CRAZYHOUSE)
        assertFalse(Repetition.isIrreversible(pawn.steps[0], Variant.CRAZYHOUSE))

        val drop = Chess.replay(
            listOf("P@e4"),
            startFen = "4k3/8/8/8/8/8/8/4K3[P] w - - 0 1",
            variant = Variant.CRAZYHOUSE,
        )
        assertTrue(drop.steps[0].move.isDrop)
        assertFalse(Repetition.isIrreversible(drop.steps[0], Variant.CRAZYHOUSE))
    }

    @Test
    fun crazyhouseCastlingDoesClearTheWindow() {
        val replay = Chess.replay(
            listOf("e1g1"),
            startFen = "4k3/8/8/8/8/8/8/4K2R[] w K - 0 1",
            variant = Variant.CRAZYHOUSE,
        )
        assertTrue(Repetition.isIrreversible(replay.steps[0], Variant.CRAZYHOUSE))
    }

    @Test
    fun crazyhousePocketIsPartOfPositionIdentity() {
        // Same board and side to move, but one side is holding a pawn — not a repetition.
        val a = Position.fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1", Variant.CRAZYHOUSE)
        val b = Position.fromFen("4k3/8/8/8/8/8/8/4K3[P] w - - 0 1", Variant.CRAZYHOUSE)
        assertEquals(a.board, b.board)
        val replay = Replay(a, listOf(fakeStep(a, b)))
        assertEquals(1, Repetition.count(replay, 1))
    }

    // ----- Castling rights are excluded when the variant can't castle ---------

    // Rh1-g1, Ke8-d8, Rg1-h1, Kd8-e8: the board returns to its start, but White's
    // kingside castling right is gone. Standard chess says that's a different position;
    // a variant with no castling at all says it is the same one.
    private val rookWalk = listOf("h1g1", "e8d8", "g1h1", "d8e8")
    private val rookWalkFen = "4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1"

    @Test
    fun standardCountsCastlingRightsInPositionIdentity() {
        val replay = Chess.replay(rookWalk, rookWalkFen, Variant.STANDARD)
        assertEquals(replay.positions[0].board, replay.positions[4].board)
        assertTrue(replay.positions[0].castlingRights != replay.positions[4].castlingRights)
        assertEquals(1, Repetition.count(replay, 4))
    }

    @Test
    fun antichessExcludesCastlingRightsFromPositionIdentity() {
        assertFalse(Repetition.allowsCastling(Variant.ANTICHESS))
        val replay = Chess.replay(rookWalk, rookWalkFen, Variant.ANTICHESS)
        assertTrue(replay.positions[0].castlingRights != replay.positions[4].castlingRights)
        assertEquals(2, Repetition.count(replay, 4))
    }

    @Test
    fun racingKingsExcludesCastlingRightsFromPositionIdentity() {
        assertFalse(Repetition.allowsCastling(Variant.RACING_KINGS))
        val replay = Chess.replay(rookWalk, rookWalkFen, Variant.RACING_KINGS)
        assertEquals(2, Repetition.count(replay, 4))
    }

    // ----- En passant: only a LEGAL en-passant capture is part of identity -----

    /**
     * Black's d4 pawn really can play dxe3 e.p. after 1. e4, so that position differs from
     * the same board reached later with the en-passant chance gone.
     */
    @Test
    fun aLegalEnPassantMakesThePositionDifferent() {
        val replay = Chess.replay(
            listOf("e2e4", "b8c8", "b1c1", "c8b8", "c1b1"),
            "1k6/8/8/8/3p4/8/4P3/1K6 w - - 0 1",
        )
        // Precondition: the en-passant capture is genuinely available at ply 1.
        assertTrue(MoveGenerator.legalMoves(replay.positions[1]).any { it.isEnPassant })
        assertEquals(replay.positions[1].board, replay.positions[5].board)
        assertEquals(replay.positions[1].sideToMove, replay.positions[5].sideToMove)
        assertEquals(1, Repetition.count(replay, 5))
    }

    /**
     * The other half: the en-passant SQUARE exists after the same double push, but with no
     * black pawn able to take it the two positions are identical. Hashing
     * [Position.enPassantTarget] unconditionally would under-report repetitions here.
     */
    @Test
    fun anUnusableEnPassantSquareIsIgnored() {
        val replay = Chess.replay(
            listOf("e2e4", "b8c8", "b1c1", "c8b8", "c1b1"),
            "1k6/8/8/3p4/8/8/4P3/1K6 w - - 0 1",
        )
        // The square is set...
        assertEquals(Square.fromName("e3"), replay.positions[1].enPassantTarget)
        // ...but no en-passant capture is legal, so it must not participate in identity.
        assertFalse(MoveGenerator.legalMoves(replay.positions[1]).any { it.isEnPassant })
        assertEquals(null, replay.positions[5].enPassantTarget)
        assertEquals(2, Repetition.count(replay, 5))
    }

    // ----- Three-check folds the check counts into identity --------------------

    /**
     * Rh8+ / Kb7 / Rh1 / Ka8 returns the board to its start, but White has delivered a
     * check in the meantime. In Three-check that is a different position (you are one
     * check closer to losing); in standard chess it is a plain repetition.
     */
    @Test
    fun threeCheckCountsChecksInPositionIdentity() {
        val moves = listOf("h1h8", "a8b7", "h8h1", "b7a8")
        val fen = "k7/8/8/8/8/8/8/K6R w - - 0 1"

        val standard = Chess.replay(moves, fen, Variant.STANDARD)
        assertEquals(2, Repetition.count(standard, 4))

        val threeCheck = Chess.replay(moves, fen, Variant.THREE_CHECK)
        // Precondition: a check really was delivered.
        assertEquals(1 to 0, GameStatusEvaluator.checkCounts(threeCheck.positions))
        assertEquals(1, Repetition.count(threeCheck, 4))
    }

    // A synthetic step pairing two hand-built positions, for identity checks that don't
    // need a real legal move between them.
    private fun fakeStep(before: Position, after: Position) = MoveRecord(
        move = Move(0, 0),
        san = "--",
        before = before,
        after = after,
        number = 1,
        byWhite = true,
    )
}
