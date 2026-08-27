package com.andyweaver.chess.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.andyweaver.chess.ui.ChessTheme
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.engine.Color as EngineColor
import com.andyweaver.chess.engine.GameOutcome
import com.andyweaver.chess.engine.GameStatus
import com.andyweaver.chess.engine.Move
import com.andyweaver.chess.engine.OutcomeReason
import com.andyweaver.chess.engine.Piece
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Position
import com.andyweaver.chess.engine.Replay
import com.andyweaver.chess.engine.Square
import com.andyweaver.chess.engine.Variant
import com.andyweaver.chess.lichess.LichessApi
import com.andyweaver.chess.lichess.LichessImportResult
import com.andyweaver.chess.settings.ChessSettings
import com.andyweaver.chess.settings.MoveStepSpeed
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcon
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The in-person ("hot-seat") game: two humans sharing one device, with NO Lichess
 * connection during play. It reuses the board renderer ([ChessBoard]) and the live-play
 * bottom bars ([MaterialBottomBar] / [CrazyhousePocketBar]); the pure-Kotlin engine is
 * the sole authority for legal moves and game-over (via [Chess.outcome]).
 *
 * Two orientations (chosen on the new-game screen, switchable mid-game from the menu):
 * - Side-by-side: the board flips after each move so the mover is always at the bottom.
 * - Across: the board stays fixed (White at the bottom) and the far side's pieces are
 *   drawn rotated 180° (via [BoardUiState.acrossMode]) to read upright to a player opposite.
 *
 * Racing Kings is the exception, because both armies start on the SAME side: there is no
 * far row to rotate on its own, so the two modes swap over. Side-by-side keeps the board
 * still, and across flips it after each move AND rotates every piece with it, so the board
 * turns as one rigid unit toward whoever is on move (see [BoardUiState.rigidPieceRotation]).
 *
 * Moves apply IMMEDIATELY (no confirm step — both players are present). Uploading the
 * finished game to Lichess is offered ONLY through the menu ("Upload to Lichess") — there
 * is no auto-prompt at game end. NOTE: a finished local game is NOT persisted — once this
 * screen is left, it can no longer be uploaded (out of scope for v1; would require
 * persisting local games).
 */
