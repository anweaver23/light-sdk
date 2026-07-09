package com.andyweaver.chess.engine

/**
 * Core piece and square vocabulary for the chess engine.
 *
 * Square indexing: squares are `0..63`, computed as `rank * 8 + file`, where
 * `file` is `0..7` for files a..h and `rank` is `0..7` for ranks 1..8. Thus
 * a1 = 0, b1 = 1, h1 = 7, a2 = 8, ... h8 = 63. This is the same little-endian
 * rank-file mapping used by most engines and is what all public APIs expect.
 */

/** The two sides. */
enum class Color {
    WHITE,
    BLACK;

    val opposite: Color get() = if (this == WHITE) BLACK else WHITE
}

/** The six piece kinds. [sanLetter] is the uppercase SAN letter (empty for pawns). */
enum class PieceType(val sanLetter: String) {
    PAWN(""),
    KNIGHT("N"),
    BISHOP("B"),
    ROOK("R"),
    QUEEN("Q"),
    KING("K"),
}

/**
 * A colored piece. [fenChar] is the FEN letter (uppercase = white, lowercase = black).
 */
data class Piece(val color: Color, val type: PieceType) {

    val fenChar: Char
        get() {
            val c = when (type) {
                PieceType.PAWN -> 'p'
                PieceType.KNIGHT -> 'n'
                PieceType.BISHOP -> 'b'
                PieceType.ROOK -> 'r'
                PieceType.QUEEN -> 'q'
                PieceType.KING -> 'k'
            }
            return if (color == Color.WHITE) c.uppercaseChar() else c
        }

    companion object {
        /** Parse a FEN piece letter (e.g. 'N', 'p') into a [Piece], or null if invalid. */
        fun fromFenChar(c: Char): Piece? {
            val color = if (c.isUpperCase()) Color.WHITE else Color.BLACK
            val type = when (c.lowercaseChar()) {
                'p' -> PieceType.PAWN
                'n' -> PieceType.KNIGHT
                'b' -> PieceType.BISHOP
                'r' -> PieceType.ROOK
                'q' -> PieceType.QUEEN
                'k' -> PieceType.KING
                else -> return null
            }
            return Piece(color, type)
        }
    }
}

/**
 * Helpers for the `0..63` square index. Kept as an object of pure functions so
 * callers can reason about squares without a wrapper type.
 */
object Square {

    const val COUNT = 64

    fun of(file: Int, rank: Int): Int = rank * 8 + file

    /** [file] and [rank] in `0..7`, or null if either is off the board. */
    fun ofOrNull(file: Int, rank: Int): Int? =
        if (file in 0..7 && rank in 0..7) rank * 8 + file else null

    fun file(square: Int): Int = square % 8

    fun rank(square: Int): Int = square / 8

    /** True if the square is a light (as opposed to dark) square. */
    fun isLight(square: Int): Boolean = (file(square) + rank(square)) % 2 == 1

    /** Algebraic name, e.g. square 0 -> "a1", 63 -> "h8". */
    fun name(square: Int): String {
        val f = 'a' + file(square)
        val r = '1' + rank(square)
        return "$f$r"
    }

    /** Parse "e4" -> square index, or null if malformed / off board. */
    fun fromName(name: String): Int? {
        if (name.length != 2) return null
        val file = name[0] - 'a'
        val rank = name[1] - '1'
        return ofOrNull(file, rank)
    }
}
