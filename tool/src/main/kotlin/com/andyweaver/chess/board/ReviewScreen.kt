package com.andyweaver.chess.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.engine.Color as EngineColor
import com.andyweaver.chess.engine.GameStatus
import com.andyweaver.chess.engine.Move
import com.andyweaver.chess.engine.Piece
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Position
import com.andyweaver.chess.engine.Replay
import com.andyweaver.chess.engine.SanReplay
import com.andyweaver.chess.engine.Square
import com.andyweaver.chess.engine.Variant
import com.andyweaver.chess.settings.ChessSettings
import com.andyweaver.chess.settings.MoveStepSpeed
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The analysis sandbox's entire logical state and behaviour, with no Compose, no Android
 * and no view model around it — so every rule that matters (what a tap selects, when a move
 * forks the line, what survives a reset) is checked by plain unit tests
 * (`AnalysisSandboxTest`) instead of on an emulator, which is this codebase's convention for
 * anything invisible in a screenshot.
 *
 * Semantics are the live board's, exactly (see BoardViewModel's `analysis*` members, which
 * this mirrors — they are private members there, so they can't simply be called from here):
 *  • the line is seeded with the REAL game's whole authoritative move list ([analysisSeed]),
 *    based at the game's own initial [Position] — NOT a FEN snapshot of the viewed position,
 *    which would silently drop Crazyhouse pockets and `~` promoted marks — so stepping back
 *    walks the actual game all the way to its start and any point of it can be branched from;
 *  • branching is truncate-and-replace ([forkLine]): a move played at the viewed index
 *    discards everything after it. One line, never a variation tree;
 *  • moves come from the VIEWED position, so either colour may move — whoever is to move
 *    there;
 *  • slides use the shared [stepAnim], so stepping, scrubbing and forking animate exactly
 *    like the live board.
 */
