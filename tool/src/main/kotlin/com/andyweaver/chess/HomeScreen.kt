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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.andyweaver.chess.board.BoardScreen
import com.andyweaver.chess.engine.Variant
// v1: last-move-in-subtitle removed — may re-add
// import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.history.HistoryScreen
import com.andyweaver.chess.lichess.AccountEventType
import com.andyweaver.chess.lichess.LichessActionResult
import com.andyweaver.chess.lichess.LichessApi
import com.andyweaver.chess.lichess.LichessChallenge
import com.andyweaver.chess.lichess.LichessGame
import com.andyweaver.chess.lichess.nameWithRating
import com.andyweaver.chess.newgame.NewGameScreen
import com.andyweaver.chess.ui.NameWithRating
import com.andyweaver.chess.settings.ChessSettings
import com.andyweaver.chess.settings.PendingSeek
import com.andyweaver.chess.settings.SettingsScreen
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightSurfaceScheme
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Opponent name is one step above the subtitle in the type scale.
private val NAME_VARIANT = LightTextVariant.Subheading
private val SUBTITLE_VARIANT = LightTextVariant.Detail

// Fixed-width column left of the name, always reserved so names never shift.
private const val ASTERISK_COL_UNITS = 1.5f
private const val EDGE_PADDING_UNITS = 1f
// Generous gap below each row so the list doesn't feel crammed.
private const val ROW_GAP_UNITS = 1.75f

// Bottom-bar history icon: a custom vector (not a LightIcons glyph) whose path
// under-fills its viewport, so it needs a larger box than the default 2f to match
// the gear/plus glyphs beside it. Purely visual — tweak to taste.
private const val HISTORY_ICON_SIZE_UNITS = 2.5f

// Quiet background refresh cadence while the home screen is foregrounded. The
// event stream catches game start/finish and challenges instantly; this poll is
// only to pick up opponent MOVES in existing games (which the stream never pushes).
// Correspondence is slow, so 20s is plenty and battery-cheap.
private const val MOVE_POLL_INTERVAL_MS = 20_000L

class HomeScreenViewModel(private val settings: ChessSettings) : LightViewModel<Unit>() {

    enum class Account(val label: String) {
        ONE("ACCT 1"),
        TWO("ACCT 2"),
    }

    /** Everything the home list renders: active games plus pending challenges both ways. */
    data class Content(
        val games: List<LichessGame> = emptyList(),
        val incoming: List<LichessChallenge> = emptyList(),
        val outgoing: List<LichessChallenge> = emptyList(),
    ) {
        val isEmpty: Boolean get() = games.isEmpty() && incoming.isEmpty() && outgoing.isEmpty()
    }

    sealed class State {
        object Loading : State()
        data class Loaded(val content: Content) : State()
        data class Error(val message: String) : State()
    }

    private val tokensByAccount = mapOf(
        Account.ONE to BuildConfig.LICHESS_TOKEN_ACCOUNT_1,
        Account.TWO to BuildConfig.LICHESS_TOKEN_ACCOUNT_2,
    )

    private val _account = MutableStateFlow(Account.ONE)
    val account: StateFlow<Account> = _account

    // Token for the currently-selected account — passed to the board/new-game screens.
    val currentToken: String
        get() = tokensByAccount.getValue(_account.value)

    // One long-lived client per account (the event stream needs to stay open); swapped
    // out on account switch, closed on clear.
    private var api = LichessApi(currentToken)

    // Locally-tracked pending seeks for the current account (Lichess can't list them).
    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingSeeks: StateFlow<List<PendingSeek>> = _account
        .flatMapLatest { settings.pendingSeeks(it.name) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    /** One-shot: a game to open immediately (accepting a challenge as White). */
    data class OpenGame(val gameId: String, val color: String)
    private val _openGame = MutableStateFlow<OpenGame?>(null)
    val openGame: StateFlow<OpenGame?> = _openGame

    private var streamJob: Job? = null
    private var pollJob: Job? = null

    // ----- lifecycle: refresh + stream + poll only while visible/foregrounded -----

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh(showLoading = true)
        startStream()
        startPoll()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        stopLive()
    }

    override fun onAppPause() {
        super.onAppPause()
        stopLive()
    }

    override fun onCleared() {
        super.onCleared()
        stopLive()
        api.close()
    }

    // Dev-only: cycle between the two test Lichess accounts. Real app has one
    // logged-in account; this is a testing hook wired to a title tap.
    fun switchAccount() {
        stopLive()
        api.close()
        _account.value = if (_account.value == Account.ONE) Account.TWO else Account.ONE
        api = LichessApi(currentToken)
        _state.value = State.Loading
        refresh(showLoading = true)
        startStream()
        startPoll()
    }

