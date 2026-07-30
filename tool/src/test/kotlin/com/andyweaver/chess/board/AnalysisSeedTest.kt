package com.andyweaver.chess.board

import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.engine.Color
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Replay
import com.andyweaver.chess.engine.Square
import com.andyweaver.chess.engine.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The analysis sandbox is seeded with the REAL game's whole move list ([analysisSeed]) and
 * branches by truncate-and-replace ([forkLine]). Both are pure, so the behaviour that
 * matters is checked here rather than on an emulator:
 *   • entering analysis lands on the position the user was viewing, but with the game's
 *     history BEHIND it — so stepping back can walk all the way to the game's start;
 *   • branching from a past position keeps everything before it and discards the rest;
 *   • Crazyhouse pockets (which a FEN round trip would silently drop) survive both.
 */
class AnalysisSeedTest {

    private fun sq(name: String) = Square.fromName(name)!!

    private fun game(moves: String, variant: Variant = Variant.STANDARD): Replay =
        Chess.replay(moves, null, variant)

    /** The moves of a replay, as UCI — the form the sandbox line is held in. */
    private fun Replay.ucis(): List<String> = steps.map { it.move.toUci() }

    // ----- seeding ----------------------------------------------------------

    @Test
    fun seedCarriesTheWholeGameAndSitsWhereTheUserWasViewing() {
        val g = game("e2e4 e7e5 g1f3 b8c6")
        // The user had stepped back to after 1. e4 e5 (index 2) before entering analysis.
        val seed = analysisSeed(g, Variant.STANDARD, entryIndex = 2)

        assertEquals(2, seed.viewIndex, "opens on the position analysis was entered from")
        assertEquals(g.ucis(), seed.moves, "the sandbox line IS the game's move list")
        assertEquals(g.initial, seed.base, "based at the game's own initial position")
        assertEquals(4, seed.replay.positions.lastIndex, "the whole game is behind/ahead of it")
        assertEquals(
            g.positions[2].board,
            seed.replay.positions[seed.viewIndex].board,
            "the position shown is the one the user was looking at",
        )
    }

    @Test
    fun steppingBackFromTheEntryPointReachesTheGamesStart() {
        val g = game("e2e4 e7e5 g1f3 b8c6 f1b5")
        // Entered at the latest position — the case that previously had nothing behind it.
        val seed = analysisSeed(g, Variant.STANDARD, entryIndex = g.positions.lastIndex)

        assertEquals(5, seed.viewIndex)
        // Every index from the entry point down to 0 is a real position of the game, so
        // canStepBack (idx > 0) stays true the whole way down to the start.
        for (i in seed.viewIndex downTo 0) {
            assertEquals(g.positions[i].board, seed.replay.positions[i].board, "position $i")
        }
        assertEquals(
            Chess.startPosition.board,
            seed.replay.positions[0].board,
            "index 0 is the game's start position",
        )
    }

    @Test
    fun seedFromAGameWithNoMovesIsJustTheStartPosition() {
        val seed = analysisSeed(game(""), Variant.STANDARD, entryIndex = 0)
        assertTrue(seed.moves.isEmpty())
        assertEquals(0, seed.viewIndex)
        assertEquals(0, seed.replay.positions.lastIndex, "nothing to step back to")
    }

    @Test
    fun anOutOfRangeEntryIndexIsClamped() {
        val g = game("e2e4 e7e5")
        assertEquals(2, analysisSeed(g, Variant.STANDARD, entryIndex = 99).viewIndex)
        assertEquals(0, analysisSeed(g, Variant.STANDARD, entryIndex = -3).viewIndex)
    }

    @Test
    fun seedKeepsTheGamesStartingPositionForANonStandardStart() {
        // Racing Kings' start is not the standard one; the sandbox must branch from the
        // GAME's initial position, not from a standard board.
        val g = game("g2g3", Variant.RACING_KINGS)
        val seed = analysisSeed(g, Variant.RACING_KINGS, entryIndex = 1)
        assertEquals(g.initial.board, seed.replay.positions[0].board)
        assertEquals(Variant.RACING_KINGS, seed.replay.initial.variant)
    }

