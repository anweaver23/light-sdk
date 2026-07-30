package com.andyweaver.chess.board

import com.andyweaver.chess.engine.Color
import com.andyweaver.chess.engine.Piece
import com.andyweaver.chess.engine.PieceType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [pieceRotation] resolves the two ways the in-person game turns pieces. It is the single
 * source both the static squares and the sliding overlay read, so getting it wrong shows
 * up as pieces popping mid-slide — invisible in a screenshot, hence tested here.
 */
class PieceRotationTest {

    private val whitePawn = Piece(Color.WHITE, PieceType.PAWN)
    private val blackPawn = Piece(Color.BLACK, PieceType.PAWN)

    @Test
    fun onlineBoardsNeverRotatePieces() {
        // The live board and review leave both fields at their defaults.
        val state = BoardUiState()
        assertEquals(0f, state.pieceRotation(whitePawn))
        assertEquals(0f, state.pieceRotation(blackPawn))
    }

    @Test
    fun acrossTurnsOnlyTheFarPlayersPieces() {
        // Board unflipped, White at the bottom: only Black's pieces face the far player.
        val state = BoardUiState(acrossMode = true)
        assertEquals(0f, state.pieceRotation(whitePawn))
        assertEquals(180f, state.pieceRotation(blackPawn))
    }

    @Test
    fun sideBySideTurnsNothing() {
        val state = BoardUiState(acrossMode = false)
        assertEquals(0f, state.pieceRotation(whitePawn))
        assertEquals(0f, state.pieceRotation(blackPawn))
    }

    @Test
    fun rigidRotationAppliesToEveryPieceAlike() {
        // Racing Kings across, board flipped: the whole board was turned around, so every
        // piece turns with it — never one colour and not the other.
        val state = BoardUiState(acrossMode = true, rigidPieceRotation = 180f)
        assertEquals(180f, state.pieceRotation(whitePawn))
        assertEquals(180f, state.pieceRotation(blackPawn))
    }

    @Test
    fun rigidZeroOverridesTheAcrossRotation() {
        // Racing Kings across, board NOT flipped: the board faces the near player, so the
        // pieces are all upright — Black's must NOT pick up the per-colour across turn.
        val state = BoardUiState(acrossMode = true, rigidPieceRotation = 0f)
        assertEquals(0f, state.pieceRotation(whitePawn))
        assertEquals(0f, state.pieceRotation(blackPawn))
    }

    @Test
    fun materialBanksFollowMyColourByDefault() {
        // Every screen but Racing-Kings-across leaves materialColor null, so the banks track
        // the bottom of the board exactly as they always have.
        assertEquals(Color.WHITE, BoardUiState(myColor = Color.WHITE).materialBottom)
        assertEquals(Color.BLACK, BoardUiState(myColor = Color.BLACK).materialBottom)
    }

    @Test
    fun pinnedMaterialBanksIgnoreTheBoardTurning() {
        // Racing Kings across: myColor alternates as the board turns each move, but the banks
        // are fixed chrome and must keep showing the same side's captures throughout.
        val nearTurn = BoardUiState(myColor = Color.WHITE, materialColor = Color.WHITE)
        val farTurn = BoardUiState(myColor = Color.BLACK, materialColor = Color.WHITE)
        assertEquals(Color.WHITE, nearTurn.materialBottom)
        assertEquals(Color.WHITE, farTurn.materialBottom)
        // ...and the opposite bank stays put with it.
        assertEquals(Color.BLACK, nearTurn.materialBottom.opposite)
        assertEquals(Color.BLACK, farTurn.materialBottom.opposite)
    }
}
