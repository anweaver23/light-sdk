package com.andyweaver.chess.engine

/**
 * The chess variants the engine understands. Rules differ per variant in move
 * generation, move application, and win conditions; [Position] carries the active
 * variant so [MoveGenerator] and callers can branch on it.
 *
 * Win-condition/terminal detection is intentionally NOT fully modelled here — the
 * app is a viewer of authoritative Lichess games, so the board screen leans on the
 * stream's `status`/`winner` for game-over state. The engine's job is to (a) apply
 * each move correctly so the board renders, and (b) generate legal moves for the
 * user's turn so tap-to-move highlights the right squares.
 */
enum class Variant {
    STANDARD,
    CHESS960,
    CRAZYHOUSE,
    ATOMIC,
    KING_OF_THE_HILL,
    THREE_CHECK,
    ANTICHESS,
    RACING_KINGS,
    HORDE;

    /** Kings have no royal power (no check, capturable, may promote to king). */
    val kingIsRoyal: Boolean get() = this != ANTICHESS

    companion object {
        /** Maps a Lichess variant key (e.g. "kingOfTheHill") to a [Variant]; unknown → [STANDARD]. */
        fun fromKey(key: String): Variant = when (key.lowercase()) {
            "chess960" -> CHESS960
            "crazyhouse" -> CRAZYHOUSE
            "atomic" -> ATOMIC
            "kingofthehill" -> KING_OF_THE_HILL
            "threecheck" -> THREE_CHECK
            "antichess", "giveaway" -> ANTICHESS
            "racingkings" -> RACING_KINGS
            "horde" -> HORDE
            else -> STANDARD
        }
    }
}
