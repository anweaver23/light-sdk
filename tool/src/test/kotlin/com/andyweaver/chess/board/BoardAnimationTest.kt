package com.andyweaver.chess.board

import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.engine.Color
import com.andyweaver.chess.engine.Piece
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Replay
import com.andyweaver.chess.engine.Square
import com.andyweaver.chess.engine.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [stepAnim] decides what slides for one position-to-position transition, for every
 * screen that draws a board (live game, analysis sandbox, review, in-person game). It is
 * pure, so all of it is checked here without Compose or an emulator — which matters
 * because a wrong slide is nearly invisible in a screenshot.
 *
 * The two things a slide has to get right, and which regressed in the past:
 *   • a CAPTURE must keep the captured piece on the board until the capturer lands, which
 *     the model expresses as [AnimatedMove.preMoveBoard] (the board rendered for the
 *     duration of the slide);
 *   • a CASTLE moves two pieces, so it must produce two concurrent slides — the rook must
 *     not be sitting on its destination before the king starts moving.
 */
class BoardAnimationTest {

    private fun sq(name: String) = Square.fromName(name)!!

    private fun replay(moves: String, fen: String? = null, variant: Variant = Variant.STANDARD): Replay =
        Chess.replay(moves, fen, variant)

    /** The animation for stepping FORWARD into the last position of [replay]. */
    private fun arrival(replay: Replay): AnimatedMove? {
        val last = replay.positions.lastIndex
        return stepAnim(replay, last - 1, last, id = 1L)
    }

    /** [PieceSlide] as "from→to", for readable assertions. */
    private fun AnimatedMove.paths(): Set<String> =
        slides.mapTo(HashSet()) { "${Square.name(it.startSquare)}${Square.name(it.endSquare)}" }

    private fun AnimatedMove.slideAt(from: String, to: String): PieceSlide? =
        slides.firstOrNull { it.startSquare == sq(from) && it.endSquare == sq(to) }

    // ----- ordinary moves ---------------------------------------------------

    @Test
    fun quietMoveIsOneSlideOfTheMovedPiece() {
        val anim = assertNotNull(arrival(replay("e2e4")))
        assertEquals(setOf("e2e4"), anim.paths())
        assertEquals(Piece(Color.WHITE, PieceType.PAWN), anim.slides.single().piece)
        // Nothing is being taken, so the static board can be rendered as-is throughout.
        assertNull(anim.preMoveBoard, "a quiet move needs no pre-move board")
    }

    @Test
    fun backwardStepReversesTheMoveJustUndone() {
        val r = replay("e2e4 e7e5")
        val last = r.positions.lastIndex
        val anim = assertNotNull(stepAnim(r, last, last - 1, id = 1L))
        // The piece that moved on the step being undone slides back home; nothing else moves.
        assertEquals(setOf("e5e7"), anim.paths())
        assertEquals(Piece(Color.BLACK, PieceType.PAWN), anim.slides.single().piece)
    }

    @Test
    fun nonAdjacentTransitionsAndDropsDoNotAnimate() {
        val r = replay("e2e4 e7e5 g1f3")
        assertNull(stepAnim(r, 0, 3, id = 1L), "a multi-step jump snaps")
        assertNull(stepAnim(r, 3, 0, id = 1L), "a multi-step rewind snaps")
        // A Crazyhouse drop has no origin square to slide from.
        val drops = replay(
            "e2e4 d7d5 e4d5 d8d5 b1c3 d5d8 P@e5",
            fen = null,
            variant = Variant.CRAZYHOUSE,
        )
        val last = drops.positions.lastIndex
        assertTrue(drops.steps.last().move.isDrop, "the last move really is a drop")
        assertNull(stepAnim(drops, last - 1, last, id = 1L), "a drop appears rather than slides")
    }

    // ----- captures ---------------------------------------------------------

    @Test
    fun captureKeepsTheCapturedPieceOnTheBoardUntilTheCapturerLands() {
        // 1. e4 d5 2. exd5 — the white pawn slides e4→d5 onto a black pawn.
        val anim = assertNotNull(arrival(replay("e2e4 d7d5 e4d5")))
        assertEquals(setOf("e4d5"), anim.paths())

        // The board rendered DURING the slide is the pre-move one, minus the mover (the
        // overlay draws that): so the captured black pawn is still visible on d5 until the
        // slide settles, at which point the UI switches back to state.board.
        val during = assertNotNull(anim.preMoveBoard, "a capture renders the pre-move board")
        assertEquals(Piece(Color.BLACK, PieceType.PAWN), during[sq("d5")], "captured pawn still shown")
        assertNull(during[sq("e4")], "the capturer is lifted off its origin (the overlay draws it)")
    }

