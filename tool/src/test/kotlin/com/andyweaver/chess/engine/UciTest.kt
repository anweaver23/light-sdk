package com.andyweaver.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UciTest {

    @Test
    fun basicRoundTrip() {
        val m = Move.fromUci("e2e4", Position.START)!!
        assertEquals(Square.fromName("e2"), m.from)
        assertEquals(Square.fromName("e4"), m.to)
        assertEquals("e2e4", m.toUci())
    }

    @Test
    fun promotionRoundTrip() {
        val p = Position.fromFen("8/P6k/8/8/8/8/8/K7 w - - 0 1")
        val m = Move.fromUci("a7a8q", p)!!
        assertEquals(PieceType.QUEEN, m.promotion)
        assertEquals("a7a8q", m.toUci())
    }

    @Test
    fun resolvesEnPassantFlag() {
        val p = Position.fromFen("rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3")
        val m = Move.fromUci("e5d6", p)!!
        assertTrue(m.isEnPassant)
    }

    @Test
    fun resolvesCastleFlag() {
        val p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val m = Move.fromUci("e1g1", p)!!
        assertTrue(m.isCastle)
    }

    @Test
    fun rejectsMalformedOrEmptyFrom() {
        assertNull(Move.fromUci("e2", Position.START))
        assertNull(Move.fromUci("zz99", Position.START))
        assertNull(Move.fromUci("e3e4", Position.START)) // e3 is empty at start
    }

    @Test
    fun allStartMovesRoundTrip() {
        // Every generated move must survive UCI serialize -> parse unchanged.
        for (m in MoveGenerator.legalMoves(Position.START)) {
            val reparsed = Move.fromUci(m.toUci(), Position.START)!!
            assertEquals(m.from, reparsed.from)
            assertEquals(m.to, reparsed.to)
            assertEquals(m.promotion, reparsed.promotion)
        }
    }
}