class LocalGameViewModel(
    private val api: LichessApi,
    private val settings: ChessSettings,
    variantKey: String,
    initialAcross: Boolean,
) : LightViewModel<Unit>() {

    private val variant: Variant = Variant.fromKey(variantKey)

    // The game's starting FEN. Racing Kings / Horde have a fixed non-standard start;
    // Chess960 is randomised here (no Lichess to hand us one); everything else starts
    // from the standard position (null → Position.START in Chess.replay).
    private val startFen: String? = when (variant) {
        Variant.CHESS960 -> randomChess960Fen()
        else -> variant.startFen
    }

    private var across: Boolean = initialAcross

    // The moves played so far (UCI), and the replay derived from them. Rebuilt from the
    // UCI list after each move so we lean entirely on the engine (matches how the live
    // board and review reconstruct their timelines).
    private val moves: MutableList<String> = mutableListOf()
    private var replay: Replay = Chess.replay(emptyList<String>(), startFen, variant)
    private var viewIndex: Int = 0

    private var selectedSquare: Int? = null
    private var legalDests: Set<Int> = emptySet()
    private var selectedDrop: PieceType? = null
    private var dropTargets: Set<Int> = emptySet()
    private var pendingPromotion: Pair<Int, Int>? = null

    private var menuOpen = false
    private var showLegalMoves = true
    private var moveStepSpeed: MoveStepSpeed = MoveStepSpeed.DEFAULT
    // NOTE: read by buildState(), so it MUST stay declared above _uiState (whose initializer
    // calls buildState() during construction).
    private var dragEnabled = false
    // Declared ABOVE _uiState: buildState() reads it during construction.
    private var scrubBarEnabled = true

    // "Are you sure you want to exit?" overlay — in-person games have no server copy, so
    // leaving mid-game silently loses progress (unlike the online board, whose game lives
    // safely on Lichess regardless of when you back out). confirmingExit shows the overlay;
    // exitConfirmed is set right before letting the SECOND back-navigation through, mirroring
    // the two-step "confirm then proceed" pattern goBack()/onBackPressed() require.
    private var confirmingExit = false
    private var exitConfirmed = false

    private var outcome: GameOutcome = GameOutcome.Ongoing

    // One-shot slide for the next state (see ReviewViewModel — same one-shot discipline:
    // set before buildState(), cleared after, so a later plain buildState() doesn't replay it).
    private var pendingAnim: AnimatedMove? = null
    private var lastAnim: AnimatedMove? = null

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
    private var animCounter = 0L

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<BoardUiState> = _uiState.asStateFlow()

    /** Upload-to-Lichess dialog state, kept off [BoardUiState] so the board stays a pure render. */
    sealed interface Dialog {
        /** Player-name form (shown auto at game end, or from the menu) before uploading. */
        data object NameEntry : Dialog
        data object Uploading : Dialog
        data class UploadDone(val url: String) : Dialog
        data class UploadError(val message: String) : Dialog
    }

    private val _dialog = MutableStateFlow<Dialog?>(null)
    val dialog: StateFlow<Dialog?> = _dialog.asStateFlow()

    /**
     * A human decision that ends the game outright — the in-person equivalents of the online
     * board's resign / agree-draw actions. There is no server to arbitrate, so the engine is
     * simply told the result (see [outcomeForEndAction]); because the game can be uploaded to
     * Lichess afterwards, getting the winner right here IS the recorded result.
     *
     * Both resignations are offered explicitly rather than "resign" meaning "the side to
     * move": either player may act at any time, and with two humans sharing one screen an
     * implicit subject would be ambiguous.
     */
    enum class EndAction { AGREE_DRAW, WHITE_RESIGNS, BLACK_RESIGNS }

    // The pending end-game confirmation, if any. Kept off BoardUiState (like [dialog]) so
    // the board state stays a pure render of the position; the screen collects it directly.
    private val _endConfirmation = MutableStateFlow<EndAction?>(null)
    val endConfirmation: StateFlow<EndAction?> = _endConfirmation.asStateFlow()

    /** Which player-name field the full-screen editor is currently editing (null = the form). */
    enum class NameField { WHITE, BLACK }
    private val _editingName = MutableStateFlow<NameField?>(null)
    val editingName: StateFlow<NameField?> = _editingName.asStateFlow()

    // The White/Black name fields for the upload PGN tags. Held here (not remembered in the
    // composable) so they keep a stable lifetime matching the LP keyboard's backing VM —
    // otherwise keystrokes edit a detached state (see HomeScreen.loginTokenState). Left EMPTY;
    // the form shows "anonymous" as a placeholder and a blank field uploads as "anonymous",
    // so the player never has to clear a pre-filled value — they just type.
    val whiteNameState = TextFieldState()
    val blackNameState = TextFieldState()

    fun editName(field: NameField) { _editingName.value = field }
    fun finishEditingName() { _editingName.value = null }

    init {
        viewModelScope.launch { settings.showLegalMoves.collect { showLegalMoves = it; render() } }
        viewModelScope.launch { settings.moveStepSpeed.collect { moveStepSpeed = it; render() } }
        viewModelScope.launch { settings.dragAndDrop.collect { dragEnabled = it; render() } }
        viewModelScope.launch { settings.scrubBar.collect { scrubBarEnabled = it; render() } }
    }

    // ----- convenience -----

    private val latest: Position get() = replay.finalPosition
    private fun isOver(): Boolean = outcome.isOver

    /** The position currently on screen — where the side to move may play (Task B). */
    private fun viewed(): Position {
        val positions = replay.positions
        return positions[viewIndex.coerceIn(0, positions.lastIndex)]
    }

    // ----- move interaction -----
    // A move may be made from the CURRENTLY VIEWED position, not just the latest: the back
    // arrow acts as undo and forward as redo, and playing a move from a past position forks
    // the line (truncating the future — see applyMove). A terminal viewed position simply
    // has no legal moves, so taps there no-op; that's what lets you step out of a finished
    // position and play a different move.

    fun onSquareTap(square: Int, animate: Boolean = true) {
        if (pendingPromotion != null) return
        val pos = viewed()
        val mover = pos.sideToMove

        // Crazyhouse: a pocket piece is held — this tap chooses where to drop it.
        val drop = selectedDrop
        if (drop != null) {
            if (square in dropTargets) commitDrop(drop, square, animate) else clearSelectionAndRender()
            return
        }

        val piece = pos.pieceAt(square)
        val selected = selectedSquare
        if (selected == null) {
            if (piece != null && piece.color == mover) select(square, pos)
            return
        }
        when {
            square == selected -> clearSelectionAndRender()
            square in legalDests -> makeMove(pos, selected, square, animate)
            piece != null && piece.color == mover -> select(square, pos)
            else -> clearSelectionAndRender()
        }
    }

    private fun select(square: Int, pos: Position) {
        selectedSquare = square
        legalDests = Chess.legalDestinations(pos, square)
        render()
    }

    /** Crazyhouse: pick up (or put back) a reserve piece to drop (at the viewed position). */
    fun onPocketTap(type: PieceType) {
        if (pendingPromotion != null) return
        val pos = viewed()
        if (pos.pocket.count(pos.sideToMove, type) <= 0) return
        if (selectedDrop == type) { clearSelectionAndRender(); return }
        clearSelection()
        selectedDrop = type
        dropTargets = Chess.legalDropSquares(pos, type)
        render()
    }

    private fun commitDrop(type: PieceType, square: Int, animate: Boolean = true) {
        clearSelection()
        applyMove(Move(from = square, to = square, drop = type), animate = animate)
    }

    private fun makeMove(pos: Position, from: Int, to: Int, animate: Boolean = true) {
        val isPromotion = pos.pieceAt(from)?.type == PieceType.PAWN &&
            (Square.rank(to) == 0 || Square.rank(to) == 7)
        if (isPromotion) {
            pendingPromotion = from to to
            clearSelection()
            render()
            return
        }
        commitMove(pos, from, to, promotion = null, animate = animate)
    }

    fun choosePromotion(type: PieceType, animate: Boolean = true) {
        val (from, to) = pendingPromotion ?: return
        pendingPromotion = null
        // viewIndex can't change while a promotion is pending (browse taps are guarded),
        // so the viewed position is still the one the pawn moved from.
        commitMove(viewed(), from, to, promotion = type, animate = animate)
    }

    fun cancelPromotion() {
        pendingPromotion = null
        clearSelection()
        render()
    }

    private fun commitMove(pos: Position, from: Int, to: Int, promotion: PieceType?, animate: Boolean = true) {
        val suffix = when (promotion) {
            PieceType.QUEEN -> "q"
            PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"
            PieceType.KNIGHT -> "n"
            else -> ""
        }
        val uci = Square.name(from) + Square.name(to) + suffix
        val move = Chess.parseUci(uci, pos)
        clearSelection()
        if (move == null) { render(); return }
        applyMove(move, animate = animate)
    }

    // Apply a legal move from the VIEWED position, rebuild the replay, and animate the
    // arrival like the live board. A move made from a non-latest position FORKS the line:
    // the future (everything after the viewed point) is dropped before appending.
    private fun applyMove(move: Move, animate: Boolean = true) {
        val oldIndex = viewIndex
        // Fork: truncate the move list to the viewed point before appending. (No-op when
        // already at the latest position, where viewIndex == moves.size.)
        if (viewIndex < moves.size) moves.subList(viewIndex, moves.size).clear()
        moves.add(move.toUci())
        replay = Chess.replay(moves, startFen, variant)
        viewIndex = replay.positions.lastIndex
        // Immediate forward slide (no arrival delay) for the move just played.
        pendingAnim = if (animate) buildStepAnim(oldIndex, viewIndex) else null
        outcome = Chess.outcome(replay)
        // No auto-prompt on game end: uploading to Lichess is offered ONLY via the menu
        // ("Upload to Lichess"), so a finished game doesn't jump straight into the name form.
        clearSelection()
        _uiState.value = buildState()
        pendingAnim = null
    }

    // ----- history browsing -----

    /** The in-person game's slide for a single-step transition — see the shared [stepAnim]. */
    private fun buildStepAnim(oldIndex: Int, newIndex: Int): AnimatedMove? =
        stepAnim(replay, oldIndex, newIndex, ++animCounter)

    private fun pushState(old: Int) {
        pendingAnim = buildStepAnim(old, viewIndex)
        clearSelection()
        _uiState.value = buildState()
        pendingAnim = null
    }

    fun stepBack() {
        if (pendingPromotion != null) return
        if (viewIndex > 0) { val old = viewIndex; viewIndex--; pushState(old) }
    }

    fun stepForward() {
        if (pendingPromotion != null) return
        if (viewIndex < replay.positions.lastIndex) { val old = viewIndex; viewIndex++; pushState(old) }
    }

    /** Scrub to a position by fraction of the whole game (0 = start, 1 = latest). */
    fun seekToFraction(fraction: Float) {
        if (pendingPromotion != null) return
        val last = replay.positions.lastIndex
        if (last <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)
        if (target != viewIndex) { val old = viewIndex; viewIndex = target; pushState(old) }
    }

    // ----- menu / play mode / new game -----

    fun openMenu() { menuOpen = true; render() }
    fun closeMenu() { menuOpen = false; render() }

    fun togglePlayMode() { across = !across; menuOpen = false; render() }

    fun newGame() {
        moves.clear()
        replay = Chess.replay(emptyList<String>(), startFen, variant)
        viewIndex = 0
        outcome = GameOutcome.Ongoing
        menuOpen = false
        pendingAnim = null
        // The one place clearing the latch IS right: a new game should cancel any slide
        // still in flight from the old one, rather than let it play out over a fresh board.
        lastAnim = null
        clearSelection()
        _endConfirmation.value = null
        _dialog.value = null
        _editingName.value = null
        render()
    }

    // ----- ending the game by agreement / resignation -----

    /** Menu tap: close the menu and ask for confirmation — these ends are irreversible. */
    fun requestEndGame(action: EndAction) {
        if (isOver()) return
        menuOpen = false
        _endConfirmation.value = action
        render()
    }

    fun cancelEndGame() { _endConfirmation.value = null }

    /** Applies the confirmed end. The engine's own verdict is overridden from here on. */
    fun confirmEndGame() {
        val action = _endConfirmation.value ?: return
        _endConfirmation.value = null
        if (isOver()) { render(); return }
        outcome = outcomeForEndAction(action)
        render()
    }

    // ----- upload to Lichess -----

    /** Menu-triggered upload: opens the player-name form (upload happens on submit). */
    fun requestUpload() { menuOpen = false; _dialog.value = Dialog.NameEntry; render() }

    fun dismissDialog() {
        if (_dialog.value !is Dialog.Uploading) {
            _dialog.value = null
            _editingName.value = null
        }
    }

    /** Submit the name form: build the PGN with the entered names and import. A blank field
     *  uploads as "anonymous", so players never have to clear a default value. */
    fun submitUpload() {
        val white = whiteNameState.text.toString().trim().ifBlank { "anonymous" }
        val black = blackNameState.text.toString().trim().ifBlank { "anonymous" }
        _editingName.value = null
        _dialog.value = Dialog.Uploading
        viewModelScope.launch(Dispatchers.IO) {
            val date = runCatching {
                java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US).format(java.util.Date())
            }.getOrNull()
            // [Pgn.export] derives the result from the ENGINE's verdict on the move list,
            // which by construction cannot know about a resignation or an agreed draw (see
            // [EndAction]) — it would export "*" and Lichess would import the game as
            // unfinished. This view model holds the authoritative outcome, so pass it as an
            // explicit override; Pgn applies it to both the Result tag and the movetext
            // terminator.
            val token = pgnResultToken(outcome)
            val tags = buildMap {
                put("Event", "Casual Game")
                put("White", white)
                put("Black", black)
                if (date != null) put("Date", date)
            }
            val pgn = Chess.toPgn(replay, tags, resultOverride = token)
            _dialog.value = when (val res = api.importGame(pgn)) {
                is LichessImportResult.Success -> Dialog.UploadDone(res.url)
                is LichessImportResult.Failure -> Dialog.UploadError(res.error)
            }
        }
    }

    // ----- back handling -----

    override fun onBackPressed(): Boolean = when {
        // Editing a name → back to the name form; otherwise dismiss the dialog / menu / promotion.
        _editingName.value != null -> { _editingName.value = null; true }
        _dialog.value != null && _dialog.value !is Dialog.Uploading -> { _dialog.value = null; true }
        _endConfirmation.value != null -> { _endConfirmation.value = null; true }
        menuOpen -> { menuOpen = false; render(); true }
        pendingPromotion != null -> { cancelPromotion(); true }
        exitConfirmed -> false
        moves.isNotEmpty() && !isOver() -> { confirmingExit = true; render(); true }
        else -> false
    }

    /** User chose "exit anyway" on the confirmation overlay: dismiss it and let the NEXT
     *  back-navigation (triggered by the caller right after this) proceed instead of
     *  re-prompting. */
    fun confirmExit() {
        confirmingExit = false
        exitConfirmed = true
        render()
    }

    fun cancelExit() {
        confirmingExit = false
        render()
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }

    // ----- helpers -----

    private fun clearSelection() {
        selectedSquare = null
        legalDests = emptySet()
        selectedDrop = null
        dropTargets = emptySet()
    }

    private fun clearSelectionAndRender() { clearSelection(); render() }

    private fun render() { _uiState.value = buildState() }

    // The variant's canonical starting position, a stable reference for the material count.
    private fun referenceStart(): Position =
        runCatching { startFen?.let { Position.fromFen(it, variant) } ?: Chess.startPosition }
            .getOrDefault(Chess.startPosition)

    private fun buildState(): BoardUiState {
        val positions = replay.positions
        val idx = viewIndex.coerceIn(0, positions.lastIndex)
        val pos = positions[idx]
        val over = isOver()

        // Orientation is stable while browsing: side-by-side keeps the player to move at the
        // LATEST position at the bottom; across keeps White at the bottom and rotates the
        // far side's pieces instead (acrossMode).
        //
        // Racing Kings inverts this: both sides start on the SAME side of the board (no
        // "opposing armies"), so the normal side-by-side/across assumption is backwards —
        // side-by-side must stay put (there's no far side to rotate into view), while across
        // needs the whole-board flip instead (there's no "far player's pieces" to rotate
        // individually).
        val flipsAfterEachMove = if (variant == Variant.RACING_KINGS) across else !across
        val flipped = if (flipsAfterEachMove) latest.sideToMove == EngineColor.BLACK else false
        // …and because that flip turns the board toward whoever is on move, the PIECES have
        // to turn with it: board + pieces rotate as one rigid unit, exactly like picking the
        // phone up and turning it around. So in Racing Kings across mode every piece shares
        // one rotation (180° while flipped, 0° otherwise) — never a per-colour one. Null for
        // every other variant, which keeps the per-colour across rotation, and null in
        // side-by-side, which never flips. See [BoardUiState.rigidPieceRotation].
        val rigidPieceRotation = if (variant == Variant.RACING_KINGS && across) {
            if (flipped) 180f else 0f
        } else {
            null
        }
        // Material/pocket banks are tied to the fixed BOTTOM/TOP of the board (derived from
        // orientation), NOT the mover — so each side's captured pieces / reserves stay put
        // instead of swapping top↔bottom every move. In side-by-side bottom == the mover
        // (unchanged); in across bottom is always White, top always Black (the fix).
        val bottomColor = if (flipped) EngineColor.BLACK else EngineColor.WHITE
        val topColor = bottomColor.opposite
        // The player who can move right now is the side to move at the VIEWED position (you
        // may fork from a past position — Task B). Exposed as moverColor for the promotion
        // picker colour and which pocket is tappable, independent of the fixed bottom side.
        val mover = pos.sideToMove

        var lastFrom: Int? = null
        var lastTo: Int? = null
        if (idx > 0) {
            val step = replay.steps[idx - 1]
            lastFrom = step.move.from
            lastTo = step.move.to
        }

        val status = Chess.status(pos)
        val checkedKing = if (status is GameStatus.Check) pos.kingSquare(pos.sideToMove).takeIf { it >= 0 } else null
        val checkmateKing = if (status is GameStatus.Checkmate) pos.kingSquare(pos.sideToMove).takeIf { it >= 0 } else null

        // Material is computed RELATIVE TO whichever side the banks are showing, and the bars
        // derive their capturedColor from exactly the same value ([BoardUiState.materialBottom]),
        // so the two can never disagree.
        //
        // Normally that side IS the bottom of the board. Racing Kings in across seating is the
        // exception: the board turns as a rigid unit every move, so bottomColor alternates and
        // the banks would swap colour twice a round. Pin them to White — the unflipped
        // orientation — so each side's captures stay put while the board turns underneath.
        val materialColor = if (rigidPieceRotation != null) EngineColor.WHITE else null
        val material = computeMaterial(referenceStart(), pos, variant, materialColor ?: bottomColor)

        // The whose-turn indicator is intentionally dropped (side-by-side makes it obvious;
        // across players alternate in person). Only the game-over text is surfaced — the
        // screen shows it in the top bar when viewing the latest, finished position.
        val subtitle = if (over) describeOutcome(outcome) else ""

        return BoardUiState(
            opponentName = "In-person game",
            subtitle = subtitle,
            board = pos.board,
            myColor = bottomColor,
            flipped = flipped,
            acrossMode = across,
            rigidPieceRotation = rigidPieceRotation,
            materialColor = materialColor,
            moverColor = mover,
            selectedSquare = selectedSquare,
            legalDestinations = if (showLegalMoves) legalDests else emptySet(),
            lastMoveFrom = lastFrom,
            lastMoveTo = lastTo,
            checkedKingSquare = checkedKing,
            checkmateKingSquare = checkmateKing,
            promotionActive = pendingPromotion != null,
            canStepBack = idx > 0,
            canStepForward = idx < positions.lastIndex,
            terminal = over,
            menuOpen = menuOpen,
            confirmingExit = confirmingExit,
            variant = variant,
            myPocket = pos.pocket.forColor(bottomColor),
            opponentPocket = pos.pocket.forColor(topColor),
            selectedDrop = selectedDrop,
            dropTargets = if (showLegalMoves) dropTargets else emptySet(),
            goalSquares = goalSquaresFor(variant),
            checkCounts = threeCheckCounts(positions, idx, variant),
            myCaptured = material.myCaptured,
            opponentCaptured = material.opponentCaptured,
            myAdvantage = material.myAdvantage,
            opponentAdvantage = material.opponentAdvantage,
            viewFraction = if (positions.size > 1) viewIndex.toFloat() / positions.lastIndex else 1f,
            totalPlies = positions.lastIndex,
            moveStepIntervalMs = moveStepSpeed.intervalMs,
            dragEnabled = dragEnabled,
            scrubBarEnabled = scrubBarEnabled,
            animatingMove = latchAnim(pendingAnim),
        )
    }

    // Human-readable game-over text from the authoritative [GameOutcome].
    private fun describeOutcome(outcome: GameOutcome): String {
        val decided = outcome as? GameOutcome.Decided ?: return "game over"
        val winner = when (decided.winner) {
            EngineColor.WHITE -> "White"
            EngineColor.BLACK -> "Black"
            null -> null
        }
        val wins = "$winner wins"
        return when (decided.reason) {
            OutcomeReason.CHECKMATE -> "checkmate · $wins"
            OutcomeReason.STALEMATE -> if (winner == null) "draw · stalemate" else "$wins · stalemate"
            OutcomeReason.INSUFFICIENT_MATERIAL -> "draw · insufficient material"
            OutcomeReason.FIFTY_MOVE_RULE -> "draw · 50-move rule"
            OutcomeReason.KING_IN_CENTER -> "$wins · king in the center"
            OutcomeReason.THREE_CHECKS -> "$wins · three checks"
            OutcomeReason.RACING_KINGS_FINISH -> if (winner == null) "draw · kings home" else "$wins · king home"
            OutcomeReason.ATOMIC_KING_EXPLODED -> "$wins · king explosion"
            OutcomeReason.ANTICHESS_NO_PIECES -> "$wins · no pieces left"
            OutcomeReason.HORDE_DESTROYED -> "$wins · horde destroyed"
            OutcomeReason.FIVEFOLD_REPETITION -> "draw · fivefold repetition"
            OutcomeReason.RESIGNATION -> "$wins · resignation"
            OutcomeReason.DRAW_AGREED -> "draw · agreed"
        }
    }

    private companion object {
        /**
         * A random legal Chess960 starting FEN. Bishops land on opposite-coloured squares
         * (one even file, one odd), the king ends up between the two rooks, and standard
         * "KQkq" castling rights are emitted (the engine resolves them to the outermost
         * rooks on each side, which is correct for 960).
         */
        fun randomChess960Fen(): String {
            val rank = CharArray(8) { ' ' }
            fun free() = (0..7).filter { rank[it] == ' ' }
            fun placeIn(candidates: List<Int>, piece: Char) {
                rank[candidates.random()] = piece
            }
            placeIn(listOf(0, 2, 4, 6), 'B') // light/dark bishop on an even file
            placeIn(listOf(1, 3, 5, 7), 'B') // the other bishop on an odd file
            placeIn(free(), 'Q')
            placeIn(free(), 'N')
            placeIn(free(), 'N')
            val rest = free() // exactly three squares left → rook, king, rook (king between rooks)
            rank[rest[0]] = 'R'; rank[rest[1]] = 'K'; rank[rest[2]] = 'R'
            val upper = String(rank)
            val lower = upper.lowercase()
            return "$lower/pppppppp/8/8/8/8/PPPPPPPP/$upper w KQkq - 0 1"
        }
    }
}