    @Test
    fun enPassantKeepsTheCapturedPawnOnItsOwnSquare() {
        // 1. e4 a6 2. e5 d5 3. exd6 e.p. — the capture SQUARE (d6) and the captured pawn's
        // square (d5) differ, which is the case a naive "clear the destination" would miss.
        val r = replay("e2e4 a7a6 e4e5 d7d5 e5d6")
        assertTrue(r.steps.last().move.isEnPassant, "the last move really is en passant")
        val anim = assertNotNull(arrival(r))
        assertEquals(setOf("e5d6"), anim.paths(), "still a single slide, to the capture square")

        val during = assertNotNull(anim.preMoveBoard)
        assertEquals(Piece(Color.BLACK, PieceType.PAWN), during[sq("d5")], "the taken pawn stays on d5")
        assertNull(during[sq("d6")], "the square landed on is empty in en passant")
        assertNull(during[sq("e5")], "the capturer is lifted off its origin")
    }

    @Test
    fun steppingBackOutOfACaptureReverseSlidesOverThePlainBoard() {
        val r = replay("e2e4 d7d5 e4d5")
        val last = r.positions.lastIndex
        val anim = assertNotNull(stepAnim(r, last, last - 1, id = 1L))
        assertEquals(setOf("d5e4"), anim.paths(), "the capturer slides back home")
        // Going backward, the destination position already shows the captured pawn, so
        // there is nothing to hold on screen.
        assertNull(anim.preMoveBoard)
    }

    @Test
    fun steppingBackOutOfAnAtomicCaptureSnaps() {
        // An Atomic capture removes several pieces at once; a reverse slide would have to
        // un-explode them mid-flight, so it deliberately doesn't animate.
        val r = replay("e5d7", "4k3/3p4/8/4N3/8/8/8/4K3 w - - 0 1", Variant.ATOMIC)
        val last = r.positions.lastIndex
        assertNotNull(stepAnim(r, last - 1, last, id = 1L), "forward still animates")
        assertNull(stepAnim(r, last, last - 1, id = 1L), "backward snaps")
    }

    // ----- castling ---------------------------------------------------------

