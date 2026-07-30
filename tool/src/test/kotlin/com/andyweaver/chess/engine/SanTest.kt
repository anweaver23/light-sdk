package com.andyweaver.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class SanTest {

    private fun san(fen: String, uci: String): String {
        val p = Position.fromFen(fen)
        val m = Move.fromUci(uci, p)!!
        return San.of(p, m)
    }

    @Test
    fun chess960CastlingSanUsesTheKingsSideNotTheRookSquare() {
        // Chess960 encodes castling king-ONTO-ROOK, so move.to is the rook's square. Reading
        // its file directly called every h-file kingside castle "O-O-O", which no Lichess
        // SAN token matches — the review replay died at the castle.
        val fen = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1"
        val pos = Position.fromFen(fen, Variant.CHESS960)
        fun san960(uci: String) = San.of(pos, Move.fromUci(uci, pos)!!)
        assertEquals("O-O", san960("e1h1"), "king onto the h-file rook is kingside")
        assertEquals("O-O-O", san960("e1a1"), "king onto the a-file rook is queenside")
        // The standard encoding (king to the g/c file) still reads the same way.
        assertEquals("O-O", san(fen, "e1g1"))
        assertEquals("O-O-O", san(fen, "e1c1"))
    }

    @Test
    fun pawnPushAndPieceMove() {
        assertEquals("e4", san(Position.START_FEN, "e2e4"))
        assertEquals("Nf3", san(Position.START_FEN, "g1f3"))
    }

    // sanForLastMove reconstructs the pre-move position from only the resulting FEN + UCI
    // (what the home screen has). Each fen below is the position AFTER the given move.
    @Test
    fun sanForLastMove_reconstructsFromResultingFen() {
        // 1. e4
        assertEquals(
            "e4",
            Chess.sanForLastMove("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", "e2e4"),
        )
        // 2. Nf3 (non-pawn, non-capture: halfmove clock is 1)
        assertEquals(
            "Nf3",
            Chess.sanForLastMove("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2", "g1f3"),
        )
        // exd5 (pawn capture: file changes)
        assertEquals(
            "exd5",
            Chess.sanForLastMove("rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2", "e4d5"),
        )
        // O-O (white kingside castle)
        assertEquals(
            "O-O",
            Chess.sanForLastMove("rnbqk2r/pppp1ppp/5n2/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQ1RK1 b kq - 5 4", "e1g1"),
        )
        // a8=Q (promotion, no check — black king parked off the lines)
        assertEquals(
            "a8=Q",
            Chess.sanForLastMove("Q7/8/8/4k3/8/8/8/K7 b - - 0 1", "a7a8q"),
        )
        // Malformed / mismatched input falls back to null.
        assertEquals(null, Chess.sanForLastMove(Position.START_FEN, "wxyz"))
    }

    @Test
    fun pawnCaptureUsesFile() {
        // White e4 pawn takes on d5.
        assertEquals("exd5", san("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1", "e4d5"))
    }

    @Test
    fun pieceCaptureUsesX() {
        // Knight on f3 takes on e5.
        assertEquals("Nxe5", san("4k3/8/8/4p3/8/5N2/8/4K3 w - - 0 1", "f3e5"))
    }

    @Test
    fun castlingNotation() {
        val fen = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        assertEquals("O-O", san(fen, "e1g1"))
        assertEquals("O-O-O", san(fen, "e1c1"))
    }

    @Test
    fun promotionNotation() {
        assertEquals("a8=Q", san("8/P6k/8/8/8/8/8/K7 w - - 0 1", "a7a8q"))
        assertEquals("a8=N", san("8/P6k/8/8/8/8/8/K7 w - - 0 1", "a7a8n"))
    }

    @Test
    fun fileDisambiguation() {
        // Rooks on a8 and h8 both reach d8.
        val fen = "R6R/8/8/8/4K3/8/8/4k3 w - - 0 1"
        assertEquals("Rad8", san(fen, "a8d8"))
        assertEquals("Rhd8", san(fen, "h8d8"))
    }

    @Test
    fun rankDisambiguation() {
        // Rooks on a1 and a8 both reach a4 (files identical -> disambiguate by rank).
        // Black king on h5 so it isn't incidentally in check (which would add "+").
        val fen = "R7/8/8/7k/8/8/8/R3K3 w - - 0 1"
        assertEquals("R1a4", san(fen, "a1a4"))
        assertEquals("R8a4", san(fen, "a8a4"))
    }

    @Test
    fun checkAndCheckmateSuffix() {
        // Rook to e8 checks the black king on a8 along the 8th rank; king can flee.
        val checkFen = "k7/8/8/8/8/8/8/4R1K1 w - - 0 1"
        assertEquals("Re8+", san(checkFen, "e1e8"))

        // Back-rank mate: rook to e8 mates the boxed-in black king.
        val mateFen = "6k1/5ppp/8/8/8/8/8/4R1K1 w - - 0 1"
        assertEquals("Re8#", san(mateFen, "e1e8"))
    }

    @Test
    fun enPassantSanIsPawnCapture() {
        val fen = "rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3"
        val p = Position.fromFen(fen)
        val m = Move.fromUci("e5d6", p)!!
        assertEquals("exd6", San.of(p, m))
        assertEquals("exd6 e.p.", San.of(p, m, enPassantSuffix = true))
    }
}
