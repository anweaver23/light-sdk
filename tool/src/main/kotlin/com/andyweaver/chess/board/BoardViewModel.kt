package com.andyweaver.chess.board

import androidx.lifecycle.viewModelScope
import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.engine.Color
import com.andyweaver.chess.engine.GameStatus
import com.andyweaver.chess.engine.Move
import com.andyweaver.chess.engine.Piece
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Position
import com.andyweaver.chess.engine.Replay
import com.andyweaver.chess.engine.Square
import com.andyweaver.chess.engine.Variant
import com.andyweaver.chess.lichess.BoardStreamEvent
import com.andyweaver.chess.lichess.LichessActionResult
import com.andyweaver.chess.lichess.LichessApi
import com.andyweaver.chess.lichess.nameWithRating
import com.andyweaver.chess.settings.ChessSettings
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which set of controls the bottom bar shows. */
enum class BottomMode { BROWSE, PENDING }

/** A destructive/irreversible action awaiting a CONFIRM/✕ overlay. */
enum class Confirmation { RESIGN, DRAW, ABORT }

// v1: PGN export disabled — may re-add
// /** How a fetched PGN should be delivered (performed in the UI layer). */
// enum class PgnDelivery { CLIPBOARD, SHARE }
//
// /** One-shot side effect: a fetched PGN plus how to deliver it. */
// data class PgnEvent(val pgn: String, val delivery: PgnDelivery)

/**
 * Immutable render snapshot for the board screen. Everything [BoardScreen] draws
 * is derived here so the composable stays a pure function of this state.
 *
 * [board] is the 64-entry piece list of the position currently displayed (which,
 * while a move is pending, is the latest position with the pending move applied).
 */
data class BoardUiState(
    val opponentName: String = "Opponent",
    val subtitle: String = "",
    val board: List<Piece?> = Chess.startPosition.board,
    val myColor: Color = Color.WHITE,
    val flipped: Boolean = false,
    val selectedSquare: Int? = null,
    val legalDestinations: Set<Int> = emptySet(),
    val lastMoveFrom: Int? = null,
    val lastMoveTo: Int? = null,
    val checkedKingSquare: Int? = null,
    /** When true, the promotion picker is shown; render the four choices in [myColor]. */
    val promotionActive: Boolean = false,
    val mode: BottomMode = BottomMode.BROWSE,
    val canStepBack: Boolean = false,
    val canStepForward: Boolean = false,
    val terminal: Boolean = false,
    val menuOpen: Boolean = false,
    val confirmation: Confirmation? = null,
    /** Lichess only allows offering a draw after both players have moved. */
    val canOfferDraw: Boolean = false,
    /** Lichess only allows aborting before both players have moved. */
    val canAbort: Boolean = false,
    /** The opponent has offered a draw and we haven't responded yet. */
    val incomingDrawOffer: Boolean = false,
    /** Non-null when the game is a variant our engine doesn't recognise yet
     * (the display name to show on the "not supported" screen instead of a board). */
    val unsupportedVariant: String? = null,
    /** The active variant, for variant-specific rendering (pockets, goal squares). */
    val variant: Variant = Variant.STANDARD,
    /** Crazyhouse reserves the player may drop, and the opponent's, as type→count. */
    val myPocket: Map<PieceType, Int> = emptyMap(),
    val opponentPocket: Map<PieceType, Int> = emptyMap(),
    /** A pocket piece the player has picked up to drop (Crazyhouse), if any. */
    val selectedDrop: PieceType? = null,
    /** Legal squares for the [selectedDrop]. */
    val dropTargets: Set<Int> = emptySet(),
    /** Squares to outline in red as the variant's goal (KotH centre, Racing Kings rank 8). */
    val goalSquares: Set<Int> = emptySet(),
    val message: String? = null,
)