/**
 * The result of a human-decided end to an in-person game. A resignation hands the win to
 * the OTHER colour; an agreed draw has no winner, which is what makes [Pgn]'s result token
 * (a function of the winner alone) come out right.
 */
internal fun outcomeForEndAction(action: LocalGameViewModel.EndAction): GameOutcome = when (action) {
    LocalGameViewModel.EndAction.AGREE_DRAW ->
        GameOutcome.Decided(null, OutcomeReason.DRAW_AGREED)
    LocalGameViewModel.EndAction.WHITE_RESIGNS ->
        GameOutcome.Decided(EngineColor.BLACK, OutcomeReason.RESIGNATION)
    LocalGameViewModel.EndAction.BLACK_RESIGNS ->
        GameOutcome.Decided(EngineColor.WHITE, OutcomeReason.RESIGNATION)
}

/** The PGN result token for a decided [outcome], or null while the game is still running. */
internal fun pgnResultToken(outcome: GameOutcome): String? = when (outcome) {
    is GameOutcome.Ongoing -> null
    is GameOutcome.Decided -> when (outcome.winner) {
        EngineColor.WHITE -> "1-0"
        EngineColor.BLACK -> "0-1"
        null -> "1/2-1/2"
    }
}

class LocalGameScreen(
    sealedActivity: SealedLightActivity,
    private val variantKey: String,
    private val across: Boolean,
    private val token: String,
) : LightScreen<Unit, LocalGameViewModel>(sealedActivity) {

    override val viewModelClass: Class<LocalGameViewModel>
        get() = LocalGameViewModel::class.java

    override fun createViewModel(): LocalGameViewModel =
        LocalGameViewModel(
            api = LichessApi(token),
            settings = ChessSettings(lightContext.dataStore),
            variantKey = variantKey,
            initialAcross = across,
        )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val dialog by viewModel.dialog.collectAsState()

        ChessTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // The side that can move right now — drives the promotion-picker colour and
                // which pocket is tappable (Crazyhouse). Falls back to the bottom side.
                val mover = state.moverColor ?: state.myColor
                Column(modifier = Modifier.fillMaxSize()) {
                    LocalTopBar(
                        state = state,
                        mover = mover,
                        onBack = { goBack() },
                        onMenu = { viewModel.openMenu() },
                        onPocketTap = viewModel::onPocketTap,
                    )

                    if (state.variant == Variant.CRAZYHOUSE) {
                        // No read-only pocket row above the board any more — the top player's
                        // pocket lives in the top bar (LocalTopBar), so the board can grow.
                        // CenteredBoard rather than a bare centred Box: its own side padding
                        // is the same 0.5 units this used, and it exposes the gutters the
                        // scrub bar lives in.
                        CenteredBoard(
                            reservedUnits = 0f,
                            scrubBar = scrubBarSlot(state) { viewModel.seekToFraction(it) },
                        ) {
                            ChessBoard(
                                state = state,
                                onSquareTap = { square, animate -> viewModel.onSquareTap(square, animate) },
                            )
                        }
                        LocalCrazyhouseBottomBar(state = state, mover = mover, viewModel = viewModel)
                    } else {
                        // reservedUnits = 0f and no topBank: the top player's material now
                        // lives in the top bar, so the board takes the full remaining height.
                        CenteredBoard(
                            reservedUnits = 0f,
                            scrubBar = scrubBarSlot(state) { viewModel.seekToFraction(it) },
                        ) {
                            ChessBoard(
                                state = state,
                                onSquareTap = { square, animate -> viewModel.onSquareTap(square, animate) },
                            )
                        }
                        MaterialBottomBar(
                            state = state,
                            onBack = { viewModel.stepBack() },
                            onForward = { viewModel.stepForward() },
                        )
                    }
                }

                if (state.promotionActive) {
                    LocalPromotionOverlay(myColor = mover, variant = state.variant, viewModel = viewModel)
                }

                if (state.menuOpen) {
                    LocalMenuOverlay(
                        across = state.acrossMode,
                        canUpload = token.isNotBlank(),
                        canEndGame = !state.terminal,
                        viewModel = viewModel,
                    )
                }

                if (state.confirmingExit) {
                    LocalConfirmationOverlay(
                        message = "Exit this game? You'll lose progress.",
                        onConfirm = { viewModel.confirmExit(); goBack() },
                        onCancel = { viewModel.cancelExit() },
                    )
                }

                // Ending the game by agreement / resignation. Sits after the exit overlay so
                // an exit prompt (which can only be raised while no other overlay is up) wins
                // if both were somehow set.
                val endConfirmation by viewModel.endConfirmation.collectAsState()
                endConfirmation?.let { action ->
                    LocalConfirmationOverlay(
                        message = when (action) {
                            LocalGameViewModel.EndAction.AGREE_DRAW -> "End this game as a draw?"
                            LocalGameViewModel.EndAction.WHITE_RESIGNS -> "White resigns? Black wins."
                            LocalGameViewModel.EndAction.BLACK_RESIGNS -> "Black resigns? White wins."
                        },
                        onConfirm = { viewModel.confirmEndGame() },
                        onCancel = { viewModel.cancelEndGame() },
                    )
                }

                val editingName by viewModel.editingName.collectAsState()
                dialog?.let { UploadDialog(dialog = it, editingName = editingName, viewModel = viewModel) }
            }
        }
    }
}