internal class AnalysisSandbox private constructor(
    private val base: Position,
    /** Where analysis was entered from, so a reset returns here rather than to the game's start. */
    val entryIndex: Int,
    moves: List<String>,
    replay: Replay,
    viewIndex: Int,
) {
    /** The sandbox line as UCI: the game's own moves, truncated/extended by any branching. */
    var moves: List<String> = moves
        private set
    var replay: Replay = replay
        private set

    /** Which position of the branch is on screen. Moves are played FROM this one. */
    var viewIndex: Int = viewIndex
        private set

    var selectedSquare: Int? = null
        private set
    var legalDestinations: Set<Int> = emptySet()
        private set
    var selectedDrop: PieceType? = null
        private set
    var dropTargets: Set<Int> = emptySet()
        private set
    var pendingPromotion: Pair<Int, Int>? = null
        private set

    // One-shot slide, read (and cleared) by the state builder — see [consumeAnim].
    private var pendingAnim: AnimatedMove? = null
    private var animCounter = 0L

    companion object {
        /** Seed a sandbox from [game] at [entryIndex] — see [analysisSeed]. */
        fun seededFrom(game: Replay, variant: Variant, entryIndex: Int): AnalysisSandbox {
            val seed = analysisSeed(game, variant, entryIndex)
            return AnalysisSandbox(seed.base, seed.viewIndex, seed.moves, seed.replay, seed.viewIndex)
        }
    }

    /** The branch's variant (carried by its own positions, so a re-derived line keeps it). */
    val variant: Variant get() = replay.initial.variant

    val lastIndex: Int get() = replay.positions.lastIndex

    /** The position on screen — where the side to move may play. */
    fun viewed(): Position = replay.positions[viewIndex.coerceIn(0, lastIndex)]

    /** Take the pending slide and clear it: it must play once, not on every recompose. */
    fun consumeAnim(): AnimatedMove? = pendingAnim.also { pendingAnim = null }

    // ----- browsing the branch (which is the real game's history, plus any sandbox moves) --

    fun stepBack() {
        if (pendingPromotion != null) return
        if (viewIndex > 0) moveTo(viewIndex - 1, animate = true)
    }

    fun stepForward() {
        if (pendingPromotion != null) return
        if (viewIndex < lastIndex) moveTo(viewIndex + 1, animate = true)
    }

    // Multi-step jumps snap (stepAnim only animates adjacent transitions anyway).
    fun stepToStart() {
        if (pendingPromotion != null) return
        if (viewIndex != 0) moveTo(0, animate = true)
    }

    fun stepToEnd() {
        if (pendingPromotion != null) return
        if (viewIndex != lastIndex) moveTo(lastIndex, animate = true)
    }

    fun seekToFraction(fraction: Float) {
        if (pendingPromotion != null) return
        if (lastIndex <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * lastIndex).roundToInt().coerceIn(0, lastIndex)
        if (target != viewIndex) moveTo(target, animate = true)
    }

    private fun moveTo(target: Int, animate: Boolean) {
        val old = viewIndex
        viewIndex = target
        pendingAnim = if (animate) stepAnim(replay, old, target, ++animCounter) else null
        clearSelection()
    }

    // ----- playing moves ----------------------------------------------------

    fun onSquareTap(square: Int) {
        if (pendingPromotion != null) return
        val pos = viewed()

        val drop = selectedDrop
        if (drop != null) {
            if (square in dropTargets) commitDrop(drop, square) else clearSelection()
            return
        }

        val piece = pos.pieceAt(square)
        val selected = selectedSquare

        if (selected == null) {
            if (piece != null && piece.color == pos.sideToMove) select(square, pos)
            return
        }

        when {
            square == selected -> clearSelection()
            square in legalDestinations -> makeMove(pos, selected, square)
            piece != null && piece.color == pos.sideToMove -> select(square, pos)
            else -> clearSelection()
        }
    }

    /**
     * Crazyhouse: pick up (or put down) a reserve piece. The bars never swap sides — bottom
     * is always the viewer's — so a tap on the bar whose colour isn't to move is inert
     * rather than a silent wrong-colour drop.
     */
    fun onPocketTap(type: PieceType, color: EngineColor) {
        if (pendingPromotion != null) return
        val pos = viewed()
        if (color != pos.sideToMove) return
        if (pos.pocket.count(pos.sideToMove, type) <= 0) return
        if (selectedDrop == type) {
            clearSelection()
            return
        }
        clearSelection()
        selectedDrop = type
        dropTargets = Chess.legalDropSquares(pos, type)
    }

    fun choosePromotion(type: PieceType) {
        val (from, to) = pendingPromotion ?: return
        pendingPromotion = null
        // The view index can't move while a promotion is pending (every browse entry point
        // bails on it), so the viewed position is still the one the pawn was picked up from.
        commitMove(viewed(), from, to, promotion = type)
    }

    fun cancelPromotion() {
        pendingPromotion = null
        clearSelection()
    }

    fun clearSelection() {
        selectedSquare = null
        legalDestinations = emptySet()
        selectedDrop = null
        dropTargets = emptySet()
    }

    private fun select(square: Int, pos: Position) {
        selectedSquare = square
        legalDestinations = Chess.legalDestinations(pos, square)
    }

    private fun commitDrop(type: PieceType, square: Int) {
        clearSelection()
        apply(Move(from = square, to = square, drop = type))
    }

    private fun makeMove(pos: Position, from: Int, to: Int) {
        val isPromotion = pos.pieceAt(from)?.type == PieceType.PAWN &&
            (Square.rank(to) == 0 || Square.rank(to) == 7)
        if (isPromotion) {
            pendingPromotion = from to to
            clearSelection()
            return
        }
        commitMove(pos, from, to, promotion = null)
    }

    private fun commitMove(pos: Position, from: Int, to: Int, promotion: PieceType?) {
        val suffix = when (promotion) {
            PieceType.QUEEN -> "q"
            PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"
            PieceType.KNIGHT -> "n"
            else -> ""
        }
        val move = Chess.parseUci(Square.name(from) + Square.name(to) + suffix, pos)
        clearSelection()
        if (move != null) apply(move)
    }

    // Truncate-and-replace, then rebuild from the base position: every derived value
    // (pockets, promoted marks, material, canStepForward…) comes out of the rebuilt replay,
    // so nothing can be left pointing past the new tip.
    private fun apply(move: Move) {
        val oldIndex = viewIndex
        moves = forkLine(moves, oldIndex, move.toUci())
        replay = Chess.replayFrom(base, moves)
        viewIndex = lastIndex
        // Always a single forward step from where the user was, so it animates like any move.
        pendingAnim = stepAnim(replay, oldIndex, viewIndex, ++animCounter)
        clearSelection()
    }
}

/**
 * Read-only replay of a finished game for the history screen. Reuses the board
 * renderer ([ChessBoard]) and browse controls ([MaterialReviewBottomBar] /
 * [CrazyhouseReviewBottomBar]); no live stream and no move-making. Opens on the
 * final position so the user steps backward through the game. SAN the engine can't
 * follow truncates the replay (see [truncationNote]) rather than discarding it; only a
 * game where NOTHING parsed falls back to a short message instead of a board.
 */
