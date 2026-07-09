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
import com.andyweaver.chess.lichess.BoardStreamEvent
import com.andyweaver.chess.lichess.LichessActionResult
import com.andyweaver.chess.lichess.LichessApi
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
enum class Confirmation { RESIGN, DRAW }

/** How a fetched PGN should be delivered (performed in the UI layer). */
enum class PgnDelivery { CLIPBOARD, SHARE }

/** One-shot side effect: a fetched PGN plus how to deliver it. */
data class PgnEvent(val pgn: String, val delivery: PgnDelivery)

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
    val lastMoveByMe: Boolean = false,
    val checkedKingSquare: Int? = null,
    val mode: BottomMode = BottomMode.BROWSE,
    val canStepBack: Boolean = false,
    val canStepForward: Boolean = false,
    val terminal: Boolean = false,
    val menuOpen: Boolean = false,
    val confirmation: Confirmation? = null,
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
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(
        BoardUiState(myColor = myColor, flipped = myColor == Color.BLACK),
    )
    val uiState: StateFlow<BoardUiState> = _uiState.asStateFlow()

    // One-shot PGN delivery, consumed by the UI (clipboard / share intent).
    private val _pgnEvent = MutableStateFlow<PgnEvent?>(null)
    val pgnEvent: StateFlow<PgnEvent?> = _pgnEvent.asStateFlow()

    // ----- internal game/render state (all mutated on the Main thread) -----
    private var initialFen: String? = null
    private var replay: Replay = Chess.replay("")
    private var viewIndex: Int = 0
    private var selectedSquare: Int? = null
    private var legalDests: Set<Int> = emptySet()
    private var pendingMove: Move? = null
    private var awaitingServer: Boolean = false
    private var streamStatus: String = ""
    private var streamWinner: String? = null
    private var opponentName: String = "Opponent"
    private var opponentOfferedDraw: Boolean = false
    private var menuOpen: Boolean = false
    private var confirmation: Confirmation? = null
    private var message: String? = null
    private var confirmMoves: Boolean = true

    private var streamJob: Job? = null

    private val myColorString = if (myColor == Color.WHITE) "white" else "black"

    init {
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
                opponentName =
                    (if (myColor == Color.WHITE) event.black.name else event.white.name)
                        ?.takeIf { it.isNotBlank() } ?: "Opponent"
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

        // Re-derive the whole timeline from the authoritative move list.
        replay = try {
            Chess.replay(state.moves, initialFen)
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
            selectedSquare = null
            legalDests = emptySet()
        }

        recompute()
    }

    // ----- history browsing -----

    fun stepBack() {
        if (pendingMove != null) return
        if (viewIndex > 0) {
            viewIndex--
            clearSelection()
            recompute()
        }
    }

    fun stepForward() {
        if (pendingMove != null) return
        if (viewIndex < replay.positions.lastIndex) {
            viewIndex++
            clearSelection()
            recompute()
        }
    }

    // ----- move interaction -----

    fun onSquareTap(square: Int) {
        val positions = replay.positions
        // Read-only while a move is pending/in-flight, while reviewing history,
        // when the game is over, or when it is not our turn.
        if (pendingMove != null) return
        if (viewIndex != positions.lastIndex) return
        if (isTerminal()) return
        val latest = replay.finalPosition
        if (latest.sideToMove != myColor) return

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

    private fun makeMove(from: Int, to: Int) {
        val latest = replay.finalPosition
        // Promotion picker is deferred; auto-queen for now (see BoardScreen note).
        val isPromotion = latest.pieceAt(from)?.type == PieceType.PAWN &&
            (Square.rank(to) == 0 || Square.rank(to) == 7)
        val uci = Square.name(from) + Square.name(to) + if (isPromotion) "q" else ""
        val move = Chess.parseUci(uci, latest)
        clearSelection()
        if (move == null) {
            recompute()
            return
        }
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

    // ----- PGN export -----

    fun copyPgn() { menuOpen = false; recompute(); fetchPgn(PgnDelivery.CLIPBOARD) }
    fun sharePgn() { menuOpen = false; recompute(); fetchPgn(PgnDelivery.SHARE) }

    private fun fetchPgn(delivery: PgnDelivery) {
        viewModelScope.launch {
            try {
                _pgnEvent.value = PgnEvent(api.exportGamePgn(gameId), delivery)
            } catch (e: Exception) {
                message = "Couldn't fetch PGN"
                recompute()
            }
        }
    }

    /** UI reports how it delivered the PGN. [note] (if any) is surfaced to the user. */
    fun onPgnDelivered(note: String?) {
        _pgnEvent.value = null
        if (note != null) { message = note; recompute() }
    }

    // ----- messages -----

    fun dismissMessage() { message = null; recompute() }

    // ----- helpers -----

    private fun clearSelection() {
        selectedSquare = null
        legalDests = emptySet()
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
        var lastByMe = false
        when {
            pendingMove != null -> {
                lastFrom = pendingMove!!.from
                lastTo = pendingMove!!.to
                lastByMe = true
            }
            viewIndex > 0 -> {
                val step = replay.steps[viewIndex - 1]
                lastFrom = step.move.from
                lastTo = step.move.to
                lastByMe = step.byWhite == (myColor == Color.WHITE)
            }
        }

        val displayStatus = Chess.status(displayPosition)
        val inCheck = displayStatus is GameStatus.Check || displayStatus is GameStatus.Checkmate
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
            lastMoveByMe = lastByMe,
            checkedKingSquare = checkedKing,
            mode = mode,
            canStepBack = viewIndex > 0,
            canStepForward = viewIndex < positions.lastIndex,
            terminal = terminal,
            menuOpen = menuOpen,
            confirmation = confirmation,
            message = message,
        )
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
    }
}
