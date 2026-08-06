package com.andyweaver.chess.engine

/**
 * One move within a [Replay]: the [move] made, its [san], and the positions
 * immediately [before] and [after] it. [number] is the fullmove number the move
 * belongs to and [byWhite] indicates who made it, so callers can render move
 * lists (e.g. "1. e4 e5 2. Nf3") without recomputing.
 */
data class MoveRecord(
    val move: Move,
    val san: String,
    val before: Position,
    val after: Position,
    val number: Int,
    val byWhite: Boolean,
)

/**
 * The result of replaying a move list: the [initial] position and one
 * [MoveRecord] per move. [positions] is the full timeline — the initial position
 * followed by the position after each move — so index `i` is the board state
 * after `i` plies (index 0 = start). This is what the board screen renders to
 * show any historical position.
 */
data class Replay(
    val initial: Position,
    val steps: List<MoveRecord>,
) {
    val positions: List<Position>
        get() = buildList {
            add(initial)
            steps.forEach { add(it.after) }
        }

    val finalPosition: Position
        get() = steps.lastOrNull()?.after ?: initial
}

/**
 * The result of a lenient SAN replay (see [Chess.replaySanLenient]): the [replay] built
 * from every token that matched, plus what stopped it. [unmatchedToken] is null when the
 * whole list replayed; otherwise it's the first token no legal move's SAN matched, and
 * [replay] holds only the moves BEFORE it.
 */
data class SanReplay(
    val replay: Replay,
    /** Number of SAN tokens in the input. */
    val totalTokens: Int,
    /** The first token that matched no legal move, or null if all of them did. */
    val unmatchedToken: String?,
) {
    /** Moves successfully replayed (== [totalTokens] unless [truncated]). */
    val movesReplayed: Int get() = replay.steps.size

    /** True when the replay stopped early — the game is only partly viewable. */
    val truncated: Boolean get() = unmatchedToken != null
}

/**
 * Top-level facade for the board screen. Everything the UI needs is reachable
 * from here; the lower-level types ([Position], [MoveGenerator], [San], etc.) are
 * public too for anyone who wants them.
 */
object Chess {

    /** The standard starting position. */
    val startPosition: Position get() = Position.START

    /** Parse a FEN string into a [Position]. Throws on malformed input. */
    fun parseFen(fen: String): Position = Position.fromFen(fen)

    /** Serialize a [Position] to FEN. */
    fun toFen(position: Position): String = position.toFen()

    /** Apply a legal (or correctly-flagged) move, returning the new position. */
    fun applyMove(position: Position, move: Move): Position = MoveGenerator.applyMove(position, move)

    /** All fully legal moves for the side to move. */
    fun legalMoves(position: Position): List<Move> = MoveGenerator.legalMoves(position)

    /** Legal target squares for the piece on [from], for highlighting when tapped. */
    fun legalDestinations(position: Position, from: Int): Set<Int> =
        MoveGenerator.legalDestinations(position, from)

    /** Status (ongoing / check / checkmate / stalemate / draw) of [position]. */
    fun status(position: Position): GameStatus = GameStatusEvaluator.status(position)

    /**
     * Authoritative, variant-aware [GameOutcome] for a game — whether it is over and, if
     * so, who won and why. Use this for the LOCAL two-human game mode where there is no
     * Lichess stream to report the result. Some variants (Three-check) need the move
     * history, so this takes the whole [replay] — as does the fivefold-repetition auto-draw,
     * whose comparison window starts at the last irreversible MOVE.
     */
    fun outcome(replay: Replay): GameOutcome =
        GameStatusEvaluator.outcome(replay.positions, replay.initial.variant, replay.steps)

    /**
     * How many times the position at [index] (default: the current one) has occurred in
     * [replay] — see [Repetition] for what "the same position" means per variant.
     */
    fun repetitionCount(replay: Replay, index: Int = replay.positions.lastIndex): Int =
        Repetition.count(replay, index)

    /**
     * Is a threefold-repetition draw CLAIMABLE at [index]? Lichess does not auto-draw on
     * threefold (only fivefold, which [outcome] reports as
     * [OutcomeReason.FIVEFOLD_REPETITION]), so this is purely an offer for the UI to make.
     */
    fun canClaimThreefold(replay: Replay, index: Int = replay.positions.lastIndex): Boolean =
        Repetition.isThreefold(replay, index)

    /**
     * Export [replay] as a PGN string (Seven Tag Roster + movetext + result token), with a
     * `Variant` tag for non-standard variants and `FEN`/`SetUp` tags when the game starts
     * from a non-standard position. Provide [tags] to override any default (e.g. player
     * names, event, date). See [Pgn].
     */
    fun toPgn(
        replay: Replay,
        tags: Map<String, String> = emptyMap(),
        resultOverride: String? = null,
    ): String = Pgn.export(replay, tags, resultOverride)

    /** True if the side to move is in check (variant-aware). */
    fun isInCheck(position: Position): Boolean = MoveGenerator.inCheck(position, position.sideToMove)