    @Test
    fun kingsideCastleSlidesKingAndRookTogether() {
        val r = replay("e1g1", "4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        val anim = assertNotNull(arrival(r))
        assertEquals(setOf("e1g1", "h1f1"), anim.paths(), "king AND rook slide")
        assertEquals(Piece(Color.WHITE, PieceType.KING), assertNotNull(anim.slideAt("e1", "g1")).piece)
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), assertNotNull(anim.slideAt("h1", "f1")).piece)
        // Both landing squares are hidden on the static board for the duration (the UI
        // hides every slide's endSquare), so neither piece is drawn twice — and the rook
        // is not left pre-teleported to f1 while only the king moves.
        assertEquals(setOf(sq("g1"), sq("f1")), anim.slides.mapTo(HashSet()) { it.endSquare })
    }

    @Test
    fun queensideCastleSlidesKingAndRookTogether() {
        val r = replay("e1c1", "4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        val anim = assertNotNull(arrival(r))
        assertEquals(setOf("e1c1", "a1d1"), anim.paths())
        assertEquals(Piece(Color.WHITE, PieceType.KING), assertNotNull(anim.slideAt("e1", "c1")).piece)
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), assertNotNull(anim.slideAt("a1", "d1")).piece)
    }

    @Test
    fun blackCastleSlidesItsOwnKingAndRook() {
        val r = replay("e8g8", "r3k2r/8/8/8/8/8/8/4K3 b kq - 0 1")
        val anim = assertNotNull(arrival(r))
        assertEquals(setOf("e8g8", "h8f8"), anim.paths())
        assertEquals(Piece(Color.BLACK, PieceType.KING), assertNotNull(anim.slideAt("e8", "g8")).piece)
        assertEquals(Piece(Color.BLACK, PieceType.ROOK), assertNotNull(anim.slideAt("h8", "f8")).piece)
    }

    @Test
    fun chess960CastleUsesTheTrueDestinationsNotTheKingOntoRookEncoding() {
        // Chess960 records a castle as king-onto-rook, so move.to is the ROOK's square
        // (h1) — animating "move.from → move.to" would slide the king onto h1.
        val r = replay("e1h1", "4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1", Variant.CHESS960)
        assertTrue(r.steps.last().move.isCastle)
        val anim = assertNotNull(arrival(r))
        assertEquals(setOf("e1g1", "h1f1"), anim.paths())
        assertEquals(Piece(Color.WHITE, PieceType.KING), assertNotNull(anim.slideAt("e1", "g1")).piece)
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), assertNotNull(anim.slideAt("h1", "f1")).piece)
    }

    @Test
    fun chess960CastleFromOffCornerFilesSlidesBothPieces() {
        // A genuine 960 back rank: king on b1, rooks on a1/g1. Castling kingside is
        // encoded b1g1 and must resolve to king b1→g1 and rook g1→f1.
        val r = replay("b1g1", "4k3/8/8/8/8/8/8/RK4R1 w KQ - 0 1", Variant.CHESS960)
        assertTrue(r.steps.last().move.isCastle, "b1g1 parses as a 960 castle")
        val anim = assertNotNull(arrival(r))
        assertEquals(setOf("b1g1", "g1f1"), anim.paths())
        assertEquals(Piece(Color.WHITE, PieceType.KING), assertNotNull(anim.slideAt("b1", "g1")).piece)
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), assertNotNull(anim.slideAt("g1", "f1")).piece)
    }

    @Test
    fun steppingBackOutOfACastleSlidesBothPiecesHome() {
        val r = replay("e1g1", "4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")
        val last = r.positions.lastIndex
        val anim = assertNotNull(stepAnim(r, last, last - 1, id = 1L))
        assertEquals(setOf("g1e1", "f1h1"), anim.paths(), "king and rook both slide back")
        assertEquals(Piece(Color.WHITE, PieceType.KING), assertNotNull(anim.slideAt("g1", "e1")).piece)
        assertEquals(Piece(Color.WHITE, PieceType.ROOK), assertNotNull(anim.slideAt("f1", "h1")).piece)
    }

    // ----- promotion --------------------------------------------------------

    // A pawn is what crosses the board; the promoted piece only exists once it lands.
    // Reading the slide's piece off the DESTINATION board (which is what every other move
    // wants) slid a fully-formed queen instead, so the promotion was over before the
    // animation began.

    @Test
    fun promotionSlidesThePawnNotThePromotedPiece() {
        val anim = assertNotNull(arrival(replay("a7a8q", "4k3/P7/8/8/8/8/8/4K3 w - - 0 1")))
        assertEquals(setOf("a7a8"), anim.paths())
        assertEquals(Piece(Color.WHITE, PieceType.PAWN), anim.slides.single().piece)
    }

    @Test
    fun capturePromotionSlidesThePawnAndKeepsTheCapturedPieceVisible() {
        val anim = assertNotNull(arrival(replay("a7b8q", "1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1")))
        assertEquals(setOf("a7b8"), anim.paths())
        assertEquals(Piece(Color.WHITE, PieceType.PAWN), anim.slides.single().piece)
        // Capture rules still apply: the pre-move board is rendered so the taken rook stays
        // put until the pawn lands, with the mover lifted off its origin.
        val pre = assertNotNull(anim.preMoveBoard)
        assertEquals(Piece(Color.BLACK, PieceType.ROOK), pre[sq("b8")])
        assertNull(pre[sq("a7")])
    }

    @Test
    fun promotionToAKnightAlsoSlidesThePawn() {
        // Underpromotion goes down the same path — nothing may key off "queen".
        val anim = assertNotNull(arrival(replay("a7a8n", "4k3/P7/8/8/8/8/8/4K3 w - - 0 1")))
        assertEquals(Piece(Color.WHITE, PieceType.PAWN), anim.slides.single().piece)
    }

    @Test
    fun steppingBackOutOfAPromotionSlidesThePawnHome() {
        // The backward case never needed a special case — its destination board is the
        // earlier position, where the piece is already a pawn. Locked down so the forward
        // fix can't be "simplified" into breaking it.
        val r = replay("a7a8q", "4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        val last = r.positions.lastIndex
        val anim = assertNotNull(stepAnim(r, last, last - 1, id = 1L))
        assertEquals(setOf("a8a7"), anim.paths())
        assertEquals(Piece(Color.WHITE, PieceType.PAWN), anim.slides.single().piece)
    }

    // ----- id plumbing ------------------------------------------------------

    @Test
    fun theCallersIdIsCarriedThrough() {
        // The UI keys its Animatable off AnimatedMove.id, so every distinct transition has
        // to carry the id its caller assigned — including the capture path.
        assertEquals(7L, assertNotNull(stepAnim(replay("e2e4"), 0, 1, id = 7L)).id)
        val capture = replay("e2e4 d7d5 e4d5")
        assertEquals(9L, assertNotNull(stepAnim(capture, 2, 3, id = 9L)).id)
    }
}
