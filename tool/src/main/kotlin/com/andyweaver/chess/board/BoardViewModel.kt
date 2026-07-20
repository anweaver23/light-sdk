package com.andyweaver.chess.board

import androidx.lifecycle.viewModelScope
import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.engine.Color
import com.andyweaver.chess.engine.GameStatus
import com.andyweaver.chess.engine.Move
import com.andyweaver.chess.engine.MoveGenerator
import com.andyweaver.chess.engine.MoveRecord
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
import kotlin.math.roundToInt

/** Which set of controls the bottom bar shows. */
enum class BottomMode { BROWSE, PENDING }

/** A destructive/irreversible action awaiting a CONFIRM/✕ overlay. */
enum class Confirmation { RESIGN, DRAW, ABORT }

/**
 * One piece slide. [startSquare] is where the overlay piece begins, [endSquare] where it
 * lands — the static board hides its piece on [endSquare] for the duration so it isn't
 * drawn twice.
 */
data class PieceSlide(
    val startSquare: Int,
    val endSquare: Int,
    val piece: Piece,
)

/**
 * The slide(s) to animate for one browsed-position transition — usually a single piece,
 * but castling animates both the king and rook. A forward step animates from→to; a
 * backward step animates to→from, so undoing a move reads as literal reverse playback
 * (the just-made move's piece slides back home; nothing else moves, the last-move frames
 * simply reappear on the earlier move). [id] restarts the animation even when the same
 * squares repeat.
 */
data class AnimatedMove(
    val slides: List<PieceSlide>,
    val id: Long,
    /** Extra delay (ms) before the slide starts — used only for "arrival" animations
     * (opening a live game/review on its last move) so the eye registers the pre-move
     * position before it moves. Zero (immediate) for every other animation: manual
     * step/scrub, a live opponent move landing, or the user's own staged move. */
    val startDelayMs: Int = 0,
)

/**
 * The [PieceSlide]s for one replay [step], with landing pieces read from [newBoard] (the
 * board of the DESTINATION position — exactly the squares the board hides during the
 * slide). [forward] = stepping into the move (from→to); else undoing it (to→from).
 * Castling yields two slides (king + rook, using the true king destination even in
 * Chess960 where the move records the rook square); other moves yield one. Drops yield
 * none (handled by the caller). Shared by the board and review view models.
 */
internal fun stepSlides(step: MoveRecord, newBoard: List<Piece?>, forward: Boolean): List<PieceSlide> {
    val move = step.move
    fun slide(from: Int, to: Int): PieceSlide? {
        val start = if (forward) from else to
        val land = if (forward) to else from
        val piece = newBoard.getOrNull(land) ?: return null
        return PieceSlide(startSquare = start, endSquare = land, piece = piece)
    }
    if (move.isCastle) {
        val (kingTo, rookFrom, rookTo) = MoveGenerator.castleSquares(step.before, move)
        return listOfNotNull(slide(move.from, kingTo), slide(rookFrom, rookTo))
    }
    return listOfNotNull(slide(move.from, move.to))
}

/**
 * Squares the active variant highlights as its goal (a single combined dashed border in
 * the UI): King of the Hill's 2×2 centre, Racing Kings' 8th rank. Empty for every other
 * variant. Shared by the live board and the review screen so both render it identically.
 */
