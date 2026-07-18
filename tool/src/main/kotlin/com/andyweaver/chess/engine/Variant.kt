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

    /**
     * Human-readable name for UI (subtitles, pickers, history). Intentionally
     * lowercase to match the app's minimal LP aesthetic (used everywhere variant
     * names are shown), so callers don't each re-lowercase it.
     */
    val displayName: String get() = when (this) {
        STANDARD -> "standard"
        CHESS960 -> "chess960"
        CRAZYHOUSE -> "crazyhouse"
        ATOMIC -> "atomic"
        KING_OF_THE_HILL -> "king of the hill"
        THREE_CHECK -> "three-check"
        ANTICHESS -> "antichess"
        RACING_KINGS -> "racing kings"
        HORDE -> "horde"
    }

    /**
     * The variant's fixed starting FEN when it differs from standard chess, else null
     * (standard start). Lichess's board stream reports `initialFen: "startpos"` even for
     * these fixed-but-non-standard starts, so callers substitute this when they see it.
     * Chess960's start is random, so Lichess always sends its real FEN — not covered here.
     */
    val startFen: String? get() = when (this) {
        RACING_KINGS -> "8/8/8/8/8/8/krbnNBRK/qrbnNBRQ w - - 0 1"
        HORDE -> "rnbqkbnr/pppppppp/8/1PP2PP1/PPPPPPPP/PPPPPPPP/PPPPPPPP/PPPPPPPP w kq - 0 1"
        else -> null
    }

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
