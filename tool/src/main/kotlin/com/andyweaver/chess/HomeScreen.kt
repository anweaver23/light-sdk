package com.andyweaver.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.andyweaver.chess.board.BoardScreen
import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.lichess.LichessApi
import com.andyweaver.chess.lichess.LichessGame
import com.andyweaver.chess.newgame.NewGameScreen
import com.andyweaver.chess.settings.ChessSettings
import com.andyweaver.chess.settings.SettingsScreen
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Opponent name is one step above the subtitle in the type scale.
private val NAME_VARIANT = LightTextVariant.Subheading
private val SUBTITLE_VARIANT = LightTextVariant.Detail

// Fixed-width column left of the name, always reserved so names never shift.
private const val ASTERISK_COL_UNITS = 1.5f
private const val EDGE_PADDING_UNITS = 1f
// Generous gap below each row so the list doesn't feel crammed.
private const val ROW_GAP_UNITS = 1.75f

class HomeScreenViewModel(private val settings: ChessSettings) : LightViewModel<Unit>() {

    enum class Account(val label: String) {
        ONE("ACCT 1"),
        TWO("ACCT 2"),
    }

    sealed class State {
        object Loading : State()
        data class Loaded(val games: List<LichessGame>) : State()
        data class Error(val message: String) : State()
    }

    private val tokensByAccount = mapOf(
        Account.ONE to BuildConfig.LICHESS_TOKEN_ACCOUNT_1,
        Account.TWO to BuildConfig.LICHESS_TOKEN_ACCOUNT_2,
    )

    private val _account = MutableStateFlow(Account.ONE)
    val account: StateFlow<Account> = _account

    // Token for the currently-selected account — passed to the board screen.
    val currentToken: String
        get() = tokensByAccount.getValue(_account.value)

