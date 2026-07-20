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
 * Moves apply IMMEDIATELY (no confirm step — both players are present). When the game
 * ends, the user is offered a one-tap upload of the finished game to Lichess (see the
 * upload dialog). NOTE: a finished local game is NOT persisted — once this screen is left,
 * it can no longer be uploaded (out of scope for v1; would require persisting local games).
 */
class LocalGameViewModel(
    private val api: LichessApi,
    private val settings: ChessSettings,
    variantKey: String,
    initialAcross: Boolean,
    // False when playing logged out (no token) — the game can't be uploaded, so the
    // auto-prompt is suppressed and the menu row hidden.
    private val canUpload: Boolean,
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

    private var outcome: GameOutcome = GameOutcome.Ongoing
    // Ensures the auto-upload prompt fires only once per finished game (not on every
    // subsequent recompute), while the menu row stays available for the rest of the session.
    private var autoPromptShown = false

    // One-shot slide for the next state (see ReviewViewModel — same one-shot discipline:
    // set before buildState(), cleared after, so a later plain buildState() doesn't replay it).
    private var pendingAnim: AnimatedMove? = null
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

    fun onSquareTap(square: Int) {
        if (pendingPromotion != null) return
        val pos = viewed()
        val mover = pos.sideToMove

        // Crazyhouse: a pocket piece is held — this tap chooses where to drop it.
        val drop = selectedDrop
        if (drop != null) {
            if (square in dropTargets) commitDrop(drop, square) else clearSelectionAndRender()
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
            square in legalDests -> makeMove(pos, selected, square)
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

    private fun commitDrop(type: PieceType, square: Int) {
        clearSelection()
        applyMove(Move(from = square, to = square, drop = type))
    }

    private fun makeMove(pos: Position, from: Int, to: Int) {
        val isPromotion = pos.pieceAt(from)?.type == PieceType.PAWN &&
            (Square.rank(to) == 0 || Square.rank(to) == 7)
        if (isPromotion) {
            pendingPromotion = from to to
            clearSelection()
            render()
            return
        }
        commitMove(pos, from, to, promotion = null)
    }

    fun choosePromotion(type: PieceType) {
        val (from, to) = pendingPromotion ?: return
        pendingPromotion = null
        // viewIndex can't change while a promotion is pending (browse taps are guarded),
        // so the viewed position is still the one the pawn moved from.
        commitMove(viewed(), from, to, promotion = type)
    }

    fun cancelPromotion() {
        pendingPromotion = null
        clearSelection()
        render()
    }

    private fun commitMove(pos: Position, from: Int, to: Int, promotion: PieceType?) {
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
        applyMove(move)
    }

    // Apply a legal move from the VIEWED position, rebuild the replay, and animate the
    // arrival like the live board. A move made from a non-latest position FORKS the line:
    // the future (everything after the viewed point) is dropped before appending.
    private fun applyMove(move: Move) {
        val oldIndex = viewIndex
        // Fork: truncate the move list to the viewed point before appending. (No-op when
        // already at the latest position, where viewIndex == moves.size.)
        if (viewIndex < moves.size) moves.subList(viewIndex, moves.size).clear()
        moves.add(move.toUci())
        replay = Chess.replay(moves, startFen, variant)
        viewIndex = replay.positions.lastIndex
        // Immediate forward slide (no arrival delay) for the move just played.
        pendingAnim = buildStepAnim(oldIndex, viewIndex)
        outcome = Chess.outcome(replay)
        // Terminal display follows the LATEST line. If the (possibly new) line is no longer
        // over, re-arm the prompt so a fresh ending re-prompts; fire it once per new ending.
        if (!isOver()) {
            autoPromptShown = false
        } else if (canUpload && !autoPromptShown) {
            autoPromptShown = true
            _dialog.value = Dialog.NameEntry
        }
        clearSelection()
        _uiState.value = buildState()
        pendingAnim = null
    }

    // ----- history browsing -----

    private fun buildStepAnim(oldIndex: Int, newIndex: Int): AnimatedMove? {
        val delta = newIndex - oldIndex
        if (delta != 1 && delta != -1) return null
        val step = replay.steps.getOrNull(minOf(oldIndex, newIndex)) ?: return null
        if (step.move.isDrop) return null
        // Captures: forward slides the capturer in over the pre-move board (captured piece
        // stays visible until it lands); Atomic backward snaps; normal backward reverse-slides.
        if (isCaptureMove(step.before, step.move)) {
            if (delta == 1) {
                animCounter += 1
                return captureSlideAnim(step.before, step.after, step.move, animCounter)
            }
            if (step.before.variant == Variant.ATOMIC) return null
        }
        val slides = stepSlides(step, replay.positions[newIndex].board, forward = delta == 1)
        if (slides.isEmpty()) return null
        animCounter += 1
        return AnimatedMove(slides = slides, id = animCounter)
    }

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
        autoPromptShown = false
        menuOpen = false
        pendingAnim = null
        clearSelection()
        _dialog.value = null
        _editingName.value = null
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
            val tags = buildMap {
                put("Event", "Casual Game")
                put("White", white)
                put("Black", black)
                if (date != null) put("Date", date)
            }
            val pgn = Chess.toPgn(replay, tags)
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
        menuOpen -> { menuOpen = false; render(); true }
        pendingPromotion != null -> { cancelPromotion(); true }
        else -> false
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
        val flipped = if (across) false else latest.sideToMove == EngineColor.BLACK
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

        // Material is computed RELATIVE TO the bottom side (== [BoardUiState.myColor]): the
        // bars derive their capturedColor from myColor, so the bottom bank shows what the
        // bottom player has captured and the top bank (rendered in the top bar) the top's.
        val material = computeMaterial(referenceStart(), pos, variant, bottomColor)

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
            animatingMove = pendingAnim,
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
            OutcomeReason.SEVENTY_FIVE_MOVE_RULE -> "draw · 75-move rule"
            OutcomeReason.KING_IN_CENTER -> "$wins · king in the center"
            OutcomeReason.THREE_CHECKS -> "$wins · three checks"
            OutcomeReason.RACING_KINGS_FINISH -> if (winner == null) "draw · kings home" else "$wins · king home"
            OutcomeReason.ATOMIC_KING_EXPLODED -> "$wins · king explosion"
            OutcomeReason.ANTICHESS_NO_PIECES -> "$wins · no pieces left"
            OutcomeReason.HORDE_DESTROYED -> "$wins · horde destroyed"
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
            canUpload = token.isNotBlank(),
        )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val dialog by viewModel.dialog.collectAsState()

        LightTheme(colors = themeColors) {
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
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 0.5f.gridUnitsAsDp()),
                            contentAlignment = Alignment.Center,
                        ) {
                            ChessBoard(state = state, onSquareTap = viewModel::onSquareTap)
                        }
                        LocalCrazyhouseBottomBar(state = state, mover = mover, viewModel = viewModel)
                    } else {
                        // reservedUnits = 0f and no topBank: the top player's material now
                        // lives in the top bar, so the board takes the full remaining height.
                        CenteredBoard(reservedUnits = 0f) {
                            ChessBoard(state = state, onSquareTap = viewModel::onSquareTap)
                        }
                        MaterialBottomBar(
                            state = state,
                            onBack = { viewModel.stepBack() },
                            onForward = { viewModel.stepForward() },
                            onSeek = { viewModel.seekToFraction(it) },
                        )
                    }
                }

                if (state.promotionActive) {
                    LocalPromotionOverlay(myColor = mover, viewModel = viewModel)
                }

                if (state.menuOpen) {
                    LocalMenuOverlay(
                        across = state.acrossMode,
                        canUpload = token.isNotBlank(),
                        viewModel = viewModel,
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
                        MaterialFan(
                            captured = state.opponentCaptured,
                            capturedColor = state.myColor,
                            advantage = state.opponentAdvantage,
                            cellUnits = MATERIAL_CELL_UNITS,
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
            .moveScrubX(currentFraction = state.viewFraction, onSeek = { viewModel.seekToFraction(it) })
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack) { viewModel.stepBack() }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LocalPocketFan(
                pocket = state.myPocket,
                color = state.myColor,
                selectedDrop = if (mover == state.myColor) state.selectedDrop else null,
                onTap = if (mover == state.myColor) viewModel::onPocketTap else null,
                rotated = false,
            )
        }
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward) { viewModel.stepForward() }
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
private fun LocalPromotionOverlay(myColor: EngineColor, viewModel: LocalGameViewModel) {
    val choices = listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
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
            Row(horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp())) {
                val cell = 6f.gridUnitsAsDp()
                choices.forEach { type ->
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
private fun LocalMenuOverlay(across: Boolean, canUpload: Boolean, viewModel: LocalGameViewModel) {
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
