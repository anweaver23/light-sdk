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
 * renderer ([ChessBoard]) and browse controls ([BrowseBar]); no live stream and no
 * move-making. Starts at the initial position so the user steps forward through the
 * game. Unsupported variants (SAN that the standard engine can't parse) surface a
 * short message instead of a board.
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
    // Horde, whose start isn't standard).
    private val replay: Replay? = runCatching {
        Chess.replaySan(movesSan, initialFen ?: variant.startFen, variant)
    }.getOrNull()
    val parseFailed: Boolean get() = replay == null

    // Open on the game's final position; the user steps/skips backward from there.
    private var viewIndex = replay?.positions?.lastIndex ?: 0

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<BoardUiState> = _uiState.asStateFlow()

    // One-shot slide to play into the next state (see BoardViewModel.buildStepAnim).
    private var pendingAnim: AnimatedMove? = null
    private var animCounter = 0L

    private fun buildStepAnim(oldIndex: Int, newIndex: Int): AnimatedMove? {
        val r = replay ?: return null
        val delta = newIndex - oldIndex
        if (delta != 1 && delta != -1) return null
        val step = r.steps.getOrNull(minOf(oldIndex, newIndex)) ?: return null
        if (step.move.isDrop) return null
        val slides = stepSlides(step, r.positions[newIndex].board, forward = delta == 1)
        if (slides.isEmpty()) return null
        animCounter += 1
        return AnimatedMove(slides = slides, id = animCounter)
    }

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
        val inCheck = status is GameStatus.Check || status is GameStatus.Checkmate
        val checkedKing = if (inCheck) pos.kingSquare(pos.sideToMove).takeIf { it >= 0 } else null

        // Material at the position under review (updates as the user steps/scrolls).
        val material = computeMaterial(positions.first(), pos, variant, myColor)

        return BoardUiState(
            board = pos.board,
            myColor = myColor,
            flipped = myColor == EngineColor.BLACK,
            lastMoveFrom = lastFrom,
            lastMoveTo = lastTo,
            checkedKingSquare = checkedKing,
            canStepBack = idx > 0,
            canStepForward = idx < positions.lastIndex,
            variant = variant,
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
                        ReadOnlyPocketBar(
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
                                    capturedColor = state.myColor,
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
