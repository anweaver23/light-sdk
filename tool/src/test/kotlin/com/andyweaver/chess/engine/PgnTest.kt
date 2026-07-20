package com.andyweaver.chess.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PGN export smoke tests: tag roster, variant/FEN tags, movetext, and result token. */
class PgnTest {

    @Test
    fun standardPgnHasRosterMovetextAndResult() {
        // Fool's mate -> Black wins (0-1).
        val replay = Chess.replay(listOf("f2f3", "e7e5", "g2g4", "d8h4"))
        val pgn = Chess.toPgn(replay)

        assertTrue("[Event \"Casual Game\"]" in pgn, "Event tag")
        assertTrue("[Result \"0-1\"]" in pgn, "Result reflects the outcome")
        assertTrue("[White \"White\"]" in pgn && "[Black \"Black\"]" in pgn, "default player tags")
        assertTrue("1. f3 e5 2. g4 Qh4#" in pgn, "numbered SAN movetext")
        assertTrue(pgn.trimEnd().endsWith("0-1"), "movetext ends with the result token")
        // Standard game from the start position: no Variant or FEN tags.
        assertFalse("Variant" in pgn, "no Variant tag for standard")
        assertFalse("FEN" in pgn, "no FEN tag for a standard-start game")
        // A blank line must separate the tag pairs from the movetext.
        assertTrue("\"]\n\n" in pgn, "blank line between tags and movetext")
    }

    @Test
    fun customTagsOverrideDefaults() {
        val replay = Chess.replay(listOf("e2e4", "e7e5"))
        val pgn = Chess.toPgn(replay, mapOf("White" to "Alice", "Black" to "Bob", "Event" to "Kitchen Table"))
        assertTrue("[White \"Alice\"]" in pgn)
        assertTrue("[Black \"Bob\"]" in pgn)
        assertTrue("[Event \"Kitchen Table\"]" in pgn)
        // Unfinished game -> result token "*".
        assertTrue("[Result \"*\"]" in pgn)
        assertTrue(pgn.trimEnd().endsWith("*"))
    }

    @Test
    fun variantGameEmitsVariantAndFenTags() {
        // Atomic game from a custom position -> Variant + FEN + SetUp tags, White wins.
        val startFen = "4k3/3p4/8/4N3/8/8/8/4K3 w - - 0 1"
        val replay = Chess.replay("e5d7", startFen, Variant.ATOMIC)
        val pgn = Chess.toPgn(replay)

        assertTrue("[Variant \"Atomic\"]" in pgn, "Variant tag with Lichess-style name")
        assertTrue("[SetUp \"1\"]" in pgn, "SetUp tag for a non-standard start")
        assertTrue("[FEN \"$startFen\"]" in pgn, "FEN tag carries the starting position")
        assertTrue("[Result \"1-0\"]" in pgn, "atomic king explosion -> White wins")
        assertTrue("Nxd7" in pgn, "capture SAN in the movetext")
        assertTrue(pgn.trimEnd().endsWith("1-0"))
    }
}
