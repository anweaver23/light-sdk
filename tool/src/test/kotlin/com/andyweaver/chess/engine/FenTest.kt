package com.andyweaver.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FenTest {

    @Test
    fun startPositionRoundTrips() {
        val p = Position.fromFen(Position.START_FEN)
        assertEquals(Position.START_FEN, p.toFen())
    }

    @Test
    fun startPositionFields() {
        val p = Position.START
        assertEquals(Color.WHITE, p.sideToMove)
        assertEquals(0, p.halfmoveClock)
        assertEquals(1, p.fullmoveNumber)
        assertNull(p.enPassantTarget)
        assertTrue(p.castlingRights.whiteKingSide)
        assertTrue(p.castlingRights.blackQueenSide)
        // Corner and key squares.
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), p.pieceAt(Square.fromName("a1")!!))
        assertEquals(Piece(Color.BLACK, PieceType.KING), p.pieceAt(Square.fromName("e8")!!))
        assertEquals(Piece(Color.WHITE, PieceType.PAWN), p.pieceAt(Square.fromName("e2")!!))
        assertNull(p.pieceAt(Square.fromName("e4")!!))
    }

    @Test
    fun squareIndexingConventions() {
        assertEquals(0, Square.fromName("a1"))
        assertEquals(7, Square.fromName("h1"))
        assertEquals(63, Square.fromName("h8"))
        assertEquals("a1", Square.name(0))
        assertEquals("h8", Square.name(63))
        assertEquals(4, Square.file(Square.fromName("e4")!!))
        assertEquals(3, Square.rank(Square.fromName("e4")!!))
    }

    @Test
    fun parsesEnPassantAndCounters() {
        val fen = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 5 12"
        val p = Position.fromFen(fen)
        assertEquals(Square.fromName("d6"), p.enPassantTarget)
        assertEquals(5, p.halfmoveClock)
        assertEquals(12, p.fullmoveNumber)
        assertEquals(fen, p.toFen())
    }

    @Test
    fun parsesEmptyCastlingRights() {
        val fen = "4k3/8/8/8/8/8/8/4K3 b - - 0 1"
        val p = Position.fromFen(fen)
        assertEquals(CastlingRights.NONE, p.castlingRights)
        assertEquals(fen, p.toFen())
    }
}