/**
 * The in-person top bar: [BACK] … [top player's material fan / pocket, or the game-over
 * text] … [menu]. No title. BACK and the menu ellipses are always upright (chrome for the
 * device holder). The top bank is rotated 180° in across mode so it reads upright to the
 * player sitting opposite. When the latest line is over AND the latest position is shown,
 * the outcome text replaces the bank; during play (or while browsing) the bank shows.
 */
@Composable
private fun LocalTopBar(
    state: BoardUiState,
    mover: EngineColor,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onPocketTap: (PieceType) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(3f.gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = LightIcons.BACK,
            contentDescription = "Back",
            modifier = Modifier.lightClickable(onClick = onBack),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 0.5f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            val topColor = state.myColor.opposite
            val showOutcome = state.terminal && !state.canStepForward
            when {
                showOutcome -> LightText(
                    text = state.subtitle,
                    variant = LightTextVariant.Fine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    align = TextAlign.Center,
                )
                state.variant == Variant.CRAZYHOUSE -> LocalPocketFan(
                    pocket = state.opponentPocket,
                    color = topColor,
                    selectedDrop = if (mover == topColor) state.selectedDrop else null,
                    onTap = if (mover == topColor) onPocketTap else null,
                    rotated = state.acrossMode,
                )
                // Mirror the bottom bank's left alignment: side-by-side hugs the left; across
                // hugs the right and is turned 180° so it reads left-aligned and upright to the
                // player opposite.
                else -> Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (state.acrossMode) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(modifier = if (state.acrossMode) Modifier.rotate(180f) else Modifier) {
                        // Same Horde collapse as online. The bar is 3 units tall and already
                        // hosts pocket-sized Crazyhouse glyphs, so the badge fits as-is.
                        MaterialFan(
                            captured = state.opponentCaptured,
                            capturedColor = state.materialBottom,
                            advantage = state.opponentAdvantage,
                            cellUnits = MATERIAL_CELL_UNITS,
                            variant = state.variant,
                        )
                    }
                }
            }
        }
        LightIcon(
            icon = LightIcons.ELLIPSES,
            contentDescription = "Menu",
            modifier = Modifier.lightClickable(onClick = onMenu),
        )
    }
}

