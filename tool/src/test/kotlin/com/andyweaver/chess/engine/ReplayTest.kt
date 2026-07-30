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
    fun replayFromPositionMatchesFenReplay() {
        val viaFen = Chess.replay("e2e4 e7e5 g1f3")
        val viaPosition = Chess.replayFrom(Position.START, listOf("e2e4", "e7e5", "g1f3"))
        assertEquals(viaFen.steps.map { it.san }, viaPosition.steps.map { it.san })
        assertEquals(viaFen.finalPosition.toFen(), viaPosition.finalPosition.toFen())
    }

    @Test
    fun replayFromSupportsTruncateAndReplaceBranching() {
        // The analysis sandbox's branching model: step back to an earlier position, play a
        // different move there, and the line is truncated at that point and continues from
        // it (see BoardViewModel.applyAnalysisMove). Position index i == "after i moves",
        // so the view index indexes the move list directly.
        val base = Position.START
        val line = mutableListOf("e2e4", "e7e5", "g1f3")
        val full = Chess.replayFrom(base, line)
        assertEquals(listOf("e4", "e5", "Nf3"), full.steps.map { it.san })

        val viewIndex = 1 // viewing the position after 1. e4
        line.subList(viewIndex, line.size).clear()
        line.add("c7c5")
        val forked = Chess.replayFrom(base, line)

        assertEquals(listOf("e4", "c5"), forked.steps.map { it.san })
        assertEquals(3, forked.positions.size)
        // Everything up to the fork point is unchanged; everything after it is gone.
        assertEquals(full.positions[viewIndex].toFen(), forked.positions[viewIndex].toFen())
    }

    @Test
    fun replayFromPreservesCrazyhousePocket() {
        // Why replayFrom takes a Position rather than a FEN: toFen() emits only the six
        // standard fields, so snapshotting a Crazyhouse position through a FEN would drop
        // the reserves the sandbox then has to let the player drop.
        val game = Chess.replay("e2e4 d7d5 e4d5 d8d5", null, Variant.CRAZYHOUSE)
        val snapshot = game.finalPosition
        assertEquals(1, snapshot.pocket.count(Color.WHITE, PieceType.PAWN))

        val branch = Chess.replayFrom(snapshot, listOf("P@e4"))
        assertEquals(1, branch.steps.size)
        assertEquals(0, branch.finalPosition.pocket.count(Color.WHITE, PieceType.PAWN))
        assertEquals(PieceType.PAWN, branch.finalPosition.pieceAt(Square.fromName("e4")!!)?.type)
    }

    @Test
    fun strictSanReplayThrowsOnUnrecognizedToken() {
        assertFailsWith<IllegalArgumentException> {
            Chess.replaySan("e4 e5 Qz9") // third token isn't a move at all
        }
    }

    @Test
    fun lenientSanReplayKeepsThePrefixItCouldParse() {
        // The review screen's failure mode: ONE token our SAN generator can't match must
        // not discard the whole game (a Horde game with 40 readable moves reviewed as
        // "can't be reviewed here" before this).
        val result = Chess.replaySanLenient("e4 e5 Nf3 Qz9 Nc6")
        assertTrue(result.truncated)
        assertEquals("Qz9", result.unmatchedToken)
        assertEquals(3, result.movesReplayed)
        assertEquals(5, result.totalTokens)
        assertEquals(listOf("e4", "e5", "Nf3"), result.replay.steps.map { it.san })
        // The partial timeline is still a coherent replay the board can render.
        assertEquals(4, result.replay.positions.size)
        assertEquals(
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
            result.replay.finalPosition.toFen(),
        )
    }

    @Test
    fun lenientSanReplayReportsNoTruncationForACleanGame() {
        val result = Chess.replaySanLenient("e4 e5 Nf3")
        assertTrue(!result.truncated)
        assertEquals(null, result.unmatchedToken)
        assertEquals(3, result.movesReplayed)
        assertEquals(3, result.totalTokens)
    }

    @Test
    fun lenientSanReplayStoppingAtTheFirstTokenYieldsNoMoves() {
        // Distinct from an empty move list: nothing parsed, so the review screen shows its
        // "can't be reviewed" message rather than a lone start position.
        val result = Chess.replaySanLenient("Qz9 e4")
        assertTrue(result.truncated)
        assertEquals(0, result.movesReplayed)
        assertTrue(result.replay.steps.isEmpty())

        val empty = Chess.replaySanLenient("")
        assertTrue(!empty.truncated)
        assertEquals(0, empty.movesReplayed)
    }

    @Test
    fun realHordeGameWithBlackCastlingReplaysInFull() {
        // Regression for the reported "this game can't be reviewed here" bug: SAN taken
        // verbatim from a real Lichess Horde game (game h0FrqJB7, /game/export). The token
        // that used to kill the replay is Black's "O-O" — castling was switched off for the
        // whole Horde variant even though only WHITE is kingless. A Horde game where Black
        // never castled replayed fine, which is why only SOME Horde games failed.
        //
        // Note the export carries NO initialFen for Horde, so the replay must start from
        // Variant.HORDE.startFen — exactly what ReviewViewModel substitutes.
        val moves = "a5 e6 d5 exd5 cxd5 d6 c6 a6 cxb7 Bxb7 bxa6 Rxa6 c4 c6 d4 Ne7 f6 gxf6 " +
            "gxf6 Nxd5 exd5 cxd5 g5 dxc4 bxc4 d5 c5 Nc6 b3 Nxa5 bxa5 Rxa5 b2 Qa8 a4 Bc6 " +
            "a3 Bxa4 bxa4 Rxa4 f5 Rxa3 bxa3 Qxa3 g4 Bxc5 dxc5 Qxc5 f4 Qa5 h5 Qxa1 f3 Qxc1 " +
            "g6 O-O g5 Qxd1"
        val expected = moves.trim().split(" ").size

        val result = Chess.replaySanLenient(moves, Variant.HORDE.startFen, Variant.HORDE)
        assertEquals(null, result.unmatchedToken, "every token should match")
        assertEquals(expected, result.movesReplayed)
        assertEquals("O-O", result.replay.steps[55].san, "black castles kingside on ply 56")
    }

    @Test
    fun lenientSanReplayHonoursTheVariantStartPosition() {
        // Horde starts from its own fixed FEN (the only unblocked white pawns are the four
        // on rank 5), so the replay must run under the HORDE path to match at all.
        val result = Chess.replaySanLenient("b6 Nf6", Variant.HORDE.startFen, Variant.HORDE)
        assertTrue(!result.truncated)
        assertEquals(2, result.movesReplayed)
        assertEquals(Variant.HORDE, result.replay.initial.variant)
    }

    @Test
    fun legalDestinationsMatchesFacade() {
        val dests = Chess.legalDestinations(Position.START, Square.fromName("g1")!!)
        assertEquals(setOf(Square.fromName("f3"), Square.fromName("h3")), dests)
    }
}
