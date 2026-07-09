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
) : LightViewModel<Unit>() {

    private val replay: Replay? = runCatching { Chess.replaySan(movesSan, initialFen) }.getOrNull()
    val parseFailed: Boolean get() = replay == null

    private var viewIndex = 0

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<BoardUiState> = _uiState.asStateFlow()

    fun stepBack() {
        if (viewIndex > 0) { viewIndex--; _uiState.value = buildState() }
    }

    fun stepForward() {
        val last = replay?.positions?.lastIndex ?: return
        if (viewIndex < last) { viewIndex++; _uiState.value = buildState() }
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

        return BoardUiState(
            board = pos.board,
            myColor = myColor,
            flipped = myColor == EngineColor.BLACK,
            lastMoveFrom = lastFrom,
            lastMoveTo = lastTo,
            checkedKingSquare = checkedKing,
            canStepBack = idx > 0,
            canStepForward = idx < positions.lastIndex,
        )
    }
}

class ReviewScreen(
    sealedActivity: SealedLightActivity,
    private val movesSan: String,
    private val initialFen: String?,
    private val myColorName: String,
    private val title: String,
    private val result: String,
) : LightScreen<Unit, ReviewViewModel>(sealedActivity) {

    override val viewModelClass: Class<ReviewViewModel>
        get() = ReviewViewModel::class.java

    override fun createViewModel(): ReviewViewModel {
        val color = if (myColorName.equals("black", ignoreCase = true)) EngineColor.BLACK else EngineColor.WHITE
        return ReviewViewModel(movesSan, initialFen, color)
    }

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
                    center = LightTopBarCenter.TwoLineDetail(line1 = title, line2 = result),
                )

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
                    BrowseBar(
                        canStepBack = state.canStepBack,
                        canStepForward = state.canStepForward,
                        onBack = { viewModel.stepBack() },
                        onForward = { viewModel.stepForward() },
                    )
                }
            }
        }
    }
}