    /** Legal drop target squares for [type] from the side-to-move's pocket (Crazyhouse). */
    fun legalDropSquares(position: Position, type: PieceType): Set<Int> =
        MoveGenerator.legalDropSquares(position, type)

    /** SAN for [move] made in [position]. The move must be legal in the position. */
    fun san(position: Position, move: Move, enPassantSuffix: Boolean = false): String =
        San.of(position, move, enPassantSuffix)

    /**
     * Best-effort SAN for the LAST move given only the RESULTING position ([resultingFen], the
     * post-move FEN) and the move in UCI ([lastMoveUci]).
     *
     * The board screen has the full move list and gets exact SAN from [replay]. The home screen does
     * NOT — Lichess's `/api/account/playing` only gives each game's `fen` (post-move) + `lastMove`
     * (UCI). This reconstructs the pre-move position by un-applying the move — capture is inferred
     * from the halfmove clock (a non-pawn move that reset it to 0 was a capture) and, for pawns, from
     * a file change — then runs the normal SAN generator. Returns null if anything doesn't add up so
     * the caller can fall back to raw UCI. En passant renders as an ordinary pawn capture ("exd6"),
     * which is the correct SAN anyway.
     */
    fun sanForLastMove(resultingFen: String, lastMoveUci: String): String? = runCatching {
        val post = Position.fromFen(resultingFen)
        val uci = lastMoveUci.trim()
        if (uci.length !in 4..5) return@runCatching null
        val from = Square.fromName(uci.substring(0, 2)) ?: return@runCatching null
        val to = Square.fromName(uci.substring(2, 4)) ?: return@runCatching null
        val promotion = if (uci.length == 5) {
            when (uci[4].lowercaseChar()) {
                'q' -> PieceType.QUEEN
                'r' -> PieceType.ROOK
                'b' -> PieceType.BISHOP
                'n' -> PieceType.KNIGHT
                else -> return@runCatching null
            }
        } else {
            null
        }

        val mover = post.sideToMove.opposite
        val postToPiece = post.pieceAt(to) ?: return@runCatching null
        if (postToPiece.color != mover) return@runCatching null
        val movedType = if (promotion != null) PieceType.PAWN else postToPiece.type

        val isCastle = movedType == PieceType.KING &&
            kotlin.math.abs(Square.file(to) - Square.file(from)) == 2
        val isPawn = movedType == PieceType.PAWN
        val fileChanged = Square.file(from) != Square.file(to)
        val wasCapture = when {
            isCastle -> false
            isPawn -> fileChanged
            else -> post.halfmoveClock == 0
        }

        val board = post.board.toMutableList()
        board[to] = null
        board[from] = Piece(mover, movedType)
        if (isCastle) {
            val rank = Square.rank(from)
            val kingSide = Square.file(to) == 6
            board[Square.of(if (kingSide) 5 else 3, rank)] = null
            board[Square.of(if (kingSide) 7 else 0, rank)] = Piece(mover, PieceType.ROOK)
        } else if (wasCapture) {
            // Placeholder captured piece; its type is irrelevant to the SAN of the mover.
            board[to] = Piece(mover.opposite, PieceType.PAWN)
        }

        val pre = Position(
            board = board,
            sideToMove = mover,
            castlingRights = post.castlingRights,
            enPassantTarget = null,
            halfmoveClock = 0,
            fullmoveNumber = post.fullmoveNumber,
        )
        val move = Move.fromUci(uci, pre) ?: return@runCatching null
        San.of(pre, move)
    }.getOrNull()

    /**
     * Parse a UCI move against [position], resolving special-move flags. Returns
     * null if the string is malformed or the from-square is empty. Does not check
     * legality — cross-check with [legalMoves] if the source is untrusted.
     */
    fun parseUci(uci: String, position: Position): Move? = Move.fromUci(uci, position)

    /**
     * Replay a space-separated UCI move list (exactly the format Lichess gives in
     * `gameState.moves`, i.e. moves from the initial position) starting from
     * [startFen] (defaults to the standard start position). Produces a [Replay]
     * with the position and SAN at each step.
     *
     * @throws IllegalArgumentException if a move is malformed (unparseable UCI).
     */
    fun replay(uciMoves: String, startFen: String? = null, variant: Variant = Variant.STANDARD): Replay =
        replay(splitMoves(uciMoves), startFen, variant)

    /** As [replay], but taking an already-split list of UCI move strings. */
    fun replay(uciMoves: List<String>, startFen: String? = null, variant: Variant = Variant.STANDARD): Replay {
        val parsed = startFen?.let { Position.fromFen(it, variant) } ?: Position.START
        // Tag the variant even when starting from the standard position (e.g. Crazyhouse
        // or Atomic games that begin from "startpos").
        val initial = if (parsed.variant != variant) parsed.copy(variant = variant) else parsed
        return replayFrom(initial, uciMoves)
    }

