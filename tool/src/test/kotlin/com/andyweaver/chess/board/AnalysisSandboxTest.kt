package com.andyweaver.chess.board

import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.engine.Color
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Replay
import com.andyweaver.chess.engine.Square
import com.andyweaver.chess.engine.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review screen's analysis sandbox ([AnalysisSandbox]) holds every rule of the feature —
 * what a tap selects, when a move forks the line, what a reset restores — and is deliberately
 * free of Compose and Android so all of it is checked here rather than on an emulator. The
 * seeding/branching primitives it builds on ([analysisSeed], [forkLine], [stepAnim]) have
 * their own tests; this covers the interaction layer on top of them.
 */
class AnalysisSandboxTest {

    private fun sq(name: String) = Square.fromName(name)!!

    private fun game(moves: String, variant: Variant = Variant.STANDARD, fen: String? = null): Replay =
        Chess.replay(moves, fen, variant)

    private fun sandbox(
        moves: String,
        entryIndex: Int,
        variant: Variant = Variant.STANDARD,
        fen: String? = null,
    ): AnalysisSandbox =
        AnalysisSandbox.seededFrom(game(moves, variant, fen), variant, entryIndex)

    // ----- entering ---------------------------------------------------------

    @Test
    fun opensOnTheReviewedPositionWithTheWholeGameAroundIt() {
        val a = sandbox("e2e4 e7e5 g1f3 b8c6", entryIndex = 2)
        assertEquals(2, a.viewIndex)
        assertEquals(2, a.entryIndex, "the reset point is where it was entered")
        assertEquals(4, a.lastIndex, "the game's later moves are still ahead of it")
        assertEquals(listOf("e2e4", "e7e5", "g1f3", "b8c6"), a.moves)
    }

    @Test
    fun stepsBackPastTheEntryPointAllTheWayToTheGamesStart() {
        // Entered at the final position — the case where a Position-only snapshot would
        // leave nothing behind to step back into.
        val a = sandbox("e2e4 e7e5 g1f3 b8c6 f1b5", entryIndex = 5)
        repeat(5) { a.stepBack() }
        assertEquals(0, a.viewIndex)
        assertEquals(Chess.startPosition.board, a.viewed().board)
        a.stepBack()
        assertEquals(0, a.viewIndex, "already at the start; nothing further back")
    }

    // ----- selecting --------------------------------------------------------

    @Test
    fun tappingAPieceOfTheSideToMoveSelectsItAndExposesItsLegalSquares() {
        val a = sandbox("", entryIndex = 0)
        a.onSquareTap(sq("e2"))
        assertEquals(sq("e2"), a.selectedSquare)
        assertEquals(setOf(sq("e3"), sq("e4")), a.legalDestinations)
    }

    @Test
    fun tappingTheIdleSidesPieceOrAnEmptySquareSelectsNothing() {
        val a = sandbox("", entryIndex = 0)
        a.onSquareTap(sq("e7")) // Black, but it is White to move.
        assertNull(a.selectedSquare)
        a.onSquareTap(sq("e5")) // Empty.
        assertNull(a.selectedSquare)
    }

    @Test
    fun tappingTheSelectedSquareAgainDeselectsIt() {
        val a = sandbox("", entryIndex = 0)
        a.onSquareTap(sq("e2"))
        a.onSquareTap(sq("e2"))
        assertNull(a.selectedSquare)
        assertTrue(a.legalDestinations.isEmpty())
    }

    @Test
    fun eitherColourMayMove_theSideToMoveAtTheViewedPositionIsWhatCounts() {
        // After 1. e4 it is BLACK to move, so black's pieces are the selectable ones even
        // though the reviewer may have played White.
        val a = sandbox("e2e4", entryIndex = 1)
        a.onSquareTap(sq("e2"))
        assertNull(a.selectedSquare, "white just moved; not its turn")
        a.onSquareTap(sq("e7"))
        assertEquals(sq("e7"), a.selectedSquare)
    }

    // ----- branching --------------------------------------------------------