class ReviewViewModel(
    movesSan: String,
    initialFen: String?,
    private val myColor: EngineColor,
    private val variant: Variant = Variant.STANDARD,
    private val clocks: List<Int> = emptyList(),
    private val initialClockSeconds: Int? = null,
    private val flaggedColor: EngineColor? = null,
    private val isCorrespondence: Boolean = false,
    // Only the "Move step speed" preference is read here — review has no other settings.
    // Nullable so the pure-JVM tests can build a view model without a DataStore.
    private val settings: ChessSettings? = null,
) : LightViewModel<Unit>() {

    // Replay under the game's own variant. Fall back to the variant's fixed start FEN
    // when the export omits initialFen (Lichess reports "startpos" for Racing Kings /
    // Horde, whose start isn't standard) — and treat a literal "startpos"/blank the same
    // way, since that's what the board stream sends and Position.fromFen can't parse it.
    private val startFen: String? =
        initialFen?.takeUnless { it == "startpos" || it.isBlank() } ?: variant.startFen

    // LENIENT: an unmatched SAN token stops the replay but keeps everything before it,
    // so one move our generator disagrees with (a variant quirk, a disambiguation
    // difference) no longer throws away the whole game. runCatching still covers a
    // start FEN we can't parse at all, which leaves nothing to show.
    private val sanReplay: SanReplay? = runCatching {
        Chess.replaySanLenient(movesSan, startFen, variant)
    }.getOrNull()

    private val replay: Replay? = sanReplay?.replay

    /**
     * Nothing at all could be read: either the start position failed to parse, or the
     * very first move did. (A game with no moves is NOT a failure — it reviews as the
     * starting position.)
     */
    val parseFailed: Boolean
        get() = sanReplay == null || (sanReplay.truncated && sanReplay.movesReplayed == 0)

    /**
     * Set when the replay stopped early: "first N of M moves", shown as one quiet line
     * under the top bar so the user knows the game is only partly here. Null otherwise.
     */
    val truncationNote: String?
        get() = sanReplay
            ?.takeIf { it.truncated && it.movesReplayed > 0 }
            ?.let { "first ${it.movesReplayed} of ${it.totalTokens} moves" }

    // Open on the game's final position; the user steps/skips backward from there.
    private var viewIndex = replay?.positions?.lastIndex ?: 0

    // Hold-to-repeat speed for the browse arrows. MUST be declared before `_uiState`:
    // that property's initializer calls buildState(), which reads this — and Kotlin runs
    // property initializers top-to-bottom, so declaring it lower down leaves it null during
    // construction and every review screen crashes with an NPE on the first build. Exactly
    // the same ordering trap `pendingAnim` documents just below.
    private var moveStepSpeed: MoveStepSpeed = MoveStepSpeed.DEFAULT
    private var dragEnabled: Boolean = false

    // One-shot slide to play into the next state (see BoardViewModel.buildStepAnim).
    // Declared (and populated for the opening state, below) BEFORE `_uiState` so the
    // very first `buildState()` call can see an animation for the game's last move —
    // property initializers run top-to-bottom, so if this stayed below `_uiState` (as
    // it did before) it would still be null when `buildState()` first ran.
    private var pendingAnim: AnimatedMove? = null
    // Declared here (above `_uiState`) for the same init-order reason as the fields above.
    private var lastAnim: AnimatedMove? = null
    private var animCounter = 0L

    /**
     * The animation the UI is told about. NEVER goes back to null once set — it is only
     * ever REPLACED by a newer slide.
     *
     * [ChessBoard] keys its `Animatable` on `animatingMove?.id`, so emitting null after a
     * real id is a KEY CHANGE: it resets the animation instead of merely declining to start
     * one, killing whatever is in flight. Any recompose from an unrelated source — a
     * settings flow emitting, a stream event — would otherwise abort the slide, which is
     * exactly how the review screen's arrival animation broke. Re-emitting the SAME object
     * is a no-op once it has finished and non-destructive while it is still running.
     */
    private fun latchAnim(fresh: AnimatedMove?): AnimatedMove? {
        if (fresh != null) lastAnim = fresh
        return lastAnim
    }

    /** The reviewed game's slide for a single-step transition — see the shared [stepAnim]. */
    private fun buildStepAnim(oldIndex: Int, newIndex: Int): AnimatedMove? =
        replay?.let { stepAnim(it, oldIndex, newIndex, ++animCounter) }

    private val _uiState = MutableStateFlow(
        run {
            val lastIndex = replay?.positions?.lastIndex ?: 0
            // Arrival animation for opening on the final move: same short start delay as
            // the live board's arrival slide (see ARRIVAL_ANIM_DELAY_MS) so the eye
            // registers the pre-move position before it starts sliding — without it the
            // slide plays instantly on screen entry and is easy to miss.
            if (lastIndex >= 1) {
                pendingAnim = buildStepAnim(lastIndex - 1, lastIndex)?.copy(startDelayMs = ARRIVAL_ANIM_DELAY_MS)
            }
            val initial = buildState()
            // One-shot, same as pushState: don't let a later buildState() call (e.g.
            // stepToStart/stepToEnd, which call it directly with no framing) see this.
            pendingAnim = null
            initial
        },
    )
    val uiState: StateFlow<BoardUiState> = _uiState.asStateFlow()

    init {
        // Collected (not read once) so changing the setting applies to a review already open.
        val prefs = settings
        if (prefs != null) {
            viewModelScope.launch {
                prefs.moveStepSpeed.collect { moveStepSpeed = it; render() }
            }
            viewModelScope.launch {
                prefs.dragAndDrop.collect { dragEnabled = it; render() }
            }
        }
    }


    // ----- menu / analysis sandbox -----

    // The action menu (currently just "Analysis"; PGN/email export are the reason it exists
    // as a menu rather than a single icon). Non-null `analysis` = the sandbox is open, which
    // is also what swaps the top-bar title and makes the board interactive.
    private var menuOpen = false
    private var analysis: AnalysisSandbox? = null

    /** Matches the live board: the menu is meaningless inside the sandbox, so it can't open there. */
    fun openMenu() { if (analysis != null) return; menuOpen = true; render() }
    fun closeMenu() { menuOpen = false; render() }

    /**
     * Enter the sandbox on the position currently being reviewed, with the game's whole
     * history behind it (see [AnalysisSandbox]) so the user can step back past the entry
     * point and branch from anywhere. A game that didn't parse has no line to branch from.
     */
    fun enterAnalysis() {
        val r = replay ?: return
        menuOpen = false
        analysis = AnalysisSandbox.seededFrom(r, variant, viewIndex)
        render()
    }

    /**
     * Long-press while already in analysis: throw the branch away and start over from the
     * real game — at the position analysis was ENTERED on, not the game's start.
     */
    fun resetAnalysis() {
        val current = analysis ?: return
        val r = replay ?: return
        menuOpen = false
        analysis = AnalysisSandbox.seededFrom(r, variant, current.entryIndex)
        render()
    }

    /** Long-press on the board: enter the sandbox, or reset it if it's already open. */
    fun onBoardLongPress() {
        if (analysis != null) resetAnalysis() else enterAnalysis()
    }

    /** Leaving the sandbox returns to the review at the position it was left showing. */
    fun exitAnalysis() {
        analysis = null
        render()
    }

    // Back exits the sandbox before it exits the screen — same priority as the live board.
    override fun onBackPressed(): Boolean {
        if (analysis != null) { exitAnalysis(); return true }
        return false
    }

    // Board/pocket interaction exists only inside the sandbox; outside it the review board is
    // read-only and the UI passes no tap handler at all (see ChessBoard's onSquareTap).
    fun onSquareTap(square: Int) { analysis?.let { it.onSquareTap(square); render() } }
    fun onPocketTap(type: PieceType, color: EngineColor) { analysis?.let { it.onPocketTap(type, color); render() } }
    fun choosePromotion(type: PieceType) { analysis?.let { it.choosePromotion(type); render() } }
    fun cancelPromotion() { analysis?.let { it.cancelPromotion(); render() } }

    // ----- browsing -----

    // Push a new state built with [pendingAnim] set for the [old]→[viewIndex] step, then
    // clear it so it's a one-shot.
    private fun pushState(old: Int) {
        pendingAnim = buildStepAnim(old, viewIndex)
        _uiState.value = buildState()
        pendingAnim = null
    }

    /** Render whichever timeline is on screen — the sandbox branch, or the game itself. */
    private fun render() {
        _uiState.value = analysis?.let { buildAnalysisState(it) } ?: buildState()
    }

    fun stepBack() {
        analysis?.let { it.stepBack(); render(); return }
        if (viewIndex > 0) { val old = viewIndex; viewIndex--; pushState(old) }
    }

    fun stepForward() {
        analysis?.let { it.stepForward(); render(); return }
        val last = replay?.positions?.lastIndex ?: return
        if (viewIndex < last) { val old = viewIndex; viewIndex++; pushState(old) }
    }

    fun stepToStart() {
        analysis?.let { it.stepToStart(); render(); return }
        if (viewIndex != 0) { viewIndex = 0; _uiState.value = buildState() }
    }

    fun stepToEnd() {
        analysis?.let { it.stepToEnd(); render(); return }
        val last = replay?.positions?.lastIndex ?: return
        if (viewIndex != last) { viewIndex = last; _uiState.value = buildState() }
    }

    /** Scrub to a position by fraction of the whole game (0 = start, 1 = final). */
    fun seekToFraction(fraction: Float) {
        analysis?.let { it.seekToFraction(fraction); render(); return }
        val last = replay?.positions?.lastIndex ?: return
        if (last <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)
        if (target != viewIndex) { val old = viewIndex; viewIndex = target; pushState(old) }
    }

    /**
     * The sandbox branch on screen — the review counterpart of BoardViewModel's
     * `recomputeAnalysis`. Clocks are dropped (a hypothetical position has none) and the
     * menu is force-closed, matching the live board.
     */
    private fun buildAnalysisState(a: AnalysisSandbox): BoardUiState {
        val positions = a.replay.positions
        val idx = a.viewIndex.coerceIn(0, positions.lastIndex)
        val pos = positions[idx]

        // Straight out of the branch's own timeline, which carries the real game's history:
        // entering analysis keeps the highlight for the move that led here, and stepping
        // back re-reveals each move as it's undone. Index 0 has no last move.
        var lastFrom: Int? = null
        var lastTo: Int? = null
        if (idx > 0) {
            val step = a.replay.steps[idx - 1]
            lastFrom = step.move.from
            lastTo = step.move.to
        }

        val status = Chess.status(pos)
        val checkedKing = if (status is GameStatus.Check) pos.kingSquare(pos.sideToMove).takeIf { it >= 0 } else null
        val checkmateKing = if (status is GameStatus.Checkmate) pos.kingSquare(pos.sideToMove).takeIf { it >= 0 } else null

        // Material stays relative to the FIXED bottom side (myColor): the board's
        // orientation never changes in analysis, only who is allowed to move.
        val material = computeMaterial(positions.first(), pos, a.variant, myColor)

        return BoardUiState(
            board = pos.board,
            myColor = myColor,
            flipped = myColor == EngineColor.BLACK,
            moverColor = pos.sideToMove,
            selectedSquare = a.selectedSquare,
            legalDestinations = a.legalDestinations,
            lastMoveFrom = lastFrom,
            lastMoveTo = lastTo,
            checkedKingSquare = checkedKing,
            checkmateKingSquare = checkmateKing,
            promotionActive = a.pendingPromotion != null,
            canStepBack = idx > 0,
            canStepForward = idx < positions.lastIndex,
            terminal = status.isTerminal,
            menuOpen = false,
            variant = a.variant,
            myPocket = pos.pocket.forColor(myColor),
            opponentPocket = pos.pocket.forColor(myColor.opposite),
            // Either side may move here, so the tappable reserve bar follows the side to
            // move; the bars themselves stay put (bottom = mine) so the board never jumps.
            opponentPocketTappable = pos.sideToMove != myColor,
            selectedDrop = a.selectedDrop,
            dropTargets = a.dropTargets,
            goalSquares = goalSquaresFor(a.variant),
            checkCounts = threeCheckCounts(positions, idx, a.variant),
            myCaptured = material.myCaptured,
            opponentCaptured = material.opponentCaptured,
            myAdvantage = material.myAdvantage,
            opponentAdvantage = material.opponentAdvantage,
            viewFraction = if (positions.size > 1) idx.toFloat() / positions.lastIndex else 1f,
            totalPlies = positions.lastIndex,
            moveStepIntervalMs = moveStepSpeed.intervalMs,
            dragEnabled = dragEnabled,
            animatingMove = latchAnim(a.consumeAnim()),
            analysisActive = true,
        )
    }

    private fun buildState(): BoardUiState {
        val r = replay ?: return BoardUiState(
            myColor = myColor,
            flipped = myColor == EngineColor.BLACK,
            menuOpen = menuOpen,
        )
        val positions = r.positions
        val idx = viewIndex.coerceIn(0, positions.lastIndex)
        val pos = positions[idx]

        var lastFrom: Int? = null
        var lastTo: Int? = null
        if (idx > 0) {
            val step = r.steps[idx - 1]
            lastFrom = step.move.from
            lastTo = step.move.to
        }

        val status = Chess.status(pos)
        val checkedKing = if (status is GameStatus.Check) pos.kingSquare(pos.sideToMove).takeIf { it >= 0 } else null
        val checkmateKing = if (status is GameStatus.Checkmate) pos.kingSquare(pos.sideToMove).takeIf { it >= 0 } else null

        // Material at the position under review (updates as the user steps/scrolls).
        val material = computeMaterial(positions.first(), pos, variant, myColor)

        return BoardUiState(
            board = pos.board,
            myColor = myColor,
            flipped = myColor == EngineColor.BLACK,
            lastMoveFrom = lastFrom,
            lastMoveTo = lastTo,
            checkedKingSquare = checkedKing,
            checkmateKingSquare = checkmateKing,
            canStepBack = idx > 0,
            canStepForward = idx < positions.lastIndex,
            menuOpen = menuOpen,
            variant = variant,
            // Variant goal squares (KotH centre, Racing Kings rank 8) — same combined
            // dashed border as the live board.
            goalSquares = goalSquaresFor(variant),
            // Three-check running tally (badge on each king); empty for other variants.
            checkCounts = threeCheckCounts(positions, idx, variant),
            // Crazyhouse reserves at the reviewed position (empty for other variants).
            myPocket = pos.pocket.forColor(myColor),
            opponentPocket = pos.pocket.forColor(myColor.opposite),
            myCaptured = material.myCaptured,
            opponentCaptured = material.opponentCaptured,
            myAdvantage = material.myAdvantage,
            opponentAdvantage = material.opponentAdvantage,
            myClockLabel = clockLabelFor(myColor == EngineColor.WHITE, idx),
            opponentClockLabel = clockLabelFor(myColor != EngineColor.WHITE, idx),
            viewFraction = if (positions.size > 1) viewIndex.toFloat() / positions.lastIndex else 1f,
            totalPlies = positions.lastIndex,
            moveStepIntervalMs = moveStepSpeed.intervalMs,
            dragEnabled = dragEnabled,
            animatingMove = latchAnim(pendingAnim),
        )
    }

    // A player's clock remaining at position [idx], formatted. White moves produce the
    // odd positions (1,3,…), Black the even ones; clocks[p-1] is the mover's time after
    // position p. Before a side has moved (including the start position) it shows the
    // base time. Null for correspondence and when no clock data is present.
    private fun clockLabelFor(white: Boolean, idx: Int): String? {
        if (isCorrespondence) return null
        val thisColor = if (white) EngineColor.WHITE else EngineColor.BLACK
        // At the final position, the side that flagged shows 0:00 (it never made a last
        // move, so there's no clocks entry recording the flag).
        val lastIndex = replay?.positions?.lastIndex ?: 0
        if (idx == lastIndex && flaggedColor == thisColor) return formatClock(0)
        val initialCentis = initialClockSeconds?.times(100)
        // Most recent position of this colour at or before idx.
        val pos = if (white) {
            if (idx % 2 == 1) idx else idx - 1
        } else {
            if (idx % 2 == 0) idx else idx - 1
        }
        val centis = if (pos < 1) initialCentis else (clocks.getOrNull(pos - 1) ?: initialCentis)
        return centis?.let { formatClock(it) }
    }
}