    /**
     * As [replay], but starting from an already-built [initial] [Position] instead of a
     * FEN — the position carries its own variant, so none is passed.
     *
     * Use this whenever the start position is one we already hold in memory (the analysis
     * sandbox snapshots the live board this way): [Position.toFen] emits only the six
     * standard FEN fields, so a FEN round-trip would silently DROP the Crazyhouse pocket
     * and the `~` promoted-piece marks that [Position.fromFen] can otherwise read back.
     */
    fun replayFrom(initial: Position, uciMoves: List<String>): Replay {
        var current = initial
        val steps = ArrayList<MoveRecord>(uciMoves.size)
        for (uci in uciMoves) {
            val move = Move.fromUci(uci, current)
                ?: throw IllegalArgumentException("Malformed UCI move '$uci' in position ${current.toFen()}")
            // Viewer-lenient: these moves come straight from Lichess and are already
            // validated server-side. We deliberately do NOT re-check standard-chess
            // legality here — variants have legal moves the standard generator won't
            // emit (Horde's first-rank pawn double-push, Racing Kings, Antichess,
            // King-of-the-Hill, Three-check), and re-checking would reject the whole
            // timeline and desync the board. Move.fromUci already derived the special
            // flags (double-push, en passant, castle, promotion) from the position.
            // SAN is best-effort so a variant quirk can't abort an otherwise-valid replay.
            val san = runCatching { San.of(current, move) }.getOrElse { move.toUci() }
            val after = MoveGenerator.applyMove(current, move)
            steps.add(
                MoveRecord(
                    move = move,
                    san = san,
                    before = current,
                    after = after,
                    number = current.fullmoveNumber,
                    byWhite = current.sideToMove == Color.WHITE,
                ),
            )
            current = after
        }
        return Replay(initial, steps)
    }

    /**
     * Replay a space-separated SAN move list — the format Lichess returns in a game's
     * `moves` field (e.g. "e4 e5 Nf3"). Each token is matched against the legal moves of
     * the current position by generated SAN (check/mate/annotation suffixes ignored), so
     * it handles disambiguation, captures, castling, and promotion. Produces the same
     * [Replay] as [replay].
     *
     * STRICT: use [replaySanLenient] for viewing a game the user actually played — one
     * token our SAN generator happens to disagree with should not discard the whole game.
     *
     * @throws IllegalArgumentException if a token matches no legal move (e.g. an
     * unsupported variant).
     */
    fun replaySan(sanMoves: String, startFen: String? = null, variant: Variant = Variant.STANDARD): Replay =
        replaySan(splitMoves(sanMoves), startFen, variant)

    /** As [replaySan], but taking an already-split list of SAN tokens. */
    fun replaySan(sanMoves: List<String>, startFen: String? = null, variant: Variant = Variant.STANDARD): Replay {
        val result = replaySanLenient(sanMoves, startFen, variant)
        result.unmatchedToken?.let {
            throw IllegalArgumentException("Unrecognized SAN '$it' in ${result.replay.finalPosition.toFen()}")
        }
        return result.replay
    }

    /**
     * Viewer-lenient [replaySan]: replays as far as the tokens match and RETURNS the
     * prefix instead of throwing on the first token it can't match.
     *
     * Games come from Lichess and are already legal there, so an unmatched token means
     * OUR SAN generator or move generator disagrees with scalachess for that position —
     * a variant quirk, a disambiguation difference, and so on. Throwing the whole
     * timeline away for it leaves the user with nothing (see [SanReplay.truncated]);
     * showing the moves that did parse at least lets them review most of the game. Same
     * rationale as [replayFrom] deliberately not re-checking legality.
     */
    fun replaySanLenient(
        sanMoves: String,
        startFen: String? = null,
        variant: Variant = Variant.STANDARD,
    ): SanReplay = replaySanLenient(splitMoves(sanMoves), startFen, variant)

    /** As [replaySanLenient], but taking an already-split list of SAN tokens. */
    fun replaySanLenient(
        sanMoves: List<String>,
        startFen: String? = null,
        variant: Variant = Variant.STANDARD,
    ): SanReplay {
        val parsed = startFen?.let { Position.fromFen(it, variant) } ?: Position.START
        val initial = if (parsed.variant != variant) parsed.copy(variant = variant) else parsed
        var current = initial
        val steps = ArrayList<MoveRecord>(sanMoves.size)
        for (token in sanMoves) {
            val target = normalizeSan(token)
            val move = MoveGenerator.legalMoves(current)
                .firstOrNull { normalizeSan(San.of(current, it)) == target }
                ?: return SanReplay(Replay(initial, steps), sanMoves.size, token)
            val after = MoveGenerator.applyMove(current, move)
            steps.add(
                MoveRecord(
                    move = move,
                    san = San.of(current, move),
                    before = current,
                    after = after,
                    number = current.fullmoveNumber,
                    byWhite = current.sideToMove == Color.WHITE,
                ),
            )
            current = after
        }
        return SanReplay(Replay(initial, steps), sanMoves.size, unmatchedToken = null)
    }

    // Strip check/mate/annotation glyphs so SAN comparison is on the core move only.
    private fun normalizeSan(san: String): String =
        san.replace("+", "").replace("#", "").replace("!", "").replace("?", "")

    private fun splitMoves(moves: String): List<String> =
        moves.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