    @Test
    fun playingAMoveAtTheTipAppendsToTheLine() {
        val a = sandbox("e2e4 e7e5", entryIndex = 2)
        a.onSquareTap(sq("g1"))
        a.onSquareTap(sq("f3"))
        assertEquals(listOf("e2e4", "e7e5", "g1f3"), a.moves)
        assertEquals(3, a.viewIndex, "the new move becomes the position on screen")
        assertNull(a.selectedSquare)
    }

    @Test
    fun playingAMoveFromAPastPositionTruncatesEverythingAfterIt() {
        val a = sandbox("e2e4 e7e5 g1f3 b8c6", entryIndex = 2)
        a.onSquareTap(sq("f1"))
        a.onSquareTap(sq("c4"))
        assertEquals(
            listOf("e2e4", "e7e5", "f1c4"),
            a.moves,
            "the game's own 2. Nf3 Nc6 are discarded, not branched alongside",
        )
        assertEquals(3, a.viewIndex)
        assertEquals(3, a.lastIndex, "one line only — nothing survives past the new tip")
    }

    @Test
    fun anIllegalDestinationJustChangesOrDropsTheSelection() {
        val a = sandbox("", entryIndex = 0)
        a.onSquareTap(sq("e2"))
        a.onSquareTap(sq("e5")) // Not reachable.
        assertNull(a.selectedSquare)
        assertEquals(0, a.viewIndex, "nothing was played")

        a.onSquareTap(sq("e2"))
        a.onSquareTap(sq("d2")) // Another of my own pieces re-selects.
        assertEquals(sq("d2"), a.selectedSquare)
    }

    @Test
    fun theEntryPointSurvivesBranchingSoAResetCanReturnToIt() {
        val a = sandbox("e2e4 e7e5 g1f3", entryIndex = 1)
        a.onSquareTap(sq("d7"))
        a.onSquareTap(sq("d5"))
        assertEquals(1, a.entryIndex)

        // A reset is a fresh seed at that same index (ReviewViewModel.resetAnalysis).
        val reset = AnalysisSandbox.seededFrom(game("e2e4 e7e5 g1f3"), Variant.STANDARD, a.entryIndex)
        assertEquals(1, reset.viewIndex)
        assertEquals(listOf("e2e4", "e7e5", "g1f3"), reset.moves, "the branch is gone")
    }

    // ----- promotion --------------------------------------------------------

    private val promotionFen = "4k3/P7/8/8/8/8/8/4K3 w - - 0 1"

    @Test
    fun aPawnReachingTheLastRankWaitsForTheChoiceInsteadOfMoving() {
        val a = sandbox("", entryIndex = 0, fen = promotionFen)
        a.onSquareTap(sq("a7"))
        a.onSquareTap(sq("a8"))
        assertEquals(sq("a7") to sq("a8"), a.pendingPromotion)
        assertTrue(a.moves.isEmpty(), "nothing is played until a piece is picked")

        a.choosePromotion(PieceType.ROOK)
        assertNull(a.pendingPromotion)
        assertEquals(listOf("a7a8r"), a.moves)
        assertEquals(PieceType.ROOK, a.viewed().pieceAt(sq("a8"))?.type)
    }

    @Test
    fun cancellingAPromotionLeavesThePositionUntouched() {
        val a = sandbox("", entryIndex = 0, fen = promotionFen)
        a.onSquareTap(sq("a7"))
        a.onSquareTap(sq("a8"))
        a.cancelPromotion()
        assertNull(a.pendingPromotion)
        assertNull(a.selectedSquare)
        assertTrue(a.moves.isEmpty())
    }

    @Test
    fun browsingIsBlockedWhileAPromotionIsPending() {
        // The pawn has to be committed from the very position it was picked up on, so every
        // way of moving the view index refuses while the picker is up.
        val b = sandbox("a7a8q", entryIndex = 0, fen = "4k3/PP6/8/8/8/8/8/4K3 w - - 0 1")
        assertEquals(1, b.lastIndex, "there IS somewhere to step to")
        b.onSquareTap(sq("b7"))
        b.onSquareTap(sq("b8"))
        assertNotNull(b.pendingPromotion)

        b.stepForward()
        assertEquals(0, b.viewIndex, "stepping is refused while the picker is up")
        b.seekToFraction(1f)
        assertEquals(0, b.viewIndex)
        b.stepToEnd()
        assertEquals(0, b.viewIndex)
    }

