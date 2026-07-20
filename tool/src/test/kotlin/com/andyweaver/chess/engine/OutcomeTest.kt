package com.andyweaver.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Variant-aware [GameStatusEvaluator.outcome] / [Chess.outcome] tests: each variant's win
 * condition firing, plus draw cases (stalemate, insufficient material, the Racing Kings
 * simultaneous-finish draw). Positions are built from FEN where possible.
 */
class OutcomeTest {

    // Single-position outcome, parsing the FEN under the given variant so move generation
    // uses the correct rules.
    private fun outcomeOf(fen: String, variant: Variant): GameOutcome =
        GameStatusEvaluator.outcome(listOf(Position.fromFen(fen, variant)), variant)

    private fun decided(winner: Color?, reason: OutcomeReason) = GameOutcome.Decided(winner, reason)

    // ----- Standard ---------------------------------------------------------

    @Test
    fun standardStartIsOngoing() {
        assertEquals(GameOutcome.Ongoing, GameStatusEvaluator.outcome(listOf(Position.START), Variant.STANDARD))
    }

    @Test
    fun standardCheckmateWinsForMover() {
        // Fool's mate: 1. f3 e5 2. g4 Qh4# — Black mates White.
        val replay = Chess.replay(listOf("f2f3", "e7e5", "g2g4", "d8h4"))
        assertEquals(decided(Color.BLACK, OutcomeReason.CHECKMATE), Chess.outcome(replay))
    }

    @Test
    fun standardStalemateIsDraw() {
        assertEquals(
            decided(null, OutcomeReason.STALEMATE),
            outcomeOf("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1", Variant.STANDARD),
        )
    }

    @Test
    fun standardInsufficientMaterialIsDraw() {
        assertEquals(
            decided(null, OutcomeReason.INSUFFICIENT_MATERIAL),
            outcomeOf("4k3/8/8/8/8/8/8/4K3 w - - 0 1", Variant.STANDARD),
        )
    }

    @Test
    fun standardSeventyFiveMoveRuleIsDraw() {
        // K+R vs K, plenty of legal moves, but the halfmove clock has hit 150.
        assertEquals(
            decided(null, OutcomeReason.SEVENTY_FIVE_MOVE_RULE),
            outcomeOf("4k3/8/8/8/8/8/8/R3K3 w - - 150 1", Variant.STANDARD),
        )
    }

    // ----- Atomic -----------------------------------------------------------

    @Test
    fun atomicKingExplodedWins() {
        // Nxd7 explodes the adjacent black king -> White wins.
        val replay = Chess.replay("e5d7", "4k3/3p4/8/4N3/8/8/8/4K3 w - - 0 1", Variant.ATOMIC)
        assertEquals(decided(Color.WHITE, OutcomeReason.ATOMIC_KING_EXPLODED), Chess.outcome(replay))
    }

    // ----- King of the Hill -------------------------------------------------

    @Test
    fun kingOfTheHillCenterWins() {
        // White king already on e4 (a centre square), Black to move -> White won.
        assertEquals(
            decided(Color.WHITE, OutcomeReason.KING_IN_CENTER),
            outcomeOf("4k3/8/8/8/4K3/8/8/8 b - - 0 1", Variant.KING_OF_THE_HILL),
        )
    }

    // ----- Three-check ------------------------------------------------------

    @Test
    fun threeCheckThirdCheckWins() {
        // A queen delivers three consecutive checks along the ranks; the third wins.
        val replay = Chess.replay(
            listOf("h1h8", "e8d7", "h8h7", "d7d6", "h7h6"),
            "4k3/8/8/8/8/8/8/4K2Q w - - 0 1",
            Variant.THREE_CHECK,
        )
        assertEquals(decided(Color.WHITE, OutcomeReason.THREE_CHECKS), Chess.outcome(replay))
    }

    // ----- Racing Kings -----------------------------------------------------

    @Test
    fun racingKingsFirstToEighthWins() {
        // White king already on a8; Black (to move) cannot reach the 8th rank in one move.
        assertEquals(
            decided(Color.WHITE, OutcomeReason.RACING_KINGS_FINISH),
            outcomeOf("K7/8/8/8/8/8/8/7k b - - 0 1", Variant.RACING_KINGS),
        )
    }

    @Test
    fun racingKingsSimultaneousFinishIsDraw() {
        // White king on a8; Black king on b7 can reach the 8th rank immediately (Kc8),
        // so the first-move-compensation rule makes it a draw.
        assertEquals(
            decided(null, OutcomeReason.RACING_KINGS_FINISH),
            outcomeOf("K7/1k6/8/8/8/8/8/8 b - - 0 1", Variant.RACING_KINGS),
        )
    }

    // ----- Antichess --------------------------------------------------------

    @Test
    fun antichessNoPiecesWins() {
        // White has no pieces left -> White wins (the goal is to lose everything).
        assertEquals(
            decided(Color.WHITE, OutcomeReason.ANTICHESS_NO_PIECES),
            outcomeOf("4k3/8/8/8/8/8/8/8 b - - 0 1", Variant.ANTICHESS),
        )
    }

    @Test
    fun antichessStalemateWinsForStalematedSide() {
        // White's only piece (pawn a2) is blocked with no capture -> White has no legal move,
        // which in Antichess is a WIN for White.
        assertEquals(
            decided(Color.WHITE, OutcomeReason.STALEMATE),
            outcomeOf("7k/8/8/8/8/p7/P7/8 w - - 0 1", Variant.ANTICHESS),
        )
    }

    // ----- Horde ------------------------------------------------------------

    @Test
    fun hordeDestroyedWinsForBlack() {
        // The horde (White) has been fully captured -> Black wins.
        assertEquals(
            decided(Color.BLACK, OutcomeReason.HORDE_DESTROYED),
            outcomeOf("4k3/8/8/8/8/8/8/8 b - - 0 1", Variant.HORDE),
        )
    }

    @Test
    fun hordeCheckmateWinsForWhite() {
        // Queen b7 (defended by pawn a6) mates the black king on a8 -> White (horde) wins.
        assertEquals(
            decided(Color.WHITE, OutcomeReason.CHECKMATE),
            outcomeOf("k7/1Q6/P7/8/8/8/8/8 b - - 0 1", Variant.HORDE),
        )
    }

    // ----- Crazyhouse -------------------------------------------------------

    @Test
    fun crazyhouseHasNoInsufficientMaterialDraw() {
        // K vs K would be an insufficient-material draw in standard chess, but in Crazyhouse
        // material can re-enter via drops, so it stays Ongoing.
        assertEquals(
            GameOutcome.Ongoing,
            outcomeOf("4k3/8/8/8/8/8/8/4K3 w - - 0 1", Variant.CRAZYHOUSE),
        )
    }
}
