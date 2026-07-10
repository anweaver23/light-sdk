package com.andyweaver.chess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rule tests for the supported variants (Atomic, Chess960, Crazyhouse, Antichess, Racing Kings, Horde). */
class VariantTest {

    private fun sq(name: String) = Square.fromName(name)!!

    // ----- Atomic -----------------------------------------------------------

    @Test
    fun atomicCaptureExplodesNeighbours() {
        // White knight e5 captures the d7 pawn; the explosion at d7 also removes the
        // adjacent black king on e8. Pawns are the only pieces spared by the blast.
        val pos = Position.fromFen("4k3/3p4/8/4N3/8/8/8/4K3 w - - 0 1", Variant.ATOMIC)
        val after = Chess.replay("e5d7", pos.toFen(), Variant.ATOMIC).finalPosition
        assertNull(after.pieceAt(sq("d7")), "captured pawn gone")
        assertNull(after.pieceAt(sq("e5")), "capturing knight exploded")
        assertNull(after.pieceAt(sq("e8")), "adjacent king exploded")
        assertEquals(-1, after.kingSquare(Color.BLACK), "black king removed = win")
        assertEquals(Piece(Color.WHITE, PieceType.KING), after.pieceAt(sq("e1")))
    }

    @Test
    fun atomicKingCannotCapture() {
        // King on e1, capturable black pawn on e2. Capturing would explode our own king,
        // so e2 must not be a legal destination (d2/f2 remain).
        val pos = Position.fromFen("4k3/8/8/8/8/8/4p3/4K3 w - - 0 1", Variant.ATOMIC)
        val dests = MoveGenerator.legalDestinations(pos, sq("e1"))
        assertFalse(sq("e2") in dests, "king may not capture (self-explosion)")
        assertTrue(sq("f2") in dests, "empty adjacent square is fine")
    }

    @Test
    fun atomicAdjacentKingsAreNotCheck() {
        // Kings on adjacent squares don't check each other in Atomic.
        val pos = Position.fromFen("8/8/8/3k4/3K4/8/8/8 w - - 0 1", Variant.ATOMIC)
        assertFalse(Chess.isInCheck(pos))
    }

    // ----- Chess960 ---------------------------------------------------------