/**
 * Crazyhouse in-person bottom bar: the BOTTOM player's reserves centered between back /
 * forward arrows (with long-press scrub). Tappable only when it's the bottom player's turn
 * (the top player drops from the top bar's pocket). Always upright.
 */
@Composable
private fun LocalCrazyhouseBottomBar(
    state: BoardUiState,
    mover: EngineColor,
    viewModel: LocalGameViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack, state.moveStepIntervalMs) { viewModel.stepBack() }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LocalPocketFan(
                pocket = state.myPocket,
                color = state.myColor,
                selectedDrop = if (mover == state.myColor) state.selectedDrop else null,
                onTap = if (mover == state.myColor) viewModel::onPocketTap else null,
                rotated = false,
            )
        }
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward, state.moveStepIntervalMs) { viewModel.stepForward() }
    }
}

/**
 * A row of one side's Crazyhouse reserves. [onTap] non-null makes them tappable (only the
 * player on move may drop); [rotated] turns the row 180° for the across-table top player.
 */
@Composable
private fun LocalPocketFan(
    pocket: Map<PieceType, Int>,
    color: EngineColor,
    selectedDrop: PieceType?,
    onTap: ((PieceType) -> Unit)?,
    rotated: Boolean,
) {
    Row(
        modifier = if (rotated) Modifier.rotate(180f) else Modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        POCKET_ORDER.filter { (pocket[it] ?: 0) > 0 }.forEach { type ->
            PocketPiece(
                type = type,
                color = color,
                count = pocket[type] ?: 0,
                cell = MY_POCKET_CELL_UNITS.gridUnitsAsDp(),
                selected = type == selectedDrop,
                onTap = onTap?.let { tap -> { tap(type) } },
            )
        }
    }
}