class ReviewScreen(
    sealedActivity: SealedLightActivity,
    private val movesSan: String,
    private val initialFen: String?,
    private val myColorName: String,
    private val title: String,
    private val result: String,
    private val variant: Variant = Variant.STANDARD,
    private val clocks: List<Int> = emptyList(),
    private val initialClockSeconds: Int? = null,
    private val flaggedColorName: String? = null,
    private val isCorrespondence: Boolean = false,
) : LightScreen<Unit, ReviewViewModel>(sealedActivity) {

    override val viewModelClass: Class<ReviewViewModel>
        get() = ReviewViewModel::class.java

    override fun createViewModel(): ReviewViewModel {
        val color = if (myColorName.equals("black", ignoreCase = true)) EngineColor.BLACK else EngineColor.WHITE
        val flagged = when (flaggedColorName?.lowercase()) {
            "white" -> EngineColor.WHITE
            "black" -> EngineColor.BLACK
            else -> null
        }
        return ReviewViewModel(
            movesSan, initialFen, color, variant, clocks, initialClockSeconds, flagged, isCorrespondence,
            settings = ChessSettings(lightContext.dataStore),
        )
    }

    // Result line, led by the variant name for non-standard games (matches the board
    // and home screens), e.g. "Crazyhouse · white · loss".
    private val resultLine: String
        get() = if (variant != Variant.STANDARD) "${variant.displayName} · $result" else result

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Hand-rolled rightButton instead of LightTopBar's own slot, exactly as
                    // BoardScreen does: that slot renders via an SDK-internal button view that
                    // takes no custom Modifier. This Box reproduces its position (TopEnd).
                    Box(modifier = Modifier.fillMaxWidth()) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                                contentDescription = "Back",
                            ),
                            // In the sandbox the title becomes the literal "Analysis" (matching
                            // the live board) so it's unmistakable that these moves are local
                            // and are not part of the game being reviewed.
                            center = if (state.analysisActive) {
                                LightTopBarCenter.Text("Analysis")
                            } else {
                                LightTopBarCenter.TwoLineDetail(line1 = title, line2 = resultLine)
                            },
                        )
                        // Dropped inside the sandbox (back is the exit path, as on the live
                        // board) and for a game that couldn't be parsed, whose only entry —
                        // Analysis — has no move list to branch from.
                        if (!state.analysisActive && !viewModel.parseFailed) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .height(3f.gridUnitsAsDp())
                                    .padding(horizontal = 1f.gridUnitsAsDp()),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                LightIcon(
                                    icon = LightIcons.ELLIPSES,
                                    contentDescription = "Menu",
                                    modifier = Modifier.lightClickable(onClick = { viewModel.openMenu() }),
                                )
                            }
                        }
                    }

                    // Partly-readable game: one quiet line saying how far we got, rather than
                    // silently showing a truncated game (or, as before, refusing the whole one).
                    viewModel.truncationNote?.let { note ->
                        LightText(
                            text = note,
                            variant = LightTextVariant.Fine,
                            align = TextAlign.Center,
                            lighten = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 0.5f.gridUnitsAsDp()),
                        )
                    }

                    when {
                        viewModel.parseFailed -> {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 1f.gridUnitsAsDp()),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightText(
                                    text = "This game can't be reviewed here.",
                                    variant = LightTextVariant.Copy,
                                    align = TextAlign.Center,
                                )
                            }
                        }

                        state.variant == Variant.CRAZYHOUSE -> {
                            // Same layout as the live Crazyhouse board: opponent reserves above
                            // the board, mine in the bottom bar — but with all four browse
                            // arrows, so the reserves pack tight to fit alongside them.
                            // Read-only during review; in the sandbox the bar whose colour is to
                            // move becomes tappable (the bars never swap position).
                            val opponentDrops = state.analysisActive && state.opponentPocketTappable
                            TopPocketBar(
                                pocket = state.opponentPocket,
                                color = state.myColor.opposite,
                                verticalPadUnits = 0.15f,
                                selectedDrop = if (opponentDrops) state.selectedDrop else null,
                                onTap = if (opponentDrops) {
                                    { type -> viewModel.onPocketTap(type, state.myColor.opposite) }
                                } else {
                                    null
                                },
                            )
                            CenteredBoard(
                                reservedUnits = 0f,
                                topRightLabel = state.opponentClockLabel,
                                bottomRightLabel = state.myClockLabel,
                            ) {
                                ChessBoard(
                                    state = state,
                                    onSquareTap = if (state.analysisActive) viewModel::onSquareTap else null,
                                    onLongPress = { viewModel.onBoardLongPress() },
                                )
                            }
                            if (state.analysisActive) {
                                // Same bar, but with a tappable reserve — see the composable.
                                CrazyhouseAnalysisBottomBar(
                                    state = state,
                                    onBack = { viewModel.stepBack() },
                                    onForward = { viewModel.stepForward() },
                                    onSkipStart = { viewModel.stepToStart() },
                                    onSkipEnd = { viewModel.stepToEnd() },
                                    onSeek = { viewModel.seekToFraction(it) },
                                    onPocketTap = if (state.opponentPocketTappable) {
                                        null
                                    } else {
                                        { type -> viewModel.onPocketTap(type, state.myColor) }
                                    },
                                )
                            } else {
                                CrazyhouseReviewBottomBar(
                                    state = state,
                                    onBack = { viewModel.stepBack() },
                                    onForward = { viewModel.stepForward() },
                                    onSkipStart = { viewModel.stepToStart() },
                                    onSkipEnd = { viewModel.stepToEnd() },
                                    onSeek = { viewModel.seekToFraction(it) },
                                )
                            }
                        }

                        else -> {
                            // Same layout as the live standard board: opponent's captures above
                            // the board (aligned to its left edge), mine in the bottom bar with
                            // all four browse arrows. Both clocks sit rotated at the board's
                            // right edge (opponent top, me bottom).
                            // Horde's collapsed pawn badge is pocket-sized and needs the
                            // taller bank, exactly as on the live board.
                            val topBankUnits =
                                if (collapsesHordePawns(state.variant, state.materialBottom)) {
                                    HORDE_BANK_HEIGHT_UNITS
                                } else {
                                    2f
                                }
                            CenteredBoard(
                                reservedUnits = topBankUnits,
                                topBank = { inset ->
                                    ReviewMaterialBank(
                                        captured = state.opponentCaptured,
                                        capturedColor = state.materialBottom,
                                        advantage = state.opponentAdvantage,
                                        variant = state.variant,
                                        heightUnits = topBankUnits,
                                        inset = inset,
                                    )
                                },
                                topRightLabel = state.opponentClockLabel,
                                bottomRightLabel = state.myClockLabel,
                            ) {
                                ChessBoard(
                                    state = state,
                                    // Read-only outside the sandbox: a null handler drops the tap
                                    // recognizer AND its haptic, so a dead tap doesn't buzz.
                                    onSquareTap = if (state.analysisActive) viewModel::onSquareTap else null,
                                    onLongPress = { viewModel.onBoardLongPress() },
                                )
                            }

                            MaterialReviewBottomBar(
                                state = state,
                                onBack = { viewModel.stepBack() },
                                onForward = { viewModel.stepForward() },
                                onSkipStart = { viewModel.stepToStart() },
                                onSkipEnd = { viewModel.stepToEnd() },
                                onSeek = { viewModel.seekToFraction(it) },
                            )
                        }
                    }
                }

                if (state.promotionActive) {
                    // Either side may move in the sandbox, so the picker uses the ACTUAL
                    // mover's colour, not the fixed bottom-of-board one.
                    ReviewPromotionOverlay(
                        color = state.moverColor ?: state.myColor,
                        variant = state.variant,
                        onChoose = { viewModel.choosePromotion(it) },
                        onCancel = { viewModel.cancelPromotion() },
                    )
                }

                if (state.menuOpen) {
                    ReviewMenuOverlay(
                        onAnalysis = { viewModel.enterAnalysis() },
                        onClose = { viewModel.closeMenu() },
                    )
                }
            }
        }
    }
}