    // ----- Crazyhouse drops -------------------------------------------------

    // 1. e4 d5 2. exd5 Qxd5 3. Nc3 — both sides banked a pawn on move 2. Index 4 is White to
    // move, holding the pawn it took on d5.
    private val crazyhouse = "e2e4 d7d5 e4d5 d8d5 b1c3"

    @Test
    fun aReservePieceOfTheSideToMoveCanBePickedUpAndDropped() {
        val a = sandbox(crazyhouse, entryIndex = 4, variant = Variant.CRAZYHOUSE)
        assertEquals(Color.WHITE, a.viewed().sideToMove)

        a.onPocketTap(PieceType.PAWN, Color.WHITE)
        assertEquals(PieceType.PAWN, a.selectedDrop)
        assertTrue(a.dropTargets.isNotEmpty())
        assertFalse(a.dropTargets.any { Square.rank(it) == 0 || Square.rank(it) == 7 }, "no pawns on the back ranks")

        val target = a.dropTargets.first()
        a.onSquareTap(target)
        assertEquals(5, a.lastIndex, "the drop replaced the game's own 3. Nc3")
        assertEquals(PieceType.PAWN, a.viewed().pieceAt(target)?.type)
        assertEquals(0, a.viewed().pocket.count(Color.WHITE, PieceType.PAWN), "the reserve was spent")
        assertNull(a.selectedDrop)
    }

    @Test
    fun tappingTheIdleSidesReserveIsInert() {
        val a = sandbox(crazyhouse, entryIndex = 4, variant = Variant.CRAZYHOUSE)
        a.onPocketTap(PieceType.PAWN, Color.BLACK)
        assertNull(a.selectedDrop, "black holds a pawn too, but it is not black's turn")
        assertTrue(a.dropTargets.isEmpty())
    }

    @Test
    fun tappingTheSameReservePieceTwicePutsItBackDown() {
        val a = sandbox(crazyhouse, entryIndex = 4, variant = Variant.CRAZYHOUSE)
        a.onPocketTap(PieceType.PAWN, Color.WHITE)
        a.onPocketTap(PieceType.PAWN, Color.WHITE)
        assertNull(a.selectedDrop)
        assertTrue(a.dropTargets.isEmpty())
    }

    @Test
    fun reservesAreRederivedFromTheLine_notCarriedThroughAFen() {
        // A FEN round trip would silently drop pockets; the sandbox rebuilds from the base
        // position, so stepping around keeps them exact.
        val a = sandbox(crazyhouse, entryIndex = 5, variant = Variant.CRAZYHOUSE)
        assertEquals(1, a.viewed().pocket.count(Color.WHITE, PieceType.PAWN))
        assertEquals(1, a.viewed().pocket.count(Color.BLACK, PieceType.PAWN))
        a.stepBack()
        a.stepBack()
        a.stepBack()
        assertEquals(0, a.viewed().pocket.count(Color.WHITE, PieceType.PAWN), "before the capture")
    }

    // ----- animation --------------------------------------------------------

    @Test
    fun steppingAndForkingProduceAOneShotSlide() {
        val a = sandbox("e2e4 e7e5", entryIndex = 2)

        a.stepBack()
        val stepping = a.consumeAnim()
        assertNotNull(stepping, "a single step animates")
        assertNull(a.consumeAnim(), "and only once")

        // Back at index 1 it is Black to move; branching there animates the same way.
        a.onSquareTap(sq("d7"))
        a.onSquareTap(sq("d5"))
        assertNotNull(a.consumeAnim(), "and so does a fork")
    }

    @Test
    fun selectionIsClearedWhenTheViewedPositionChanges() {
        val a = sandbox("e2e4 e7e5", entryIndex = 2)
        a.onSquareTap(sq("g1"))
        assertNotNull(a.selectedSquare)
        a.stepBack()
        assertNull(a.selectedSquare, "a piece picked up at one position can't be moved at another")
    }
}