@Composable
private fun LocalPromotionOverlay(myColor: EngineColor, variant: Variant, viewModel: LocalGameViewModel) {
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
                                    .lightClickable { viewModel.choosePromotion(type) },
                                contentAlignment = Alignment.Center,
                            ) {
                                PieceGlyph(piece = Piece(myColor, type), squareSize = cell)
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
                    onClick = { viewModel.cancelPromotion() },
                    contentDescription = "Cancel promotion",
                ),
            ),
        )
    }
}

@Composable
private fun LocalMenuOverlay(
    across: Boolean,
    canUpload: Boolean,
    canEndGame: Boolean,
    viewModel: LocalGameViewModel,
) {
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
            // Play mode is a select option (label + current value, dimmed), like the
            // new-game rows; tapping it toggles and closes the menu.
            LocalOptionRow(label = "Play mode", value = if (across) "Across" else "Side by side") {
                viewModel.togglePlayMode()
            }
            // Game-ending actions. Hidden once the game is already decided (same gate style
            // as canUpload); each one confirms before taking effect — they're irreversible
            // and the result they set is what gets uploaded to Lichess.
            if (canEndGame) {
                LocalMenuRow("Agree draw") {
                    viewModel.requestEndGame(LocalGameViewModel.EndAction.AGREE_DRAW)
                }
                LocalMenuRow("White resigns") {
                    viewModel.requestEndGame(LocalGameViewModel.EndAction.WHITE_RESIGNS)
                }
                LocalMenuRow("Black resigns") {
                    viewModel.requestEndGame(LocalGameViewModel.EndAction.BLACK_RESIGNS)
                }
            }
            LocalMenuRow("New game") { viewModel.newGame() }
            // Upload needs a logged-in token; hidden entirely when playing logged out.
            if (canUpload) LocalMenuRow("Upload to Lichess") { viewModel.requestUpload() }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = { viewModel.closeMenu() },
                    contentDescription = "Close menu",
                ),
            ),
        )
    }
}