/**
 * Backs [BoardScreen]. Streams one Lichess board game while the screen is
 * visible/foregrounded, exposes a single [BoardUiState] to render, and drives all
 * move/resign/draw/PGN interactions.
 *
 * Threading: the stream is collected on [viewModelScope] (Main dispatcher). Ktor's
 * OkHttp engine performs the network IO off-thread, so all state mutation happens
 * on one thread and needs no locking. Action calls (move/resign/draw/PGN export)
 * likewise launch on Main and let the suspend API do its own IO.
 */
class BoardViewModel(
    private val api: LichessApi,
    private val settings: ChessSettings,
    private val gameId: String,
    private val myColor: Color,
    currentFen: String? = null,
    seededOpponentName: String? = null,
    seededVariant: Variant = Variant.STANDARD,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(
        BoardUiState(myColor = myColor, flipped = myColor == Color.BLACK),
    )
    val uiState: StateFlow<BoardUiState> = _uiState.asStateFlow()

    // v1: PGN export disabled — may re-add
    // // One-shot PGN delivery, consumed by the UI (clipboard / share intent).
    // private val _pgnEvent = MutableStateFlow<PgnEvent?>(null)
    // val pgnEvent: StateFlow<PgnEvent?> = _pgnEvent.asStateFlow()

    // ----- internal game/render state (all mutated on the Main thread) -----
    private var initialFen: String? = null
    private var replay: Replay = Chess.replay("")
    private var viewIndex: Int = 0
    private var selectedSquare: Int? = null
    private var legalDests: Set<Int> = emptySet()
    // A pawn move to the last rank awaiting a promotion-piece choice (from, to).
    private var pendingPromotion: Pair<Int, Int>? = null
    private var pendingMove: Move? = null
    private var awaitingServer: Boolean = false
    private var streamStatus: String = ""
    private var streamWinner: String? = null
    // Seeded from the home row so the top bar shows the real name on first paint
    // (no "Opponent" flash); reconciled from gameFull when the stream arrives.
    private var opponentName: String = seededOpponentName?.takeIf { it.isNotBlank() } ?: "Opponent"
    private var unsupportedVariant: String? = null
    // Seeded from the home row; corrected from gameFull when the stream arrives.
    private var variant: Variant = seededVariant
    // Crazyhouse: a pocket piece picked up to drop, and its legal target squares.
    private var selectedDrop: PieceType? = null
    private var dropTargets: Set<Int> = emptySet()
    private var opponentOfferedDraw: Boolean = false
    // Set true once we accept/decline an incoming offer, to hide the prompt until
    // the stream reflects the change; reset when the offer clears.
    private var drawResponsePending: Boolean = false
    private var menuOpen: Boolean = false
    private var confirmation: Confirmation? = null
    private var message: String? = null
    private var confirmMoves: Boolean = true

    private var streamJob: Job? = null

    private val myColorString = if (myColor == Color.WHITE) "white" else "black"

    init {
        // Seed the displayed board from the game's current FEN (passed by the home
        // screen) so the FIRST render shows the real position instead of the standard
        // start — avoids a start-position flash before the live stream arrives. When
        // gameFull lands it reconciles from the authoritative move list as usual.
        if (currentFen != null) {
            replay = runCatching { Chess.replay("", currentFen, variant) }.getOrElse { Chess.replay("", null, variant) }
            viewIndex = replay.positions.lastIndex
        }
        // Keep the confirm-moves preference current; defaults to true until loaded.
        viewModelScope.launch { settings.confirmMoves.collect { confirmMoves = it } }
        recompute()
    }

    // ----- lifecycle: stream only while visible AND foregrounded -----

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        startStream()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        stopStream()
    }

    override fun onAppPause() {
        super.onAppPause()
        stopStream()
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
        api.close()
    }

    private fun startStream() {
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            try {
                api.streamBoardGame(gameId).collect { handleEvent(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Connection lost"
                recompute()
            }
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
    }

    // ----- stream handling -----

    private fun handleEvent(event: BoardStreamEvent) {
        when (event) {
            is BoardStreamEvent.GameFull -> {
                initialFen = event.initialFen.takeUnless { it == "startpos" || it.isBlank() }
                variant = Variant.fromKey(event.variant.key)
                // Only a variant we don't recognise at all falls back to the "not
                // supported" screen; every known variant now plays.
                unsupportedVariant = event.variant.name
                    .takeIf { event.variant.key.lowercase() !in KNOWN_VARIANTS }
                val opp = if (myColor == Color.WHITE) event.black else event.white
                val oppName = opp.name?.takeIf { it.isNotBlank() } ?: "Opponent"
                opponentName = nameWithRating(oppName, opp.rating)
                applyState(event.state, resetView = true)
            }

            is BoardStreamEvent.GameState -> applyState(event, resetView = false)

            BoardStreamEvent.Unknown -> Unit
        }
    }

    private fun applyState(state: BoardStreamEvent.GameState, resetView: Boolean) {
        val oldPositions = replay.positions
        val wasAtLatest = viewIndex == oldPositions.lastIndex

        streamStatus = state.status
        streamWinner = state.winner
        opponentOfferedDraw = if (myColor == Color.WHITE) state.bdraw else state.wdraw
        // Offer withdrawn/resolved -> allow the prompt to show again next time.
        if (!opponentOfferedDraw) drawResponsePending = false

        // Re-derive the whole timeline from the authoritative move list.
        replay = try {
            Chess.replay(state.moves, initialFen, variant)
        } catch (_: Exception) {
            replay
        }
        val newPositions = replay.positions

        viewIndex = if (resetView || wasAtLatest) {
            newPositions.lastIndex
        } else {
            viewIndex.coerceIn(0, newPositions.lastIndex)
        }

        // Our optimistic pending move has landed (or a new move arrived) -> release it.
        if (pendingMove != null) {
            val last = replay.steps.lastOrNull()
            if ((last != null && last.move.toUci() == pendingMove!!.toUci()) ||
                newPositions.size > oldPositions.size
            ) {
                pendingMove = null
                awaitingServer = false
            }
        }

        // Any change in ply count means the board moved under any selection.
        if (newPositions.size != oldPositions.size) {
            clearSelection()
        }

        recompute()
    }

    // ----- history browsing -----

    fun stepBack() {
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex > 0) {
            viewIndex--
            clearSelection()
            recompute()
        }
    }

    fun stepForward() {
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex < replay.positions.lastIndex) {
            viewIndex++
            clearSelection()
            recompute()
        }
    }

    fun stepToStart() {
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex != 0) {
            viewIndex = 0
            clearSelection()
            recompute()
        }
    }

    fun stepToEnd() {
        if (pendingMove != null || pendingPromotion != null) return
        val last = replay.positions.lastIndex
        if (viewIndex != last) {
            viewIndex = last
            clearSelection()
            recompute()
        }
    }

    // ----- move interaction -----

    fun onSquareTap(square: Int) {
        val positions = replay.positions
        // Read-only while a move is pending/in-flight, while picking a promotion
        // piece, while reviewing history, when the game is over, or when not our turn.
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex != positions.lastIndex) return
        if (isTerminal()) return
        val latest = replay.finalPosition
        if (latest.sideToMove != myColor) return

        // Crazyhouse: a pocket piece is picked up — this tap chooses where to drop it.
        val drop = selectedDrop
        if (drop != null) {
            if (square in dropTargets) commitDrop(drop, square) else clearSelectionAndRecompute()
            return
        }

        val piece = latest.pieceAt(square)
        val selected = selectedSquare

        if (selected == null) {
            if (piece != null && piece.color == myColor) select(square, latest)
            return
        }

        when {
            square == selected -> clearSelectionAndRecompute()
            square in legalDests -> makeMove(selected, square)
            piece != null && piece.color == myColor -> select(square, latest)
            else -> clearSelectionAndRecompute()
        }
    }

    private fun select(square: Int, position: Position) {
        selectedSquare = square
        legalDests = Chess.legalDestinations(position, square)
        recompute()
    }

    /** Crazyhouse: pick up (or put back) a pocket piece to drop. */
    fun onPocketTap(type: PieceType) {
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex != replay.positions.lastIndex) return
        if (isTerminal()) return
        val latest = replay.finalPosition
        if (latest.sideToMove != myColor) return
        if (latest.pocket.count(myColor, type) <= 0) return
        if (selectedDrop == type) {
            clearSelectionAndRecompute() // tapping the held piece again puts it back
            return
        }
        clearSelection()
        selectedDrop = type
        dropTargets = Chess.legalDropSquares(latest, type)
        recompute()
    }

    private fun commitDrop(type: PieceType, square: Int) {
        clearSelection()
        stage(Move(from = square, to = square, drop = type))
    }

    private fun makeMove(from: Int, to: Int) {
        val latest = replay.finalPosition
        val isPromotion = latest.pieceAt(from)?.type == PieceType.PAWN &&
            (Square.rank(to) == 0 || Square.rank(to) == 7)
        if (isPromotion) {
            // Hold the move and ask which piece to promote to (BoardScreen shows a picker).
            pendingPromotion = from to to
            clearSelection()
            recompute()
            return
        }
        commitMove(from, to, promotion = null)
    }

    /** User picked a promotion piece from the picker. */
    fun choosePromotion(type: PieceType) {
        val (from, to) = pendingPromotion ?: return
        pendingPromotion = null
        commitMove(from, to, promotion = type)
    }

    fun cancelPromotion() {
        pendingPromotion = null
        clearSelection()
        recompute()
    }

    private fun commitMove(from: Int, to: Int, promotion: PieceType?) {
        val latest = replay.finalPosition
        val suffix = when (promotion) {
            PieceType.QUEEN -> "q"
            PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"
            PieceType.KNIGHT -> "n"
            else -> ""
        }
        val uci = Square.name(from) + Square.name(to) + suffix
        val move = Chess.parseUci(uci, latest)
        clearSelection()
        if (move == null) {
            recompute()
            return
        }
        stage(move)
    }

    // Stage a move: hold it for confirmation, or submit immediately if confirm-moves is off.
    private fun stage(move: Move) {
        pendingMove = move
        if (confirmMoves) {
            awaitingServer = false
            recompute()
        } else {
            awaitingServer = true
            recompute()
            submit(move)
        }
    }

    fun confirmPendingMove() {
        val move = pendingMove ?: return
        if (awaitingServer) return
        awaitingServer = true
        recompute()
        submit(move)
    }

    fun cancelPendingMove() {
        pendingMove = null
        awaitingServer = false
        recompute()
    }

    private fun submit(move: Move) {
        viewModelScope.launch {
            when (val res = api.submitMove(gameId, move.toUci())) {
                LichessActionResult.Success -> Unit // stream will reflect the new position
                is LichessActionResult.Failure -> {
                    pendingMove = null
                    awaitingServer = false
                    message = "Move rejected: ${res.error}"
                    recompute()
                }
            }
        }
    }

    // ----- menu / confirmations -----

    fun openMenu() { menuOpen = true; recompute() }
    fun closeMenu() { menuOpen = false; recompute() }

    fun requestResign() { menuOpen = false; confirmation = Confirmation.RESIGN; recompute() }
    fun requestAbort() { menuOpen = false; confirmation = Confirmation.ABORT; recompute() }
    fun requestDraw() { menuOpen = false; confirmation = Confirmation.DRAW; recompute() }
    fun cancelConfirmation() { confirmation = null; recompute() }

    fun confirmResign() {
        confirmation = null
        recompute()
        viewModelScope.launch {
            val res = api.resignGame(gameId)
            if (res is LichessActionResult.Failure) {
                message = "Couldn't resign: ${res.error}"
                recompute()
            }
        }
    }

    fun confirmAbort() {
        confirmation = null
        recompute()
        viewModelScope.launch {
            val res = api.abortGame(gameId)
            if (res is LichessActionResult.Failure) {
                message = "Couldn't abort: ${res.error}"
                recompute()
            }
        }
    }

    fun confirmDraw() {
        confirmation = null
        recompute()
        viewModelScope.launch {
            when (val res = api.handleDraw(gameId, accept = true)) {
                LichessActionResult.Success -> { message = "Draw offer sent"; recompute() }
                is LichessActionResult.Failure -> { message = "Couldn't offer draw: ${res.error}"; recompute() }
            }
        }
    }

    // ----- responding to an incoming draw offer -----

    fun acceptIncomingDraw() = respondToDraw(accept = true, failNote = "Couldn't accept draw")
    fun declineIncomingDraw() = respondToDraw(accept = false, failNote = "Couldn't decline draw")

    private fun respondToDraw(accept: Boolean, failNote: String) {
        if (drawResponsePending) return
        drawResponsePending = true
        recompute() // hides the prompt immediately
        viewModelScope.launch {
            val res = api.handleDraw(gameId, accept = accept)
            if (res is LichessActionResult.Failure) {
                drawResponsePending = false
                message = "$failNote: ${res.error}"
                recompute()
            }
            // On success the stream reflects the resolution (draw -> terminal, or offer cleared).
        }
    }

    // ----- PGN export -----
    // v1: PGN export disabled — may re-add
    // fun copyPgn() { menuOpen = false; recompute(); fetchPgn(PgnDelivery.CLIPBOARD) }
    // fun sharePgn() { menuOpen = false; recompute(); fetchPgn(PgnDelivery.SHARE) }
    //
    // private fun fetchPgn(delivery: PgnDelivery) {
    //     viewModelScope.launch {
    //         try {
    //             _pgnEvent.value = PgnEvent(api.exportGamePgn(gameId), delivery)
    //         } catch (e: Exception) {
    //             message = "Couldn't fetch PGN"
    //             recompute()
    //         }
    //     }
    // }
    //
    // /** UI reports how it delivered the PGN. [note] (if any) is surfaced to the user. */
    // fun onPgnDelivered(note: String?) {
    //     _pgnEvent.value = null
    //     if (note != null) { message = note; recompute() }
    // }

    // ----- messages -----

    fun dismissMessage() { message = null; recompute() }

    // ----- helpers -----

    private fun clearSelection() {
        selectedSquare = null
        legalDests = emptySet()
        selectedDrop = null
        dropTargets = emptySet()
    }

    private fun clearSelectionAndRecompute() {
        clearSelection()
        recompute()
    }

    private fun isTerminal(): Boolean {
        val engine = Chess.status(replay.finalPosition)
        return engine.isTerminal || streamStatus.lowercase() !in LIVE_STATUSES
    }

    private fun recompute() {
        val positions = replay.positions
        viewIndex = viewIndex.coerceIn(0, positions.lastIndex)
        val latest = replay.finalPosition

        val displayPosition = pendingMove?.let { safeApply(latest, it) } ?: positions[viewIndex]
        val terminal = isTerminal()
        val isMyTurn = !terminal && latest.sideToMove == myColor

        // Last-move highlight follows whatever produced the displayed position.
        var lastFrom: Int? = null
        var lastTo: Int? = null
        when {
            pendingMove != null -> {
                lastFrom = pendingMove!!.from
                lastTo = pendingMove!!.to
            }
            viewIndex > 0 -> {
                val step = replay.steps[viewIndex - 1]
                lastFrom = step.move.from
                lastTo = step.move.to
            }
        }

        // Don't reveal check/checkmate for a move the user hasn't confirmed yet.
        val moveUnconfirmed = pendingMove != null && !awaitingServer
        val displayStatus = Chess.status(displayPosition)
        val inCheck = !moveUnconfirmed &&
            (displayStatus is GameStatus.Check || displayStatus is GameStatus.Checkmate)
        val checkedKing = if (inCheck) {
            displayPosition.kingSquare(displayPosition.sideToMove).takeIf { it >= 0 }
        } else {
            null
        }

        val subtitle = if (terminal) {
            resultSubtitle(latest)
        } else {
            buildString {
                append(if (isMyTurn) "your move" else "their move")
                if (opponentOfferedDraw) append(" · draw offered")
            }
        }

        val mode = if (pendingMove != null && !awaitingServer) BottomMode.PENDING else BottomMode.BROWSE

        // Lichess rejects draw offers before both players have moved (>= 2 plies).
        val canOfferDraw = !terminal && replay.steps.size >= 2
        // Lichess only allows aborting before both players have moved (< 2 plies).
        val canAbort = !terminal && replay.steps.size < 2
        val incomingDrawOffer = opponentOfferedDraw && !drawResponsePending && !terminal

        _uiState.value = BoardUiState(
            opponentName = opponentName,
            subtitle = subtitle,
            board = displayPosition.board,
            myColor = myColor,
            flipped = myColor == Color.BLACK,
            selectedSquare = selectedSquare,
            legalDestinations = legalDests,
            lastMoveFrom = lastFrom,
            lastMoveTo = lastTo,
            checkedKingSquare = checkedKing,
            promotionActive = pendingPromotion != null,
            mode = mode,
            canStepBack = viewIndex > 0,
            canStepForward = viewIndex < positions.lastIndex,
            terminal = terminal,
            menuOpen = menuOpen,
            confirmation = confirmation,
            canOfferDraw = canOfferDraw,
            canAbort = canAbort,
            incomingDrawOffer = incomingDrawOffer,
            unsupportedVariant = unsupportedVariant,
            variant = variant,
            myPocket = displayPosition.pocket.forColor(myColor),
            opponentPocket = displayPosition.pocket.forColor(myColor.opposite),
            selectedDrop = selectedDrop,
            dropTargets = dropTargets,
            goalSquares = goalSquares(),
            message = message,
        )
    }

    // Squares the active variant highlights as its goal (red outline in the UI).
    private fun goalSquares(): Set<Int> = when (variant) {
        Variant.KING_OF_THE_HILL -> setOf(
            Square.of(3, 3), Square.of(4, 3), Square.of(3, 4), Square.of(4, 4), // d4, e4, d5, e5
        )
        Variant.RACING_KINGS -> (0..7).map { Square.of(it, 7) }.toSet() // the 8th rank
        else -> emptySet()
    }

    private fun safeApply(position: Position, move: Move): Position =
        try {
            Chess.applyMove(position, move)
        } catch (_: Exception) {
            position
        }

    private fun resultSubtitle(latest: Position): String {
        val engine = Chess.status(latest)
        val s = streamStatus.lowercase()
        val iWon = streamWinner != null && streamWinner.equals(myColorString, ignoreCase = true)
        val outcome = if (iWon) "you won" else "you lost"
        return when {
            s == "mate" || engine is GameStatus.Checkmate -> "checkmate · $outcome"
            s == "resign" -> "resigned · $outcome"
            s == "outoftime" || s == "timeout" -> "time out · $outcome"
            s == "stalemate" || engine is GameStatus.Stalemate -> "stalemate · draw"
            s == "draw" || engine is GameStatus.Draw -> "draw"
            s == "aborted" -> "game aborted"
            streamWinner != null -> outcome
            else -> "game over"
        }
    }

    private companion object {
        // Stream statuses that mean the game is still in progress.
        val LIVE_STATUSES = setOf("", "started", "created")

        // Every variant the engine now handles. Anything outside this set (an
        // unknown/future Lichess variant) falls back to the "not supported" screen.
        // "fromposition" is standard rules from a custom FEN, so it plays normally.
        val KNOWN_VARIANTS = setOf(
            "standard", "fromposition", "chess960", "crazyhouse", "atomic",
            "kingofthehill", "threecheck", "antichess", "racingkings", "horde",
        )
    }
}