    // Event-driven liveness: games appear/disappear and challenges surface the instant
    // Lichess pushes an event — no polling for those. (Per-move updates are NOT pushed.)
    private fun startStream() {
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            try {
                api.streamEvents().collect { event ->
                    if (event != AccountEventType.UNKNOWN) refresh(showLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Stream dropped; the poll still keeps the list reasonably fresh.
            }
        }
    }

    private fun startPoll() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(MOVE_POLL_INTERVAL_MS)
                refresh(showLoading = false)
            }
        }
    }

    private fun stopLive() {
        streamJob?.cancel(); streamJob = null
        pollJob?.cancel(); pollJob = null
    }

    /**
     * Fetches games + challenges together. [showLoading] blanks to the spinner only when
     * there's nothing to show yet; background refreshes (stream/poll) update in place, and
     * a transient failure keeps the current content rather than flashing an error.
     */
    fun refresh(showLoading: Boolean) {
        if (showLoading && _state.value !is State.Loaded) _state.value = State.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val games = async { api.getOngoingCorrespondenceGames() }
                val challenges = async { api.getChallenges() }
                val c = challenges.await()
                val gamesList = games.await()
                // Clear any pending seek that appears to have matched into a new game.
                settings.reconcileSeeks(_account.value.name, gamesList.map { it.gameId }.toSet())
                _state.value = State.Loaded(
                    Content(
                        games = gamesList.sortedForList(),
                        incoming = c.incoming,
                        outgoing = c.outgoing,
                    ),
                )
            } catch (e: Exception) {
                if (_state.value !is State.Loaded) {
                    _state.value = State.Error(e.message ?: "Unable to load games")
                }
            }
        }
    }

    /**
     * Accepts an incoming challenge. The resulting game's id equals the challenge id.
     * `finalColor` is the challenger's color, so I play the opposite — and if that's
     * White I jump straight into the game to make the first move (via [openGame]).
     */
    fun acceptChallenge(challenge: LichessChallenge) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = api.acceptChallenge(challenge.id)
            refresh(showLoading = false)
            if (result is LichessActionResult.Success) {
                val iAmWhite = challenge.finalColor == "black"
                if (iAmWhite) _openGame.value = OpenGame(challenge.id, "white")
            }
        }
    }

    fun consumeOpenGame() { _openGame.value = null }

    fun declineChallenge(id: String) = challengeAction { api.declineChallenge(id) }
    fun cancelChallenge(id: String) = challengeAction { api.cancelChallenge(id) }

    // Seek rows no longer expose a dismiss (an ✕ wrongly implies it cancels the
    // Lichess seek). Kept for reference; markers auto-clear via reconcileSeeks.
    // /** Removes the local pending-seek marker (does not cancel it on Lichess). */
    // fun dismissSeek(id: String) {
    //     viewModelScope.launch(Dispatchers.IO) { settings.removePendingSeek(_account.value.name, id) }
    // }

    private fun challengeAction(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }
            // Reflect the change immediately (the stream will also fire).
            refresh(showLoading = false)
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
        val pendingSeeks by viewModel.pendingSeeks.collectAsState()
        val openGame by viewModel.openGame.collectAsState()

        // Accepting a challenge as White jumps straight into the game for the first move.
        LaunchedEffect(openGame) {
            val open = openGame ?: return@LaunchedEffect
            viewModel.consumeOpenGame()
            navigateTo({ sa -> BoardScreen(sa, open.gameId, viewModel.currentToken, open.color) })
        }

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
                            val content = currentState.content
                            if (content.isEmpty && pendingSeeks.isEmpty()) {
                                LightText(
                                    text = "No correspondence games in progress.",
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier.padding(horizontal = EDGE_PADDING_UNITS.gridUnitsAsDp()),
                                )
                            } else {
                                LightScrollView(modifier = Modifier.fillMaxSize()) {
                                    // Incoming challenges pinned at the top so they're never missed.
                                    content.incoming.forEach { challenge ->
                                        IncomingChallengeRow(
                                            challenge = challenge,
                                            onAccept = { viewModel.acceptChallenge(challenge) },
                                            onDecline = { viewModel.declineChallenge(challenge.id) },
                                        )
                                    }
                                    // Active games.
                                    content.games.forEach { game ->
                                        GameRow(
                                            game = game,
                                            onClick = {
                                                navigateTo({ sa ->
                                                    BoardScreen(
                                                        sa,
                                                        game.gameId,
                                                        viewModel.currentToken,
                                                        game.color,
                                                        game.fen,
                                                        nameWithRating(game.opponent.username, game.opponent.rating),
                                                        Variant.fromKey(game.variant.key),
                                                    )
                                                })
                                            },
                                        )
                                    }
                                    // Challenges you've sent + seeks awaiting a match, greyed at the bottom.
                                    if (content.outgoing.isNotEmpty() || pendingSeeks.isNotEmpty()) {
                                        SectionLabel("Pending")
                                        content.outgoing.forEach { challenge ->
                                            OutgoingChallengeRow(
                                                challenge = challenge,
                                                onCancel = { viewModel.cancelChallenge(challenge.id) },
                                            )
                                        }
                                        pendingSeeks.forEach { seek ->
                                            SeekRow(seek = seek)
                                        }
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
                        LightBarButton.Icon(
                            // Custom Material "history" glyph; pick the variant that matches
                            // the theme's content color (LightBarButton.Icon doesn't tint).
                            painter = painterResource(
                                if (LightThemeTokens.surfaceScheme == LightSurfaceScheme.Dark) {
                                    R.drawable.ic_history_white
                                } else {
                                    R.drawable.ic_history_black
                                },
                            ),
                            onClick = { navigateTo({ sa -> HistoryScreen(sa, viewModel.currentToken) }) },
                            contentDescription = "Game history",
                            // The Material path only fills ~80% of its 24dp viewport, so at the
                            // default 2f it looks smaller than the LightIcons gear/plus beside it.
                            // Bump the box so the glyph reads at the same size. Tweak to taste.
                            sizeUnits = HISTORY_ICON_SIZE_UNITS,
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            onClick = {
                                navigateTo({ sa ->
                                    NewGameScreen(sa, viewModel.currentToken, viewModel.account.value.name)
                                })
                            },
                            contentDescription = "New game",
                        ),
                    ),
                )
            }
        }
    }
}

