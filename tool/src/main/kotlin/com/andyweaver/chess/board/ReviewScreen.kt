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
import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.engine.Color as EngineColor
import com.andyweaver.chess.engine.GameStatus
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

    fun stepBack() {
        if (viewIndex > 0) { viewIndex--; _uiState.value = buildState() }
    }

    fun stepForward() {
        val last = replay?.positions?.lastIndex ?: return
        if (viewIndex < last) { viewIndex++; _uiState.value = buildState() }
    }

    fun stepToStart() {
        if (viewIndex != 0) { viewIndex = 0; _uiState.value = buildState() }
    }

    fun stepToEnd() {
        val last = replay?.positions?.lastIndex ?: return
        if (viewIndex != last) { viewIndex = last; _uiState.value = buildState() }
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
            myCaptured = material.myCaptured,
            opponentCaptured = material.opponentCaptured,
            myAdvantage = material.myAdvantage,
            opponentAdvantage = material.opponentAdvantage,
            myClockLabel = clockLabelFor(myColor == EngineColor.WHITE, idx),
            opponentClockLabel = clockLabelFor(myColor != EngineColor.WHITE, idx),
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

                if (!viewModel.parseFailed) {
                    // Opponent's captures + their +N at the top board edge (left), with the
                    // opponent's clock overlaid at the right (non-correspondence games).
                    Box(modifier = Modifier.fillMaxWidth()) {
                        MaterialRow(
                            captured = state.opponentCaptured,
                            capturedColor = state.myColor,
                            advantage = state.opponentAdvantage,
                        )
                        state.opponentClockLabel?.let { clock ->
                            LightText(
                                text = clock,
                                variant = LightTextVariant.Detail,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 0.5f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    if (viewModel.parseFailed) {
                        LightText(
                            text = "This game can't be reviewed here.",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                        )
                    } else {
                        ChessBoard(state = state, onSquareTap = {})
                    }
                }

                if (!viewModel.parseFailed) {
                    // My captures + my +N at the bottom board edge (left), with the move's
                    // clock time overlaid at the right (non-correspondence games only).
                    Box(modifier = Modifier.fillMaxWidth()) {
                        MaterialRow(
                            captured = state.myCaptured,
                            capturedColor = state.myColor.opposite,
                            advantage = state.myAdvantage,
                        )
                        state.myClockLabel?.let { clock ->
                            LightText(
                                text = clock,
                                variant = LightTextVariant.Detail,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }

                if (!viewModel.parseFailed) {
                    BrowseBar(
                        canStepBack = state.canStepBack,
                        canStepForward = state.canStepForward,
                        onBack = { viewModel.stepBack() },
                        onForward = { viewModel.stepForward() },
                        onSkipStart = { viewModel.stepToStart() },
                        onSkipEnd = { viewModel.stepToEnd() },
                    )
                }
            }
        }
    }
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
