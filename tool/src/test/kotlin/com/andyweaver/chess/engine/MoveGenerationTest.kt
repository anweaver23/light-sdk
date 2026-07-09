package com.andyweaver.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoveGenerationTest {

    private fun perft(position: Position, depth: Int): Long {
        if (depth == 0) return 1L
        val moves = MoveGenerator.legalMoves(position)
        if (depth == 1) return moves.size.toLong()
        var total = 0L
        for (m in moves) {
            total += perft(MoveGenerator.applyMove(position, m), depth - 1)
        }
        return total
    }

    @Test
    fun startPositionHas20LegalMoves() {
        assertEquals(20, MoveGenerator.legalMoves(Position.START).size)
    }

    @Test
    fun startPositionPerft() {
        // Well-known perft values validate the whole generator end to end.
        assertEquals(20L, perft(Position.START, 1))
        assertEquals(400L, perft(Position.START, 2))
        assertEquals(8902L, perft(Position.START, 3))
    }

    @Test
    fun kiwipetePerftExercisesCastlingEnPassantAndPins() {
        // Classic "Kiwipete" position; hits castling, en passant, pins, promotions.
        val kiwipete = Position.fromFen(
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        )
        assertEquals(48L, perft(kiwipete, 1))
        assertEquals(2039L, perft(kiwipete, 2))
    }

    @Test
    fun knightMovesFromCorners() {
        val p = Position.fromFen("4k3/8/8/8/8/8/8/N3K2N w - - 0 1")
        // a1 knight: b3, c2. h1 knight: f2, g3.
        assertEquals(setOf(Square.fromName("b3"), Square.fromName("c2")),
            MoveGenerator.legalDestinations(p, Square.fromName("a1")!!))
        assertEquals(setOf(Square.fromName("f2"), Square.fromName("g3")),
            MoveGenerator.legalDestinations(p, Square.fromName("h1")!!))
    }

    @Test
    fun slidingPieceMovesAndBlocking() {
        // Rook on d4 in an open board (kings out of the way).
        val p = Position.fromFen("4k3/8/8/8/3R4/8/8/4K3 w - - 0 1")
        val dests = MoveGenerator.legalDestinations(p, Square.fromName("d4")!!)
        // 14 squares along the rank and file.
        assertEquals(14, dests.size)
        assertTrue(dests.contains(Square.fromName("d8")))
        assertTrue(dests.contains(Square.fromName("a4")))
        assertTrue(dests.contains(Square.fromName("h4")))
    }

    @Test
    fun pinnedKnightCannotMove() {
        // Black rook on e8 pins the white knight on e2 to the king on e1.
        val p = Position.fromFen("4r1k1/8/8/8/8/8/4N3/4K3 w - - 0 1")
        assertTrue(MoveGenerator.legalDestinations(p, Square.fromName("e2")!!).isEmpty())
    }

    @Test
    fun pinnedRookMayMoveAlongPinAndCapturePinner() {
        // White rook on e4 pinned by black rook e8; may slide on the e-file and take it.
        val p = Position.fromFen("4r1k1/8/8/8/4R3/8/8/4K3 w - - 0 1")
        val dests = MoveGenerator.legalDestinations(p, Square.fromName("e4")!!)
        assertTrue(dests.all { Square.file(it) == 4 }, "pinned rook must stay on e-file")
        assertTrue(dests.contains(Square.fromName("e8")), "may capture the pinning rook")
        assertFalse(dests.contains(Square.fromName("d4")), "may not leave the pin line")
    }

    @Test
    fun castlingBothSidesAvailable() {
        val p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val kingMoves = MoveGenerator.legalDestinations(p, Square.fromName("e1")!!)
        assertTrue(kingMoves.contains(Square.fromName("g1")), "king-side castle")
        assertTrue(kingMoves.contains(Square.fromName("c1")), "queen-side castle")
    }

    @Test
    fun castlingBlockedWhenKingPassesThroughAttackedSquare() {
        // Black rook on f8 attacks f1, so king-side castling (through f1) is illegal;
        // queen-side remains legal.
        val p = Position.fromFen("5rk1/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        val kingMoves = MoveGenerator.legalDestinations(p, Square.fromName("e1")!!)
        assertFalse(kingMoves.contains(Square.fromName("g1")), "cannot castle through attacked f1")
        assertTrue(kingMoves.contains(Square.fromName("c1")), "queen-side still legal")
    }

    @Test
    fun cannotCastleWhileInCheck() {
        // Black rook on e8 checks the white king; no castling allowed.
        val p = Position.fromFen("4r1k1/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        val kingMoves = MoveGenerator.legalDestinations(p, Square.fromName("e1")!!)
        assertFalse(kingMoves.contains(Square.fromName("g1")))
        assertFalse(kingMoves.contains(Square.fromName("c1")))
    }

    @Test
    fun castlingMovesTheRook() {
        val p = Position.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        val castle = MoveGenerator.legalMoves(p)
            .first { it.isCastle && Square.file(it.to) == 6 }
        val after = MoveGenerator.applyMove(p, castle)
        assertEquals(Piece(Color.WHITE, PieceType.KING), after.pieceAt(Square.fromName("g1")!!))
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), after.pieceAt(Square.fromName("f1")!!))
        assertEquals(null, after.pieceAt(Square.fromName("h1")!!))
        // White forfeits both castling rights after castling.
        assertFalse(after.castlingRights.whiteKingSide)
        assertFalse(after.castlingRights.whiteQueenSide)
    }

    @Test
    fun enPassantCaptureAvailableAndRemovesPawn() {
        // White pawn e5, black just played d7-d5; en passant target d6.
        val p = Position.fromFen("rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3")
        val ep = MoveGenerator.legalMoves(p).firstOrNull {
            it.isEnPassant && it.from == Square.fromName("e5") && it.to == Square.fromName("d6")
        }
        assertTrue(ep != null, "en passant capture should be generated")
        val after = MoveGenerator.applyMove(p, ep)
        assertEquals(Piece(Color.WHITE, PieceType.PAWN), after.pieceAt(Square.fromName("d6")!!))
        assertEquals(null, after.pieceAt(Square.fromName("e5")!!))
        assertEquals(null, after.pieceAt(Square.fromName("d5")!!), "captured pawn removed")
    }

    @Test
    fun promotionGeneratesAllFourPieces() {
        val p = Position.fromFen("8/P6k/8/8/8/8/8/K7 w - - 0 1")
        val promos = MoveGenerator.legalMoves(p).filter { it.from == Square.fromName("a7") }
        assertEquals(
            setOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT),
            promos.mapNotNull { it.promotion }.toSet(),
        )
        val queen = promos.first { it.promotion == PieceType.QUEEN }
        val after = MoveGenerator.applyMove(p, queen)
        assertEquals(Piece(Color.WHITE, PieceType.QUEEN), after.pieceAt(Square.fromName("a8")!!))
    }

    @Test
    fun doublePushSetsEnPassantTarget() {
        val e4 = Move.fromUci("e2e4", Position.START)!!
        val after = MoveGenerator.applyMove(Position.START, e4)
        assertEquals(Square.fromName("e3"), after.enPassantTarget)
        assertTrue(e4.isDoublePawnPush)
    }
}