/**
 * The review screen's action menu. Deliberately identical in look and interaction to
 * BoardScreen's own `ActionMenuOverlay` (a full-screen column: "Menu" top bar, tappable rows,
 * a single CLOSE button in the bottom bar) — that one is private to BoardScreen.kt and bound
 * to BoardViewModel, so it can't be called from here. It holds only "Analysis" today; copy
 * PGN and export-to-email are the reason it is a menu rather than one more top-bar icon.
 */
@Composable
private fun ReviewMenuOverlay(onAnalysis: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            center = LightTopBarCenter.Text("Menu"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Local sandbox — play any legal move from the reviewed position. Also reachable
            // by long-pressing the board; back is the exit path.
            ReviewMenuRow("Analysis", onAnalysis)
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = onClose,
                    contentDescription = "Close menu",
                ),
            ),
        )
    }
}

@Composable
private fun ReviewMenuRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Subheading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
    )
}

/** BoardScreen's promotion picker, which is private there — same layout, plain callbacks. */
@Composable
private fun ReviewPromotionOverlay(
    color: EngineColor,
    variant: Variant,
    onChoose: (PieceType) -> Unit,
    onCancel: () -> Unit,
) {
    // Order the offered pieces by usefulness: queen first.
    val choices = variant.promotionChoices
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            center = LightTopBarCenter.Text("Promote to"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val cell = 6f.gridUnitsAsDp()
            // Four choices fill the width exactly at this cell size, so Antichess's fifth
            // (the king) has to wrap rather than shrink every target to fit.
            Column(
                verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                choices.chunked(if (choices.size > 4) 3 else 4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp())) {
                        row.forEach { type ->
                            Box(
                                modifier = Modifier
                                    .size(cell)
                                    .lightClickable { onChoose(type) },
                                contentAlignment = Alignment.Center,
                            ) {
                                PieceGlyph(piece = Piece(color, type), squareSize = cell)
                            }
                        }
                    }
                }
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = onCancel,
                    contentDescription = "Cancel promotion",
                ),
            ),
        )
    }
}