    // Backed by persisted settings (Settings screen). Defaults true until loaded.
    val showTimeRemaining = settings.showTimeRemaining
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val showLastMove = settings.showLastMove
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Spec: re-fetch on return to this screen rather than maintaining local state.
        refresh()
    }

    // Dev-only: cycle between the two test Lichess accounts. Real app has one
    // logged-in account; this is a testing hook wired to a title tap.
    fun switchAccount() {
        _account.value = if (_account.value == Account.ONE) Account.TWO else Account.ONE
        refresh()
    }

    fun refresh() {
        val token = tokensByAccount.getValue(_account.value)
        _state.value = State.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val api = LichessApi(token)
            _state.value = try {
                State.Loaded(api.getOngoingCorrespondenceGames().sortedForList())
            } catch (e: Exception) {
                State.Error(e.message ?: "Unable to load games")
            } finally {
                api.close()
            }
        }
    }

    // Your-move games first; within each group, least time remaining first.
    private fun List<LichessGame>.sortedForList(): List<LichessGame> =
        sortedWith(
            compareByDescending<LichessGame> { it.isMyTurn }
                .thenBy { it.secondsLeft ?: Int.MAX_VALUE },
        )
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel =
        HomeScreenViewModel(ChessSettings(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val showTime by viewModel.showTimeRemaining.collectAsState()
        val showLastMove by viewModel.showLastMove.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // Centered title, matching LP convention. Tap is a dev-only hook
                // to switch test accounts.
                LightTopBar(
                    center = LightTopBarCenter.Text("Chess", onClick = { viewModel.switchAccount() }),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val currentState = state) {
                        is HomeScreenViewModel.State.Loading -> {
                            LightText(
                                text = "Loading…",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(horizontal = EDGE_PADDING_UNITS.gridUnitsAsDp()),
                            )
                        }

                        is HomeScreenViewModel.State.Error -> {
                            LightText(
                                text = currentState.message,
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(horizontal = EDGE_PADDING_UNITS.gridUnitsAsDp()),
                            )
                        }

                        is HomeScreenViewModel.State.Loaded -> {
                            if (currentState.games.isEmpty()) {
                                LightText(
                                    text = "No correspondence games in progress.",
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier.padding(horizontal = EDGE_PADDING_UNITS.gridUnitsAsDp()),
                                )
                            } else {
                                LightScrollView(modifier = Modifier.fillMaxSize()) {
                                    currentState.games.forEach { game ->
                                        GameRow(
                                            game = game,
                                            showTimeRemaining = showTime,
                                            showLastMove = showLastMove,
                                            onClick = {
                                                navigateTo({ sa ->
                                                    BoardScreen(
                                                        sa,
                                                        game.gameId,
                                                        viewModel.currentToken,
                                                        game.color,
                                                    )
                                                })
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { navigateTo(::SettingsScreen) },
                            contentDescription = "Settings",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            onClick = { navigateTo({ sa -> NewGameScreen(sa, viewModel.currentToken) }) },
                            contentDescription = "New game",
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun GameRow(
    game: LichessGame,
    showTimeRemaining: Boolean,
    showLastMove: Boolean,
    onClick: () -> Unit,
) {
    val subtitle = buildSubtitle(game, showTimeRemaining, showLastMove)
    val asteriskColWidth = ASTERISK_COL_UNITS.gridUnitsAsDp()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(
                start = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                end = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                bottom = ROW_GAP_UNITS.gridUnitsAsDp(),
            ),
    ) {
        // Name line: reserved asterisk column (empty when not your move) + name.
        // Asterisk is centered on the name line so it doesn't ride high.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.width(asteriskColWidth),
                contentAlignment = Alignment.Center,
            ) {
                if (game.isMyTurn) {
                    LightText(text = "*", variant = NAME_VARIANT)
                }
            }
            LightText(
                text = game.opponent.username,
                variant = NAME_VARIANT,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // Subtitle: indented to align under the name; dimmed to match the mockup.
        subtitle?.let {
            LightText(
                text = it,
                variant = SUBTITLE_VARIANT,
                lighten = true,
                modifier = Modifier.padding(start = asteriskColWidth),
            )
        }
    }
}

// Both settings off → no subtitle (row collapses to a single line).
private fun buildSubtitle(
    game: LichessGame,
    showTimeRemaining: Boolean,
    showLastMove: Boolean,
): String? {
    if (!showTimeRemaining && !showLastMove) return null
    val parts = buildList {
        if (!game.isMyTurn) add("their move")
        if (showTimeRemaining) game.secondsLeft?.let { add(formatTimeLabel(it, game.isMyTurn)) }
        if (showLastMove) game.lastMove?.takeIf { it.isNotBlank() }?.let { add(formatLastMove(game.fen, it)) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatTimeLabel(seconds: Int, isMyTurn: Boolean): String {
    val duration = formatDuration(seconds)
    return if (isMyTurn) "$duration left" else duration
}

private fun formatDuration(seconds: Int): String {
    val days = seconds / 86_400
    if (days >= 1) return "$days ${plural(days, "day")}"
    val hours = seconds / 3_600
    if (hours >= 1) return "$hours ${plural(hours, "hour")}"
    val minutes = seconds / 60
    if (minutes >= 1) return "$minutes ${plural(minutes, "minute")}"
    return "$seconds ${plural(seconds, "second")}"
}

private fun plural(n: Int, unit: String): String = if (n == 1) unit else "${unit}s"

// Move-number prefix (from the FEN) + the move in SAN, e.g. "8... Bd6" / "9. O-O".
// SAN is reconstructed by the engine from the post-move FEN + the UCI move; if that
// can't be resolved we fall back to the raw UCI so the row never shows nothing.
private fun formatLastMove(fen: String, move: String): String {
    val prefix = lastMovePrefix(fen) ?: ""
    val san = Chess.sanForLastMove(fen, move) ?: move
    return prefix + san
}

private fun lastMovePrefix(fen: String): String? {
    val parts = fen.trim().split(" ")
    if (parts.size < 6) return null
    val activeColor = parts[1]
    val fullmove = parts[5].toIntOrNull() ?: return null
    // FEN fullmove increments after Black's move; active color is who moves NOW,
    // so the last move was the other side's.
    return if (activeColor == "w") "${fullmove - 1}... " else "$fullmove. "
}