internal fun goalSquaresFor(variant: Variant): Set<Int> = when (variant) {
    Variant.KING_OF_THE_HILL -> setOf(
        Square.of(3, 3), Square.of(4, 3), Square.of(3, 4), Square.of(4, 4), // d4, e4, d5, e5
    )
    Variant.RACING_KINGS -> (0..7).map { Square.of(it, 7) }.toSet() // the 8th rank
    else -> emptySet()
}

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
    /** Live check only (not checkmate) — draws the marching-ants dashed border. */
    val checkedKingSquare: Int? = null,
    /** Checkmate only — draws no border; the king itself renders turned sideways. */
    val checkmateKingSquare: Int? = null,
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
    /**
     * Material display (Lichess-style), non-Crazyhouse only. [myCaptured] are the
     * opponent-coloured pieces I've captured; [opponentCaptured] are my-coloured
     * pieces the opponent has captured. At most one advantage is > 0 (the leader's
     * point lead); the other is 0. Empty/zero for Crazyhouse (the pockets cover it).
     */
    val myCaptured: List<PieceType> = emptyList(),
    val opponentCaptured: List<PieceType> = emptyList(),
    val myAdvantage: Int = 0,
    val opponentAdvantage: Int = 0,
    /**
     * Review only: each player's clock remaining at the displayed position (formatted).
     * [myClockLabel] renders bottom-right, [opponentClockLabel] top-right. Both show the
     * base time before either side has moved. Null on the live board and for
     * correspondence games (no per-move clock).
     */
    val myClockLabel: String? = null,
    val opponentClockLabel: String? = null,
    val viewFraction: Float = 1f,
    /** A one-shot piece slide to play for the transition into this position (or null). */
    val animatingMove: AnimatedMove? = null,
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
    seededLastMove: String? = null,
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
    // Last-move highlight seeded from the home row's UCI, so the highlight paints on
    // the very first frame instead of popping in when the stream's move list arrives.
    // Used only while the replay has no moves of its own (the seed-only state).
    private val seededLastFrom: Int? = seededLastMove?.takeIf { it.length >= 4 }?.let { Square.fromName(it.substring(0, 2)) }
    private val seededLastTo: Int? = seededLastMove?.takeIf { it.length >= 4 }?.let { Square.fromName(it.substring(2, 4)) }
    private var opponentOfferedDraw: Boolean = false
    // Set true once we accept/decline an incoming offer, to hide the prompt until
    // the stream reflects the change; reset when the offer clears.
    private var drawResponsePending: Boolean = false
    private var menuOpen: Boolean = false
    private var confirmation: Confirmation? = null
    private var message: String? = null
    private var confirmMoves: Boolean = true
    private var showLegalMoves: Boolean = true

    private var streamJob: Job? = null

    private val myColorString = if (myColor == Color.WHITE) "white" else "black"

    // The animation currently in play, and a counter to key each new one. This PERSISTS
    // across recomputes (it is emitted on every one, never nulled after emit) — the UI
    // keys its Animatable off `animatingMove.id`, so re-emitting the same id is a no-op
    // (the Animatable rests at 1f once done) and a *new* id triggers a fresh slide. That
    // id-keying is what prevents unrelated recomputes (menu, settings, selection) from
    // replaying an animation — so we must NOT null this between them, or an intermediate
    // recompute during an arrival slide would flip the key to null, reset the Animatable,
    // and flash the piece from destination back to origin. Each genuine transition
    // (step/move/live-move/arrival) overwrites it with a new id; everything else leaves it.
    // Declared BEFORE init{} so init{}'s seed-time arrival animation isn't wiped by a
    // late `= null` initializer (property initializers run in textual order).
    private var pendingAnim: AnimatedMove? = null
    private var animCounter = 0L

    // The UCI of the last move whose arrival animation has already been shown, and the
    // exact AnimatedMove (same id) that showed it — set ONLY when an arrival animation
    // actually plays (seed or resetView), never eagerly. Guards applyState's resetView
    // branch against re-animating the SAME move a second time once the real GameFull
    // arrives: when re-emitting the "already shown" case, it must re-emit THIS SAME
    // AnimatedMove object (same id), not null — ChessBoard keys its Animatable off
    // anim?.id, so flipping animatingMove to null (a different key) resets/aborts
    // whatever's mid-flight instead of just "not starting a new one". Re-emitting the
    // same id is a harmless no-op once the slide has already finished.
    private var arrivalAnimatedUci: String? = null
    private var lastArrivalAnim: AnimatedMove? = null

    init {
        // Seed the displayed board from the game's current FEN (passed by the home
        // screen) so the FIRST render shows the real position instead of the standard
        // start — avoids a start-position flash before the live stream arrives. When
        // gameFull lands it reconciles from the authoritative move list as usual.
        if (currentFen != null) {
            replay = runCatching { Chess.replay("", currentFen, variant) }.getOrElse { Chess.replay("", null, variant) }
            viewIndex = replay.positions.lastIndex
            // If the home row also seeded a last move, show ITS arrival animation from
            // the very first frame instead of a static already-arrived position — the
            // seeded state has no move list yet, so buildStepAnim can't be used; build
            // the slide directly from the seeded destination board. Doesn't handle
            // castling specially (a plain single-piece slide guess) — fine, since this
            // is superseded within about one network round-trip once the real GameFull
            // arrives and applyState rebuilds the animation from the authoritative replay.
            if (seededLastFrom != null && seededLastTo != null) {
                val piece = replay.positions.last().board.getOrNull(seededLastTo)
                if (piece != null) {
                    animCounter += 1
                    val anim = AnimatedMove(
                        slides = listOf(PieceSlide(seededLastFrom, seededLastTo, piece)),
                        id = animCounter,
                        startDelayMs = ARRIVAL_ANIM_DELAY_MS,
                    )
                    pendingAnim = anim
                    lastArrivalAnim = anim
                    arrivalAnimatedUci = seededLastMove
                }
            }
        }
        // Keep the confirm-moves preference current; defaults to true until loaded.
        viewModelScope.launch { settings.confirmMoves.collect { confirmMoves = it } }
        // Show-legal-moves affects rendering directly, so re-render on change.
        viewModelScope.launch { settings.showLegalMoves.collect { showLegalMoves = it; recompute() } }
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
                variant = Variant.fromKey(event.variant.key)
                // Lichess reports "startpos" even for variants with a fixed but
                // NON-standard start (Racing Kings, Horde); fall back to the variant's
                // real starting FEN so we don't render the standard chess start.
                initialFen = event.initialFen.takeUnless { it == "startpos" || it.isBlank() }
                    ?: variant.startFen
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
        val pendingLanded = pendingMove != null && run {
            val last = replay.steps.lastOrNull()
            (last != null && last.move.toUci() == pendingMove!!.toUci()) ||
                newPositions.size > oldPositions.size
        }
        if (pendingLanded) {
            pendingMove = null
            awaitingServer = false
        }

        // Any change in ply count means the board moved under any selection.
        if (newPositions.size != oldPositions.size) {
            clearSelection()
        }

        pendingAnim = when {
            // Already animated at staging time (Fix 1) — re-animating here would yo-yo
            // the piece back to its origin and re-slide, since the static board already
            // shows the destination from the optimistic display.
            pendingLanded -> null
            // First connection to the stream (screen just opened/foregrounded, or a
            // reconnect after backgrounding): animate the arrival of the last move —
            // but only if it's not the SAME move the seed state (or a previous
            // GameFull) already animated, or we'd re-trigger a redundant/stuttering
            // second slide for a move already shown. A genuinely newer last move (e.g.
            // one landed between the home screen's snapshot and the board opening, or
            // while backgrounded) still animates normally.
            resetView -> {
                val newLastUci = replay.steps.lastOrNull()?.move?.toUci()
                if (newLastUci != null && newLastUci == arrivalAnimatedUci) {
                    // Same move already shown (seed, or an earlier connect) — re-emit the
                    // SAME AnimatedMove (see the field doc above) so this doesn't reset an
                    // in-flight slide.
                    lastArrivalAnim
                } else {
                    arrivalAnimatedUci = newLastUci
                    val anim = buildStepAnim(newPositions.lastIndex - 1, newPositions.lastIndex)
                        ?.copy(startDelayMs = ARRIVAL_ANIM_DELAY_MS)
                    lastArrivalAnim = anim
                    anim
                }
            }
            // A move arrived live while browsing the latest position (the opponent's move).
            wasAtLatest && newPositions.size == oldPositions.size + 1 ->
                buildStepAnim(oldPositions.lastIndex, newPositions.lastIndex)
            else -> null
        }

        recompute()
    }

    // ----- history browsing -----
    // (pendingAnim/animCounter now declared above, before init{} — see that comment.)

    /**
     * The slide to animate for a single-step transition [oldIndex] → [newIndex]. Only
     * adjacent steps animate (multi-step jumps and drops snap). Forward: from→to.
     * Backward: to→from (reverse) — the moved piece slides back home. The overlay piece
     * is taken from the DESTINATION position at the landing square, which is exactly the
     * square the static board will hide during the slide.
     */
    private fun buildStepAnim(oldIndex: Int, newIndex: Int): AnimatedMove? {
        val delta = newIndex - oldIndex
        if (delta != 1 && delta != -1) return null
        val step = replay.steps.getOrNull(minOf(oldIndex, newIndex)) ?: return null
        if (step.move.isDrop) return null
        val slides = stepSlides(step, replay.positions[newIndex].board, forward = delta == 1)
        if (slides.isEmpty()) return null
        animCounter += 1
        return AnimatedMove(slides = slides, id = animCounter)
    }

    /**
     * The slide(s) to animate for a move just staged by the user, built directly from
     * the pre-move [before] position and the [move] (it isn't in the replay yet, so
     * [stepSlides]/a [MoveRecord] don't apply). Drops have no board start square and
     * don't animate, matching [stepSlides]'s drop handling.
     */
    private fun buildMoveAnim(before: Position, move: Move): AnimatedMove? {
        if (move.isDrop) return null
        val slides = if (move.isCastle) {
            val (kingTo, rookFrom, rookTo) = MoveGenerator.castleSquares(before, move)
            listOfNotNull(
                before.pieceAt(move.from)?.let { PieceSlide(move.from, kingTo, it) },
                before.pieceAt(rookFrom)?.let { PieceSlide(rookFrom, rookTo, it) },
            )
        } else {
            listOfNotNull(before.pieceAt(move.from)?.let { PieceSlide(move.from, move.to, it) })
        }
        if (slides.isEmpty()) return null
        animCounter += 1
        return AnimatedMove(slides = slides, id = animCounter)
    }

    fun stepBack() {
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex > 0) {
            val old = viewIndex
            viewIndex--
            pendingAnim = buildStepAnim(old, viewIndex)
            clearSelection()
            recompute()
        }
    }

    fun stepForward() {
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex < replay.positions.lastIndex) {
            val old = viewIndex
            viewIndex++
            pendingAnim = buildStepAnim(old, viewIndex)
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

    /** Scrub to a position by fraction of the whole game (0 = start, 1 = latest). */
    fun seekToFraction(fraction: Float) {
        if (pendingMove != null || pendingPromotion != null) return
        val last = replay.positions.lastIndex
        if (last <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)
        if (target != viewIndex) {
            val old = viewIndex
            viewIndex = target
            pendingAnim = buildStepAnim(old, target)
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
        pendingAnim = buildMoveAnim(replay.finalPosition, move)
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
        // Stop the staged move's slide if it's still mid-flight — the move is being
        // undone, so there's nothing to animate to. (Nulling here is a deliberate
        // "cancel this animation", not the incidental wipe recompute used to do.)
        pendingAnim = null
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
            // Seed-only state (no move list yet): use the last move passed by the home
            // row so the highlight doesn't flash in when the stream arrives.
            replay.steps.isEmpty() -> {
                lastFrom = seededLastFrom
                lastTo = seededLastTo
            }
        }

        // Don't reveal check/checkmate for a move the user hasn't confirmed yet.
        val moveUnconfirmed = pendingMove != null && !awaitingServer
        val displayStatus = Chess.status(displayPosition)
        val isCheck = !moveUnconfirmed && displayStatus is GameStatus.Check
        val isCheckmate = !moveUnconfirmed && displayStatus is GameStatus.Checkmate
        val checkedKing = if (isCheck) {
            displayPosition.kingSquare(displayPosition.sideToMove).takeIf { it >= 0 }
        } else {
            null
        }
        val checkmateKing = if (isCheckmate) {
            displayPosition.kingSquare(displayPosition.sideToMove).takeIf { it >= 0 }
        } else {
            null
        }

        val subtitle = if (terminal) {
            resultSubtitle(latest)
        } else {
            buildString {
                // Lead with the variant name for non-standard games, before the turn.
                if (variant != Variant.STANDARD) append("${variant.displayName} · ")
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

        // Compare against the variant's canonical starting complement (not
        // replay.positions.first()) so material is stable from the very first seeded
        // frame: during the seed-only state the replay's "first" position IS the
        // current position, which would otherwise read as zero captures and then
        // flash to the real count when the move list streams in. Every non-fromPosition
        // game has identical starting piece counts to this reference, so no flash.
        val material = computeMaterial(referenceStart(), displayPosition, variant, myColor)

        _uiState.value = BoardUiState(
            opponentName = opponentName,
            subtitle = subtitle,
            board = displayPosition.board,
            myColor = myColor,
            flipped = myColor == Color.BLACK,
            selectedSquare = selectedSquare,
            legalDestinations = if (showLegalMoves) legalDests else emptySet(),
            lastMoveFrom = lastFrom,
            lastMoveTo = lastTo,
            checkedKingSquare = checkedKing,
            checkmateKingSquare = checkmateKing,
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
            dropTargets = if (showLegalMoves) dropTargets else emptySet(),
            goalSquares = goalSquares(),
            myCaptured = material.myCaptured,
            opponentCaptured = material.opponentCaptured,
            myAdvantage = material.myAdvantage,
            opponentAdvantage = material.opponentAdvantage,
            viewFraction = if (positions.size > 1) viewIndex.toFloat() / positions.lastIndex else 1f,
            animatingMove = pendingAnim,
            message = message,
        )
        // NOTE: pendingAnim is intentionally NOT nulled here — it persists so unrelated
        // recomputes re-emit the same id (a UI no-op), rather than flipping it to null and
        // resetting an in-flight slide. See the pendingAnim declaration for the full why.
    }

    // The variant's canonical starting position, used as the stable reference for the
    // material count (see the call site). Cheap to build; falls back to the standard
    // start if the variant's start FEN somehow fails to parse.
    private fun referenceStart(): Position =
        runCatching { variant.startFen?.let { Position.fromFen(it, variant) } ?: Chess.startPosition }
            .getOrDefault(Chess.startPosition)

    // Squares the active variant highlights as its goal — see [goalSquaresFor].
    private fun goalSquares(): Set<Int> = goalSquaresFor(variant)

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