/**
 * The Crazyhouse review bottom bar with a TAPPABLE reserve, for the analysis sandbox.
 * Duplicates `CrazyhouseReviewBottomBar` (BoardScreen.kt) — which hard-codes `onTap = null`
 * because review is read-only — since that file is owned elsewhere. [onPocketTap] is null
 * when it is the opponent's turn in the line, so the bottom (viewer's) bar goes inert and the
 * TOP one takes the drops instead; the bars themselves never swap sides.
 */
@Composable
private fun CrazyhouseAnalysisBottomBar(
    state: BoardUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSkipStart: () -> Unit,
    onSkipEnd: () -> Unit,
    onSeek: (Float) -> Unit,
    onPocketTap: ((PieceType) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .moveScrubX(currentFraction = state.viewFraction, totalPlies = state.totalPlies, onSeek = onSeek)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReviewSkipControl(leading = true, LightIcons.BACK, "First move", state.canStepBack, onSkipStart)
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack, state.moveStepIntervalMs, onBack)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            POCKET_ORDER.filter { (state.myPocket[it] ?: 0) > 0 }.forEach { type ->
                PocketPiece(
                    type = type,
                    color = state.myColor,
                    count = state.myPocket[type] ?: 0,
                    cell = MY_POCKET_CELL_UNITS.gridUnitsAsDp(),
                    selected = onPocketTap != null && state.selectedDrop == type,
                    onTap = onPocketTap?.let { tap -> { tap(type) } },
                    horizontalPadUnits = 0.0f,
                )
            }
        }
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward, state.moveStepIntervalMs, onForward)
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        ReviewSkipControl(leading = false, LightIcons.ARROW_RIGHT, "Last move", state.canStepForward, onSkipEnd)
    }
}