    @Test
    fun chess960KingsideCastleFromKingOntoRook() {
        val pos = Position.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQkq - 0 1", Variant.CHESS960)
        val after = Chess.replay("e1h1", pos.toFen(), Variant.CHESS960).finalPosition
        assertEquals(Piece(Color.WHITE, PieceType.KING), after.pieceAt(sq("g1")))
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), after.pieceAt(sq("f1")))
        assertNull(after.pieceAt(sq("h1")))
        assertNull(after.pieceAt(sq("e1")))
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), after.pieceAt(sq("a1")), "queenside rook untouched")
    }

    @Test
    fun chess960QueensideCastle() {
        val pos = Position.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQkq - 0 1", Variant.CHESS960)
        val after = Chess.replay("e1a1", pos.toFen(), Variant.CHESS960).finalPosition
        assertEquals(Piece(Color.WHITE, PieceType.KING), after.pieceAt(sq("c1")))
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), after.pieceAt(sq("d1")))
        assertNull(after.pieceAt(sq("a1")))
    }

    @Test
    fun chess960CastleIsAOfferedDestination() {
        val pos = Position.fromFen("4k3/8/8/8/8/8/8/R3K2R w KQkq - 0 1", Variant.CHESS960)
        val dests = MoveGenerator.legalDestinations(pos, sq("e1"))
        assertTrue(sq("h1") in dests, "kingside castle target is the rook square")
        assertTrue(sq("a1") in dests, "queenside castle target is the rook square")
    }

    @Test
    fun chess960FileLetterCastlingRightsParse() {
        // Rooks on the b- and g-files (either side of the e-file king).
        val pos = Position.fromFen("nrbnkbrq/pppppppp/8/8/8/8/PPPPPPPP/NRBNKBRQ w GBgb - 0 1", Variant.CHESS960)
        assertTrue(pos.castlingRights.whiteKingSide)
        assertTrue(pos.castlingRights.whiteQueenSide)
        assertTrue(pos.castlingRights.blackKingSide)
        assertTrue(pos.castlingRights.blackQueenSide)
    }

    // ----- Crazyhouse -------------------------------------------------------

    @Test
    fun crazyhouseDropPlacesPieceAndSpendsPocket() {
        val fen = "4k3/8/8/8/8/8/8/4K3[Qq] w - - 0 1"
        val pos = Position.fromFen(fen, Variant.CRAZYHOUSE)
        assertEquals(1, pos.pocket.count(Color.WHITE, PieceType.QUEEN))
        val after = Chess.replay("Q@d4", fen, Variant.CRAZYHOUSE).finalPosition
        assertEquals(Piece(Color.WHITE, PieceType.QUEEN), after.pieceAt(sq("d4")))
        assertEquals(0, after.pocket.count(Color.WHITE, PieceType.QUEEN))
    }

    @Test
    fun crazyhouseCaptureGoesToPocket() {
        // White knight g1 captures the black bishop on f3 -> a bishop enters White's pocket.
        val pos = Position.fromFen("4k3/8/8/8/8/5b2/8/4K1N1 w - - 0 1", Variant.CRAZYHOUSE)
        val after = Chess.replay("g1f3", pos.toFen(), Variant.CRAZYHOUSE).finalPosition
        assertEquals(Piece(Color.WHITE, PieceType.KNIGHT), after.pieceAt(sq("f3")))
        assertEquals(1, after.pocket.count(Color.WHITE, PieceType.BISHOP))
    }

    @Test
    fun crazyhousePawnsCannotDropOnBackRanks() {
        val pos = Position.fromFen("4k3/8/8/8/8/8/8/4K3[Pp] w - - 0 1", Variant.CRAZYHOUSE)
        val squares = Chess.legalDropSquares(pos, PieceType.PAWN)
        assertFalse(squares.any { Square.rank(it) == 0 || Square.rank(it) == 7 }, "no pawn drops on 1st/8th")
        assertTrue(sq("d4") in squares)
    }

    // ----- Antichess --------------------------------------------------------

    @Test
    fun antichessCapturesAreForced() {
        // Only capture available is Kxd2; no non-capture king moves are legal.
        val pos = Position.fromFen("4k3/8/8/8/8/8/3p4/4K3 w - - 0 1", Variant.ANTICHESS)
        val moves = MoveGenerator.legalMoves(pos)
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.to == sq("d2") }, "only the forced capture is legal")
    }

    @Test
    fun antichessAllowsKingPromotion() {
        val pos = Position.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1", Variant.ANTICHESS)
        val moves = MoveGenerator.legalMoves(pos)
        assertTrue(moves.any { it.promotion == PieceType.KING }, "pawn may promote to king")
    }

    // ----- Racing Kings -----------------------------------------------------

    @Test
    fun racingKingsForbidsGivingCheck() {
        // Rook on a3, black king on h8. Ra3-h3 would check along the h-file: illegal.
        val pos = Position.fromFen("7k/8/8/8/8/R7/8/4K3 w - - 0 1", Variant.RACING_KINGS)
        val dests = MoveGenerator.legalDestinations(pos, sq("a3"))
        assertFalse(sq("h3") in dests, "a move that gives check is forbidden")
        assertTrue(sq("g3") in dests, "a non-checking rook move is fine")
    }

    // ----- Horde ------------------------------------------------------------

    @Test
    fun hordeFirstRankPawnMayDoublePush() {
        val pos = Position.fromFen("4k3/8/8/8/8/8/8/P3K3 w - - 0 1", Variant.HORDE)
        val dests = MoveGenerator.legalDestinations(pos, sq("a1"))
        assertTrue(sq("a3") in dests, "first-rank Horde pawn double push")
    }

    @Test
    fun hordeFirstRankDoublePushCreatesNoEnPassant() {
        val after = Chess.replay("a1a3", "4k3/8/8/8/8/8/8/P3K3 w - - 0 1", Variant.HORDE).finalPosition
        assertNull(after.enPassantTarget, "first-rank double push is not a valid en-passant target")
    }

    // ----- hardening: Chess960 perft + off-corner castling rights -----------

    private fun perft(pos: Position, depth: Int): Long {
        if (depth == 0) return 1L
        var nodes = 0L
        for (m in MoveGenerator.legalMoves(pos)) nodes += perft(MoveGenerator.applyMove(pos, m), depth - 1)
        return nodes
    }

    @Test
    fun chess960PerftMatchesStandardOnKiwipete() {
        // "Kiwipete" is a castling-rich position with known perft values. Run it under
        // the CHESS960 code path (king-onto-rook castling, outermost-rook logic): the
        // legal-move tree must be identical to standard chess, so the counts must match.
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        val pos = Position.fromFen(fen, Variant.CHESS960)
        assertEquals(48L, perft(pos, 1))
        assertEquals(2039L, perft(pos, 2))
        assertEquals(97862L, perft(pos, 3))
    }

    @Test
    fun chess960OffCornerCastlingRookClearsItsRight() {
        // King e1, castling rooks on a1 (queenside) and g1 (kingside, NOT the corner).
        // Moving the g1 rook must clear only the kingside right; queenside stays.
        val after = Chess.replay("g1g5", "4k3/8/8/8/8/8/8/R3K1R1 w KQ - 0 1", Variant.CHESS960).finalPosition
        assertFalse(after.castlingRights.whiteKingSide, "off-corner castling rook moved -> right cleared")
        assertTrue(after.castlingRights.whiteQueenSide, "the other right is untouched")
    }

    // ----- hardening: Atomic + Antichess edge cases -------------------------

    @Test
    fun atomicCaptureNextToOwnKingIsIllegal() {
        // Rook d1 could capture the d2 knight, but the explosion sits next to our own
        // king on e1 — so it's illegal (self-explosion). Other rook moves stay legal.
        val pos = Position.fromFen("4k3/8/8/8/8/8/3n4/3RK3 w - - 0 1", Variant.ATOMIC)
        val dests = MoveGenerator.legalDestinations(pos, sq("d1"))
        assertFalse(sq("d2") in dests, "capture next to own king would self-explode")
        assertTrue(sq("c1") in dests, "an ordinary rook move is fine")
    }

    @Test
    fun atomicExplosionBeatsCheck() {
        // White is in check from the c5 bishop, but Nxg7 explodes the black king on h8
        // (winning). Exploding the enemy king takes precedence over being in check, so
        // it's legal; a knight move that neither blocks nor wins (Nf4) is not.
        val pos = Position.fromFen("7k/6p1/4N3/2b5/8/8/8/6K1 w - - 0 1", Variant.ATOMIC)
        assertTrue(Chess.isInCheck(pos), "white is in atomic check")
        val dests = MoveGenerator.legalDestinations(pos, sq("e6"))
        assertTrue(sq("g7") in dests, "capturing to explode the enemy king wins, even in check")
        assertFalse(sq("f4") in dests, "a move that doesn't resolve check or win is illegal")
    }

    @Test
    fun antichessEnPassantCaptureIsForced() {
        // The only available capture is en passant (exd6); it must be forced.
        val pos = Position.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", Variant.ANTICHESS)
        val moves = MoveGenerator.legalMoves(pos)
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.isEnPassant && it.to == sq("d6") }, "forced to take en passant")
    }
}
