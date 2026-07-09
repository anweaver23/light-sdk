package com.andyweaver.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayTest {

    @Test
    fun replayProducesSanTimeline() {
        // 1. e4 e5 2. Nf3 — exactly the Lichess gameState.moves format.
        val replay = Chess.replay("e2e4 e7e5 g1f3")
        assertEquals(listOf("e4", "e5", "Nf3"), replay.steps.map { it.san })
        // Timeline = initial + one position per ply.
        assertEquals(4, replay.positions.size)
        assertEquals(Position.START_FEN, replay.positions.first().toFen())
    }

    @Test
    fun replayTracksMoveNumbersAndSide() {
        val replay = Chess.replay("e2e4 e7e5 g1f3")
        assertEquals(listOf(1, 1, 2), replay.steps.map { it.number })
        assertEquals(listOf(true, false, true), replay.steps.map { it.byWhite })
    }

    @Test
    fun replayFinalPositionMatchesExpectedFen() {
        val replay = Chess.replay("e2e4 e7e5 g1f3")
        assertEquals(
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
            replay.finalPosition.toFen(),
        )
    }

    @Test
    fun replayHandlesCastlingAndCaptures() {
        // Italian-ish line into a capture and castling.
        val replay = Chess.replay("e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 e1g1")
        assertEquals(listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "O-O"), replay.steps.map { it.san })
    }

    @Test
    fun replayFromCustomStartFen() {
        val fen = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"
        val replay = Chess.replay("e5d6", fen)
        assertEquals(listOf("exd6"), replay.steps.map { it.san })
    }

    @Test
    fun emptyMoveListYieldsJustInitialPosition() {
        val replay = Chess.replay("")
        assertTrue(replay.steps.isEmpty())
        assertEquals(1, replay.positions.size)
    }

    @Test
    fun illegalMoveInReplayThrows() {
        assertFailsWith<IllegalArgumentException> {
            Chess.replay("e2e4 e2e4") // second move impossible
        }
    }

    @Test
    fun legalDestinationsMatchesFacade() {
        val dests = Chess.legalDestinations(Position.START, Square.fromName("g1")!!)
        assertEquals(setOf(Square.fromName("f3"), Square.fromName("h3")), dests)
    }
}