    // ----- branching --------------------------------------------------------

    @Test
    fun forkingMidHistoryKeepsThePastAndDiscardsTheFuture() {
        val g = game("e2e4 e7e5 g1f3 b8c6")
        val seed = analysisSeed(g, Variant.STANDARD, entryIndex = 2)

        // From after 1. e4 e5, play 2. Bc4 instead of the game's 2. Nf3.
        val line = forkLine(seed.moves, seed.viewIndex, "f1c4")
        assertEquals(listOf("e2e4", "e7e5", "f1c4"), line, "the game's later moves are dropped")

        val branch = Chess.replayFrom(seed.base, line)
        // Everything up to the fork point is still the real game…
        for (i in 0..2) {
            assertEquals(g.positions[i].board, branch.positions[i].board, "position $i")
        }
        // …and the new move diverges from it.
        assertNotEquals(g.positions[3].board, branch.positions[3].board)
        assertEquals(sq("c4"), branch.steps.last().move.to)
    }

    @Test
    fun forkingAtTheTipIsAPlainAppend() {
        val seed = analysisSeed(game("e2e4 e7e5"), Variant.STANDARD, entryIndex = 2)
        assertEquals(
            listOf("e2e4", "e7e5", "g1f3"),
            forkLine(seed.moves, seed.viewIndex, "g1f3"),
        )
    }

    @Test
    fun forkingAtTheGamesStartReplacesTheWholeLine() {
        val seed = analysisSeed(game("e2e4 e7e5 g1f3"), Variant.STANDARD, entryIndex = 0)
        assertEquals(listOf("d2d4"), forkLine(seed.moves, seed.viewIndex, "d2d4"))
    }

    // ----- Crazyhouse -------------------------------------------------------

    @Test
    fun crazyhousePocketsSurviveSeedingAndBranching() {
        // 1. e4 d5 2. exd5 Qxd5 3. Nc3 — both sides banked a pawn on move 2.
        val g = game("e2e4 d7d5 e4d5 d8d5 b1c3", Variant.CRAZYHOUSE)
        val tip = g.finalPosition
        assertEquals(1, tip.pocket.count(Color.WHITE, PieceType.PAWN), "the game itself banks a pawn")
        assertEquals(1, tip.pocket.count(Color.BLACK, PieceType.PAWN))

        val seed = analysisSeed(g, Variant.CRAZYHOUSE, entryIndex = g.positions.lastIndex)
        assertEquals(tip.pocket, seed.replay.finalPosition.pocket, "pockets survive the seed")

        // Re-deriving the line (what a branch does) rebuilds the pockets from the moves,
        // rather than relying on them surviving a Position.toFen round trip, which drops them.
        val rebuilt = Chess.replayFrom(seed.base, seed.moves)
        assertEquals(tip.pocket, rebuilt.finalPosition.pocket, "pockets survive a rebuild")

        // Branch back at index 4 (White to move, holding the pawn it took on d5) by
        // dropping that pawn instead of playing the game's 3. Nc3.
        val branch = Chess.replayFrom(seed.base, forkLine(seed.moves, 4, "P@e6"))
        assertEquals(5, branch.steps.size, "the game's own 5th move was replaced, not appended to")
        assertTrue(branch.steps.last().move.isDrop, "the branch really played a drop")
        assertEquals(
            0,
            branch.finalPosition.pocket.count(Color.WHITE, PieceType.PAWN),
            "the dropped pawn left White's reserve",
        )
        assertEquals(
            1,
            branch.finalPosition.pocket.count(Color.BLACK, PieceType.PAWN),
            "Black's reserve is untouched by White's branch",
        )
    }
}
