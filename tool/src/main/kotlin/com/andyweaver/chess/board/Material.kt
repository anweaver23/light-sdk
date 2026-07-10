package com.andyweaver.chess.board

import com.andyweaver.chess.engine.Color
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Position
import com.andyweaver.chess.engine.Square
import com.andyweaver.chess.engine.Variant

/**
 * Lichess-style material summary for one displayed position, shared by the live
 * board and the review screen.
 *
 * [myCaptured] are the opponent-coloured pieces I've captured; [opponentCaptured]
 * are my-coloured pieces the opponent has captured (both low-value-first). At most
 * one advantage is > 0 (the leader's point lead); the other is 0.
 */
data class MaterialInfo(
    val myCaptured: List<PieceType> = emptyList(),
    val opponentCaptured: List<PieceType> = emptyList(),
    val myAdvantage: Int = 0,
    val opponentAdvantage: Int = 0,
) {
    companion object {
        val EMPTY = MaterialInfo()
    }
}

// Standard point values for the material lead (king excluded).
private val PIECE_VALUES = mapOf(
    PieceType.PAWN to 1,
    PieceType.KNIGHT to 3,
    PieceType.BISHOP to 3,
    PieceType.ROOK to 5,
    PieceType.QUEEN to 9,
)

// Captured-piece display order: low value first (pawns), like Lichess.
private val CAPTURE_ORDER = listOf(
    PieceType.PAWN, PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN,
)

/**
 * Compare [display] against the game's [start] complement to find each side's
 * captured pieces and net point lead, from [myColor]'s perspective. Crazyhouse is
 * excluded (its pockets already show reserves). Promotions can nudge the captured
 * icons slightly (a promoted pawn reads as a captured pawn), but the point lead —
 * the headline number — stays correct because it's summed from piece values.
 */
fun computeMaterial(
    start: Position,
    display: Position,
    variant: Variant,
    myColor: Color,
): MaterialInfo {
    if (variant == Variant.CRAZYHOUSE) return MaterialInfo.EMPTY

    fun counts(pos: Position, color: Color): Map<PieceType, Int> {
        val m = HashMap<PieceType, Int>()
        for (sq in 0 until Square.COUNT) {
            val p = pos.board[sq] ?: continue
            if (p.color == color && p.type != PieceType.KING) m[p.type] = (m[p.type] ?: 0) + 1
        }
        return m
    }
    // Pieces of [victim]'s colour missing from [display] vs the start = pieces the
    // OTHER side has captured, listed low-value-first (pawns first), like Lichess.
    fun capturedFrom(victim: Color): List<PieceType> {
        val startCounts = counts(start, victim)
        val nowCounts = counts(display, victim)
        val out = ArrayList<PieceType>()
        for (type in CAPTURE_ORDER) {
            val gone = ((startCounts[type] ?: 0) - (nowCounts[type] ?: 0)).coerceAtLeast(0)
            repeat(gone) { out.add(type) }
        }
        return out
    }

    val whiteCaptured = capturedFrom(Color.BLACK) // black pieces white took
    val blackCaptured = capturedFrom(Color.WHITE)
    fun value(pieces: List<PieceType>) = pieces.sumOf { PIECE_VALUES[it] ?: 0 }
    val lead = value(whiteCaptured) - value(blackCaptured) // > 0 => white ahead

    val myCaptured = if (myColor == Color.WHITE) whiteCaptured else blackCaptured
    val oppCaptured = if (myColor == Color.WHITE) blackCaptured else whiteCaptured
    val myLead = if (myColor == Color.WHITE) lead else -lead
    return MaterialInfo(
        myCaptured = myCaptured,
        opponentCaptured = oppCaptured,
        myAdvantage = myLead.coerceAtLeast(0),
        opponentAdvantage = (-myLead).coerceAtLeast(0),
    )
}