/**
 * The in-person game's confirmation overlay — mirrors the online board's
 * ConfirmationOverlay visual (full-screen [message] + CONFIRM/✕ bottom bar). Used for
 * exiting mid-game (this game has no server copy, so backing out loses all progress) and
 * for the irreversible game-ending actions (see [LocalGameViewModel.EndAction]).
 */
@Composable
private fun LocalConfirmationOverlay(
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
        LightBottomBar(
            items = listOf(
                null,
                LightBarButton.Text(text = "CONFIRM", onClick = onConfirm),
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = onCancel,
                    contentDescription = "Cancel",
                ),
            ),
        )
    }
}

// A select-style menu row: label on the left, current value dimmed on the right (matches
// the new-game OptionRow look). Tapping runs [onClick] (which also closes the menu).
@Composable
private fun LocalOptionRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = LightTextVariant.Subheading, modifier = Modifier.weight(1f))
        LightText(text = value, variant = LightTextVariant.Subheading, lighten = true)
    }
}

@Composable
private fun LocalMenuRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Subheading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
    )
}

/**
 * The player-name form shown before an upload: a "White" and a "Black" [LightTextField],
 * each defaulting to the "anonymous" placeholder (a blank field uploads as "anonymous", so
 * the player just types their name without clearing anything). Tapping a field opens the
 * full-screen editor (see [UploadDialog]). UPLOAD submits; ✕ cancels.
 */
