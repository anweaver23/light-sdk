package com.andyweaver.chess.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.engine.Color as EngineColor
import com.andyweaver.chess.engine.GameStatus
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Replay
import com.andyweaver.chess.engine.SanReplay
import com.andyweaver.chess.engine.Variant
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

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

    // One-shot slide to play into the next state (see BoardViewModel.buildStepAnim).
    // Declared (and populated for the opening state, below) BEFORE `_uiState` so the
    // very first `buildState()` call can see an animation for the game's last move —
    // property initializers run top-to-bottom, so if this stayed below `_uiState` (as
    // it did before) it would still be null when `buildState()` first ran.
    private var pendingAnim: AnimatedMove? = null
    private var animCounter = 0L

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

    // Push a new state built with [pendingAnim] set for the [old]→[viewIndex] step, then
    // clear it so it's a one-shot.
    private fun pushState(old: Int) {
        pendingAnim = buildStepAnim(old, viewIndex)
        _uiState.value = buildState()
        pendingAnim = null
    }

    fun stepBack() {
        if (viewIndex > 0) { val old = viewIndex; viewIndex--; pushState(old) }
    }

    fun stepForward() {
        val last = replay?.positions?.lastIndex ?: return
        if (viewIndex < last) { val old = viewIndex; viewIndex++; pushState(old) }
    }

    fun stepToStart() {
        if (viewIndex != 0) { viewIndex = 0; _uiState.value = buildState() }
    }

    fun stepToEnd() {
        val last = replay?.positions?.lastIndex ?: return
        if (viewIndex != last) { viewIndex = last; _uiState.value = buildState() }
    }

    /** Scrub to a position by fraction of the whole game (0 = start, 1 = final). */
    fun seekToFraction(fraction: Float) {
        val last = replay?.positions?.lastIndex ?: return
        if (last <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)
        if (target != viewIndex) { val old = viewIndex; viewIndex = target; pushState(old) }
    }

    private fun buildState(): BoardUiState {
        val r = replay ?: return BoardUiState(myColor = myColor, flipped = myColor == EngineColor.BLACK)
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
            animatingMove = pendingAnim,
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.TwoLineDetail(line1 = title, line2 = resultLine),
                )

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
                        TopPocketBar(
                            pocket = state.opponentPocket,
                            color = state.myColor.opposite,
                            verticalPadUnits = 0.15f,
                        )
                        CenteredBoard(
                            reservedUnits = 0f,
                            topRightLabel = state.opponentClockLabel,
                            bottomRightLabel = state.myClockLabel,
                        ) {
                            ChessBoard(state = state, onSquareTap = {})
                        }
                        CrazyhouseReviewBottomBar(
                            state = state,
                            onBack = { viewModel.stepBack() },
                            onForward = { viewModel.stepForward() },
                            onSkipStart = { viewModel.stepToStart() },
                            onSkipEnd = { viewModel.stepToEnd() },
                            onSeek = { viewModel.seekToFraction(it) },
                        )
                    }

                    else -> {
                        // Same layout as the live standard board: opponent's captures above
                        // the board (aligned to its left edge), mine in the bottom bar with
                        // all four browse arrows. Both clocks sit rotated at the board's
                        // right edge (opponent top, me bottom).
                        CenteredBoard(
                            reservedUnits = 2f,
                            topBank = { inset ->
                                ReviewMaterialBank(
                                    captured = state.opponentCaptured,
                                    capturedColor = state.materialBottom,
                                    advantage = state.opponentAdvantage,
                                    inset = inset,
                                )
                            },
                            topRightLabel = state.opponentClockLabel,
                            bottomRightLabel = state.myClockLabel,
                        ) {
                            ChessBoard(state = state, onSquareTap = {})
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
        }
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
) {
    MaterialRow(
        captured = captured,
        capturedColor = capturedColor,
        advantage = advantage,
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
