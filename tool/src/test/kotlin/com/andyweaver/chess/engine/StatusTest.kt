package com.andyweaver.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusTest {

    private fun play(vararg uci: String): Position {
        var p = Position.START
        for (u in uci) {
            val m = Move.fromUci(u, p)!!
            p = MoveGenerator.applyMove(p, m)
        }
        return p
    }

    @Test
    fun startPositionIsOngoing() {
        assertEquals(GameStatus.Ongoing, GameStatusEvaluator.status(Position.START))
        assertFalse(Chess.isInCheck(Position.START))
    }

    @Test
    fun foolsMateIsCheckmate() {
        // 1. f3 e5 2. g4 Qh4#
        val p = play("f2f3", "e7e5", "g2g4", "d8h4")
        assertEquals(GameStatus.Checkmate, GameStatusEvaluator.status(p))
    }

    @Test
    fun scholarsMateIsCheckmate() {
        // 1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6?? 4. Qxf7#
        val p = play("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7")
        assertEquals(GameStatus.Checkmate, GameStatusEvaluator.status(p))
    }

    @Test
    fun stalematePositionDetected() {
        // Black king h8, white queen f7, white king g6; black to move, not in check,
        // no legal moves.
        val p = Position.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertEquals(GameStatus.Stalemate, GameStatusEvaluator.status(p))
    }

    @Test
    fun checkDetectedButNotMate() {
        // Black rook checks white king along the e-file; king can step aside.
        val p = Position.fromFen("4r1k1/8/8/8/8/8/8/4K3 w - - 0 1")
        assertTrue(Chess.isInCheck(p))
        assertEquals(GameStatus.Check, GameStatusEvaluator.status(p))
    }

    @Test
    fun insufficientMaterialKingVsKing() {
        val p = Position.fromFen("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
        assertEquals(GameStatus.Draw(DrawReason.INSUFFICIENT_MATERIAL), GameStatusEvaluator.status(p))
    }

    @Test
    fun insufficientMaterialKingAndBishopVsKing() {
        val p = Position.fromFen("4k3/8/8/8/8/8/8/4KB2 w - - 0 1")
        assertTrue(GameStatusEvaluator.isInsufficientMaterial(p))
    }

    @Test
    fun insufficientMaterialKingAndKnightVsKing() {
        val p = Position.fromFen("4k3/8/8/8/8/8/8/4KN2 w - - 0 1")
        assertTrue(GameStatusEvaluator.isInsufficientMaterial(p))
    }

    @Test
    fun sufficientMaterialWithRook() {
        val p = Position.fromFen("4k3/8/8/8/8/8/8/4KR2 w - - 0 1")
        assertFalse(GameStatusEvaluator.isInsufficientMaterial(p))
    }

    @Test
    fun sufficientMaterialWithPawn() {
        val p = Position.fromFen("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1")
        assertFalse(GameStatusEvaluator.isInsufficientMaterial(p))
    }
}