@Composable
private fun NameEntryForm(viewModel: LocalGameViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            center = LightTopBarCenter.Text("Player names"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
            ) {
                LightTextField(
                    label = "White",
                    value = viewModel.whiteNameState.text.toString(),
                    placeholder = "anonymous",
                    onClick = { viewModel.editName(LocalGameViewModel.NameField.WHITE) },
                )
                LightTextField(
                    label = "Black",
                    value = viewModel.blackNameState.text.toString(),
                    placeholder = "anonymous",
                    onClick = { viewModel.editName(LocalGameViewModel.NameField.BLACK) },
                )
            }
        }
        LightBottomBar(
            items = listOf(
                null,
                LightBarButton.Text(text = "UPLOAD", onClick = { viewModel.submitUpload() }),
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = { viewModel.dismissDialog() },
                    contentDescription = "Cancel",
                ),
            ),
        )
    }
}

@Composable
private fun UploadDialog(
    dialog: LocalGameViewModel.Dialog,
    editingName: LocalGameViewModel.NameField?,
    viewModel: LocalGameViewModel,
) {
    when (dialog) {
        LocalGameViewModel.Dialog.NameEntry -> {
            // Editing one of the names opens the full-screen editor; otherwise the form.
            if (editingName != null) {
                val keyboardOptions = rememberKeyboardOptions()
                val isWhite = editingName == LocalGameViewModel.NameField.WHITE
                LightTextInputEditor(
                    title = if (isWhite) "White" else "Black",
                    state = if (isWhite) viewModel.whiteNameState else viewModel.blackNameState,
                    onSubmit = { viewModel.finishEditingName() },
                    onBack = { viewModel.finishEditingName() },
                    keyboardOptionsFlow = keyboardOptions,
                    submitLabel = "DONE",
                    singleLine = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                NameEntryForm(viewModel = viewModel)
            }
        }

        LocalGameViewModel.Dialog.Uploading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = "Uploading to Lichess…", variant = LightTextVariant.Copy)
            }
        }

        is LocalGameViewModel.Dialog.UploadDone -> {
            // Browser is blocked on LightOS, so show the URL as plain text to copy manually.
            LightFullscreenModal(
                message = "Game uploaded to Lichess:\n${dialog.url}",
                onClose = { viewModel.dismissDialog() },
            )
        }

        is LocalGameViewModel.Dialog.UploadError -> {
            LightFullscreenModal(
                message = "Couldn't upload: ${dialog.message}",
                onClose = { viewModel.dismissDialog() },
            )
        }
    }
}
