package com.andyweaver.chess.engine

import com.andyweaver.chess.board.LocalGameViewModel.EndAction
import com.andyweaver.chess.board.outcomeForEndAction
import com.andyweaver.chess.board.pgnResultToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-person game's human-decided endings (resignation / agreed draw). These never come
 * from the engine, so the only thing standing between "White resigns" and a WRONG result
 * being uploaded to Lichess is this mapping and the PGN result token derived from it.
 */
class LocalGameEndTest {

    @Test
    fun `white resigning gives black the win`() {
        val outcome = outcomeForEndAction(EndAction.WHITE_RESIGNS)
        assertEquals(GameOutcome.Decided(Color.BLACK, OutcomeReason.RESIGNATION), outcome)
        assertEquals("0-1", pgnResultToken(outcome))
    }

    @Test
    fun `black resigning gives white the win`() {
        val outcome = outcomeForEndAction(EndAction.BLACK_RESIGNS)
        assertEquals(GameOutcome.Decided(Color.WHITE, OutcomeReason.RESIGNATION), outcome)
        assertEquals("1-0", pgnResultToken(outcome))
    }

    @Test
    fun `an agreed draw has no winner`() {
        val outcome = outcomeForEndAction(EndAction.AGREE_DRAW)
        assertEquals(GameOutcome.Decided(null, OutcomeReason.DRAW_AGREED), outcome)
        assertEquals("1/2-1/2", pgnResultToken(outcome))
    }

    @Test
    fun `an ongoing game has no result token`() {
        assertNull(pgnResultToken(GameOutcome.Ongoing))
    }

    /**
     * [Pgn.export] asks the engine for the result, and the engine cannot see a resignation —
     * an unfinished move list exports as "*". Both the tag and the movetext terminator have
     * to be overridden, or the uploaded game records the wrong result.
     */
    @Test
    fun `resignation overrides the engine's open-ended pgn`() {
        val replay = Chess.replay(listOf("e2e4", "e7e5"), null, Variant.STANDARD)
        val enginePgn = Chess.toPgn(replay)
        assertTrue("engine should see this game as unfinished", enginePgn.contains("[Result \"*\"]"))
        assertTrue(enginePgn.trimEnd().endsWith("*"))

        // The override has to reach BOTH places PGN carries the result: the tag and the
        // movetext terminator. Lichess reads the terminator, so a tag-only fix would still
        // import the game as unfinished.
        val token = pgnResultToken(outcomeForEndAction(EndAction.WHITE_RESIGNS))!!
        val pgn = Chess.toPgn(replay, resultOverride = token)
        assertTrue(pgn.contains("[Result \"0-1\"]"))
        assertTrue(pgn.trimEnd().endsWith("1. e4 e5 0-1"))
    }

    @Test
    fun `an agreed draw exports as a draw rather than unfinished`() {
        val replay = Chess.replay(listOf("e2e4", "e7e5"), null, Variant.STANDARD)
        val token = pgnResultToken(outcomeForEndAction(EndAction.AGREE_DRAW))!!
        val pgn = Chess.toPgn(replay, resultOverride = token)
        assertTrue(pgn.contains("[Result \"1/2-1/2\"]"))
        assertTrue(pgn.trimEnd().endsWith("1. e4 e5 1/2-1/2"))
    }

    /** No override: the engine's own verdict still wins, so normal endings are untouched. */
    @Test
    fun `without an override the engine verdict is used`() {
        // Fool's mate: 1. f3 e5 2. g4 Qh4#
        val replay = Chess.replay(listOf("f2f3", "e7e5", "g2g4", "d8h4"), null, Variant.STANDARD)
        val pgn = Chess.toPgn(replay)
        assertTrue(pgn.contains("[Result \"0-1\"]"))
        assertTrue(pgn.trimEnd().endsWith("0-1"))
    }
}
