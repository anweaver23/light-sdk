package com.andyweaver.chess.engine

/**
 * Dependency-free PGN export.
 *
 * Produces a valid PGN from a [Replay]: the Seven Tag Roster (Event, Site, Date, Round,
 * White, Black, Result) with sensible defaults, a `Variant` tag for non-standard variants,
 * `FEN` + `SetUp "1"` tags when the game starts from a non-standard position, the numbered
 * SAN movetext (reusing [MoveRecord.san]), and the result token (1-0 / 0-1 / 1/2-1/2 / *)
 * derived from the authoritative [GameStatusEvaluator.outcome].
 *
 * Any default tag can be overridden via the `tags` map (e.g. real player names or a date).
 */
object Pgn {

    /** Column width the movetext is wrapped at, per the PGN export-format convention. */
    private const val MOVETEXT_WRAP = 80

    /**
     * [resultOverride] forces the result token ("1-0" / "0-1" / "1/2-1/2"), for outcomes the
     * engine cannot derive from the moves alone — a resignation or an agreed draw. Without
     * it such a game exports "*", which Lichess imports as unfinished. PGN carries the
     * result TWICE (the `Result` tag and the movetext terminator) and this covers both.
     */
    fun export(
        replay: Replay,
        tags: Map<String, String> = emptyMap(),
        resultOverride: String? = null,
    ): String {
        val variant = replay.initial.variant
        // Pass the moves, not just the positions: repetition detection needs them, and
        // without them a game that ended by fivefold would export the wrong result token.
        val resultToken = resultOverride
            ?: resultToken(GameStatusEvaluator.outcome(replay.positions, variant, replay.steps))

        // Seven Tag Roster, in the mandated order, with local-game defaults. Unknown values
        // use the PGN placeholders ("?" and "????.??.??") so callers who don't supply a date
        // still emit a well-formed tag.
        val roster = linkedMapOf(
            "Event" to "Casual Game",
            "Site" to "Light Phone",
            "Date" to "????.??.??",
            "Round" to "?",
            "White" to "White",
            "Black" to "Black",
            "Result" to resultToken,
        )
        for ((k, v) in tags) if (roster.containsKey(k)) roster[k] = v

        val sb = StringBuilder()
        for ((k, v) in roster) sb.append(tag(k, v))

        // Supplemental tags, after the roster. Variant tag for anything non-standard.
        if (variant != Variant.STANDARD) {
            sb.append(tag("Variant", tags["Variant"] ?: variant.pgnName))
        }
        // FEN + SetUp when the game does not start from the standard initial position
        // (custom setup, Chess960's random back rank, or a variant's own fixed start).
        val initialFen = replay.initial.toFen()
        if (initialFen != Position.START_FEN) {
            sb.append(tag("SetUp", tags["SetUp"] ?: "1"))
            sb.append(tag("FEN", tags["FEN"] ?: initialFen))
        }
        // Any remaining custom tags the caller supplied (not part of the roster/supplemental set).
        val handled = roster.keys + setOf("Variant", "FEN", "SetUp")
        for ((k, v) in tags) if (k !in handled) sb.append(tag(k, v))

        sb.append('\n')
        sb.append(movetext(replay, resultToken))
        sb.append('\n')
        return sb.toString()
    }

    private fun tag(key: String, value: String): String = "[$key \"${escape(value)}\"]\n"

    // PGN tag values escape backslash and double-quote.
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun resultToken(outcome: GameOutcome): String = when (outcome) {
        is GameOutcome.Ongoing -> "*"
        is GameOutcome.Decided -> when (outcome.winner) {
            Color.WHITE -> "1-0"
            Color.BLACK -> "0-1"
            null -> "1/2-1/2"
        }
    }

    /**
     * Build the numbered movetext, ending with the result token. Handles a game that
     * begins with Black to move (from a custom FEN) via the "N... " prefix, and wraps
     * lines at [MOVETEXT_WRAP] columns.
     */
    private fun movetext(replay: Replay, resultToken: String): String {
        val tokens = ArrayList<String>()
        var blackNeedsNumber = true // true when the next Black move must print its "N... " prefix
        for (step in replay.steps) {
            if (step.byWhite) {
                tokens.add("${step.number}.")
                tokens.add(step.san)
                blackNeedsNumber = false
            } else {
                if (blackNeedsNumber) tokens.add("${step.number}...")
                tokens.add(step.san)
                blackNeedsNumber = true
            }
        }
        tokens.add(resultToken)
        return wrap(tokens)
    }

    private fun wrap(tokens: List<String>): String {
        val sb = StringBuilder()
        var lineLen = 0
        for (token in tokens) {
            if (lineLen == 0) {
                sb.append(token)
                lineLen = token.length
            } else if (lineLen + 1 + token.length > MOVETEXT_WRAP) {
                sb.append('\n').append(token)
                lineLen = token.length
            } else {
                sb.append(' ').append(token)
                lineLen += 1 + token.length
            }
        }
        return sb.toString()
    }
}