// Dim, small section header (used above the greyed "Pending" group).
@Composable
private fun SectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(
            start = EDGE_PADDING_UNITS.gridUnitsAsDp(),
            top = 0.5f.gridUnitsAsDp(),
            bottom = 0.75f.gridUnitsAsDp(),
        ),
    )
}

// An incoming challenge: name + terms, with accept (✓) and decline (✕) controls.
@Composable
private fun IncomingChallengeRow(
    challenge: LichessChallenge,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                end = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                bottom = ROW_GAP_UNITS.gridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            NameWithRating(
                name = challenge.challenger?.displayName ?: "Someone",
                rating = challenge.challenger?.ratingOrNull,
                nameVariant = NAME_VARIANT,
                modifier = Modifier.fillMaxWidth(),
            )
            LightText(
                text = "wants to play · ${challengeTerms(challenge)}",
                variant = SUBTITLE_VARIANT,
                lighten = true,
            )
        }
        LightIcon(
            icon = LightIcons.ACCEPT,
            contentDescription = "Accept challenge",
            modifier = Modifier.lightClickable(onClick = onAccept),
        )
        LightIcon(
            icon = LightIcons.CLOSE,
            contentDescription = "Decline challenge",
            modifier = Modifier
                .padding(start = 1f.gridUnitsAsDp())
                .lightClickable(onClick = onDecline),
        )
    }
}

// A challenge you've sent, awaiting acceptance — greyed, with a cancel (✕).
@Composable
private fun OutgoingChallengeRow(
    challenge: LichessChallenge,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                end = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                bottom = ROW_GAP_UNITS.gridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = challenge.destUser?.let { nameWithRating(it.displayName, it.ratingOrNull) } ?: "Open challenge",
                variant = NAME_VARIANT,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = "waiting · ${challengeTerms(challenge)}",
                variant = SUBTITLE_VARIANT,
                lighten = true,
            )
        }
        LightIcon(
            icon = LightIcons.CLOSE,
            contentDescription = "Cancel challenge",
            modifier = Modifier.lightClickable(onClick = onCancel),
        )
    }
}

// e.g. "2 days/turn · casual". Days omitted if the challenge didn't specify one.
private fun challengeTerms(challenge: LichessChallenge): String {
    val parts = buildList {
        challenge.timeControl?.daysPerTurn?.let { add("$it ${plural(it, "day")}/turn") }
        add(if (challenge.rated) "rated" else "casual")
    }
    return parts.joinToString(" · ")
}