// BoardScreen's SkipControl (and its BarSide enum / bar-height constant) are private to that
// file, so the skip arrows of the bar above are reproduced here to the same metrics.
private const val REVIEW_SKIP_BAR_HEIGHT_UNITS = 1.4f

@Composable
private fun ReviewSkipControl(
    leading: Boolean,
    icon: LightIconConfiguration,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bar: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(REVIEW_SKIP_BAR_HEIGHT_UNITS.gridUnitsAsDp())
                .background(LightThemeTokens.colors.content),
        )
    }
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .then(if (enabled) Modifier.lightClickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading) bar()
        LightIcon(icon = icon, contentDescription = contentDescription)
        if (!leading) bar()
    }
}

/**
 * The opponent's reviewed-position material bank (non-Crazyhouse). The fan starts at
 * [inset] so it aligns to the centered board's left edge. Clocks live at the board's
 * right edge (see [CenteredBoard]), not here.
 */
@Composable
private fun ReviewMaterialBank(
    captured: List<PieceType>,
    capturedColor: EngineColor,
    advantage: Int,
    inset: Dp,
    variant: Variant = Variant.STANDARD,
    heightUnits: Float = 2f,
) {
    MaterialRow(
        captured = captured,
        capturedColor = capturedColor,
        advantage = advantage,
        variant = variant,
        heightUnits = heightUnits,
        startPad = inset,
    )
}

// Format a centisecond clock value as m:ss (or h:mm:ss past an hour).
private fun formatClock(centis: Int): String {
    val totalSeconds = centis / 100
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