// A locally-tracked seek awaiting a random match — greyed, no controls. There is
// deliberately NO ✕: a correspondence seek can't be canceled via the Lichess API
// (a dismiss here would only drop the local marker), and an ✕ wrongly implies it
// can. The marker auto-clears when the seek matches into a game (reconcileSeeks).
@Composable
private fun SeekRow(seek: PendingSeek) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                end = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                bottom = ROW_GAP_UNITS.gridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = "Random opponent",
                variant = NAME_VARIANT,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = "waiting · ${seekTerms(seek)}",
                variant = SUBTITLE_VARIANT,
                lighten = true,
            )
        }
    }
}

// e.g. "Horde · 2 days/turn · casual · white". Variant shown only when non-standard;
// side omitted when random.
private fun seekTerms(seek: PendingSeek): String {
    val parts = buildList {
        variantLabel(seek.variant)?.let { add(it) }
        add("${seek.days} ${plural(seek.days, "day")}/turn")
        add(if (seek.rated) "rated" else "casual")
        if (seek.side != "random") add(seek.side)
    }
    return parts.joinToString(" · ")
}

// Human label for a non-standard variant key, or null for standard (so it's omitted).
private fun variantLabel(key: String): String? = when (key) {
    "crazyhouse" -> "Crazyhouse"
    "chess960" -> "Chess960"
    "kingOfTheHill" -> "King of the Hill"
    "threeCheck" -> "Three-check"
    "antichess" -> "Antichess"
    "atomic" -> "Atomic"
    "horde" -> "Horde"
    "racingKings" -> "Racing Kings"
    else -> null
}

@Composable
private fun GameRow(
    game: LichessGame,
    onClick: () -> Unit,
) {
    val subtitle = buildSubtitle(game)
    val asteriskColWidth = ASTERISK_COL_UNITS.gridUnitsAsDp()
    // Reserved asterisk column + a name/subtitle block. The row is center-aligned
    // vertically so the "your move" asterisk sits centered across BOTH lines
    // (name + subtitle) rather than riding up on the name line.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(
                start = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                end = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                bottom = ROW_GAP_UNITS.gridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(asteriskColWidth),
            contentAlignment = Alignment.Center,
        ) {
            if (game.isMyTurn) {
                LightText(text = "*", variant = NAME_VARIANT)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            NameWithRating(
                name = game.opponent.username,
                rating = game.opponent.rating,
                nameVariant = NAME_VARIANT,
                modifier = Modifier.fillMaxWidth(),
            )
            // Subtitle aligns under the name automatically (same column); dimmed.
            LightText(
                text = subtitle,
                variant = SUBTITLE_VARIANT,
                lighten = true,
            )
        }
    }
}

// Subtitle: which side you play (white/black), "their move" when waiting, and the
// time remaining (always shown now — the toggles were removed for v1).
private fun buildSubtitle(game: LichessGame): String {
    val parts = buildList {
        add(game.color)
        if (!game.isMyTurn) add("their move")
        game.secondsLeft?.let { add(formatTimeLabel(it, game.isMyTurn)) }
    }
    return parts.joinToString(" · ")
}

private fun formatTimeLabel(seconds: Int, isMyTurn: Boolean): String {
    val duration = formatDuration(seconds)
    return if (isMyTurn) "$duration left" else duration
}

// 48h is the cutoff: 2+ days show "N days"; under that, switch to hours (rounded)
// so a ~2-day correspondence turn reads "48 hours" rather than a misleading "1 day".
private fun formatDuration(seconds: Int): String = when {
    seconds >= 2 * 86_400 -> {
        val days = (seconds + 43_200) / 86_400
        "$days ${plural(days, "day")}"
    }
    seconds >= 3_600 -> {
        val hours = (seconds + 1_800) / 3_600
        "$hours ${plural(hours, "hour")}"
    }
    seconds >= 60 -> {
        val minutes = (seconds + 30) / 60
        "$minutes ${plural(minutes, "minute")}"
    }
    else -> "$seconds ${plural(seconds, "second")}"
}

private fun plural(n: Int, unit: String): String = if (n == 1) unit else "${unit}s"

// v1: last-move-in-subtitle removed — may re-add. Kept for reference.
// private fun formatLastMove(fen: String, move: String): String {
//     val prefix = lastMovePrefix(fen) ?: ""
//     val san = Chess.sanForLastMove(fen, move) ?: move
//     return prefix + san
// }
//
// private fun lastMovePrefix(fen: String): String? {
//     val parts = fen.trim().split(" ")
//     if (parts.size < 6) return null
//     val activeColor = parts[1]
//     val fullmove = parts[5].toIntOrNull() ?: return null
//     return if (activeColor == "w") "${fullmove - 1}... " else "$fullmove. "
// }
