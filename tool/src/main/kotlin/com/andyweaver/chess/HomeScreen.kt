package com.andyweaver.chess

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import com.andyweaver.chess.newgame.EDGE_UNITS
import com.andyweaver.chess.newgame.NewGameScreen
import com.andyweaver.chess.newgame.ROW_VERTICAL_UNITS
import com.andyweaver.chess.ui.NameWithRating
import com.andyweaver.chess.settings.ChessSettings
import com.andyweaver.chess.settings.PendingSeek
import com.andyweaver.chess.settings.Session
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
import kotlinx.coroutines.supervisorScope

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

// Duration of one full rotation of the refresh icon while a manual refresh is in flight.
private const val REFRESH_SPIN_MS = 800

class HomeScreenViewModel(private val settings: ChessSettings) : LightViewModel<Unit>() {

    /** Everything the home list renders: active games plus pending challenges both ways. */
    data class Content(
        val games: List<LichessGame> = emptyList(),
        val incoming: List<LichessChallenge> = emptyList(),
        val outgoing: List<LichessChallenge> = emptyList(),
    ) {
        val isEmpty: Boolean get() = games.isEmpty() && incoming.isEmpty() && outgoing.isEmpty()
    }

    sealed class State {
        object NeedsLogin : State()
        object Loading : State()
        data class Loaded(val content: Content) : State()
        data class Error(val message: String) : State()
    }

    // The logged-in session drives everything. Built from the persisted token; when it
    // changes, the client is rebuilt and the list reloaded. Null → the login gate.
    private var session: Session? = null
    val token: String? get() = session?.token
    val username: String? get() = session?.username

    // One long-lived client for the current session (the event stream stays open);
    // rebuilt on login, closed on logout/clear.
    private var api: LichessApi? = null

    // Whether the screen is currently visible/foregrounded (start live only when both a
    // session exists and the screen is shown).
    private var shown = false

    // Login-pane state (shown when there's no session).
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError

    // The token field's state is held here, NOT remembered inside LoginPane. The LP keyboard's
    // backing ViewModel is scoped to this (root) screen's ViewModelStore and, once created, is
    // never re-created — so it stays bound to whatever TextFieldState it first saw. If LoginPane
    // recreated the state on each (re)composition (the ENTER TOKEN toggle, or a logout→login
    // cycle), keystrokes would edit a detached state and nothing would appear. Hoisting it here
    // gives the state the keyboard VM's exact lifetime, so the binding is always correct.
    val loginTokenState = TextFieldState("")
    private val _loggingIn = MutableStateFlow(false)
    val loggingIn: StateFlow<Boolean> = _loggingIn

    // Locally-tracked pending seeks for the logged-in user (Lichess can't list them).
    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingSeeks: StateFlow<List<PendingSeek>> = settings.session
        .flatMapLatest { s ->
            if (s == null) MutableStateFlow<List<PendingSeek>>(emptyList()) else settings.pendingSeeks(s.username)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    init {
        // React to login/logout: (re)build the client for a new session, or gate to login.
        viewModelScope.launch {
            settings.session.collect { s ->
                val tokenChanged = s?.token != session?.token
                session = s
                if (s == null) {
                    stopLive()
                    api?.close()
                    api = null
                    loginTokenState.clearText()
                    _state.value = State.NeedsLogin
                } else if (tokenChanged) {
                    stopLive()
                    api?.close()
                    api = LichessApi(s.token)
                    _state.value = State.Loading
                    if (shown) startLive()
                }
            }
        }
    }

    /** One-shot: a game to open immediately (accepting a challenge as White). */
    data class OpenGame(val gameId: String, val color: String)
    private val _openGame = MutableStateFlow<OpenGame?>(null)
    val openGame: StateFlow<OpenGame?> = _openGame

    private var streamJob: Job? = null
    private var pollJob: Job? = null

    // ----- lifecycle: refresh + stream + poll only while visible/foregrounded -----

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        shown = true
        if (api != null) startLive()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        shown = false
        stopLive()
    }

    override fun onAppPause() {
        super.onAppPause()
        shown = false
        stopLive()
    }

    override fun onCleared() {
        super.onCleared()
        stopLive()
        api?.close()
    }

    /** Refresh + open the event stream + start the poll (requires a logged-in client). */
    private fun startLive() {
        refresh(showLoading = true)
        startStream()
        startPoll()
    }

    // ----- login / logout -----

    /**
     * Validate a pasted Lichess personal-access token via `GET /api/account`; on success
     * persist the session (which drives the reload), else surface an error. Trims input.
     */
    fun attemptLogin(rawToken: String) {
        val token = rawToken.trim()
        if (token.isEmpty()) {
            _loginError.value = "Enter your Lichess token."
            return
        }
        if (_loggingIn.value) return
        _loggingIn.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val probe = LichessApi(token)
            val name = try {
                probe.getAccountUsername().takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            } finally {
                probe.close()
            }
            if (name == null) {
                _loginError.value = "That token didn't work. Check it (and its scopes) and try again."
            } else {
                settings.saveSession(token, name)
            }
            _loggingIn.value = false
        }
    }

    fun dismissLoginError() { _loginError.value = null }

    // Event-driven liveness: games appear/disappear and challenges surface the instant
    // Lichess pushes an event — no polling for those. (Per-move updates are NOT pushed.)
    private fun startStream() {
        if (streamJob?.isActive == true) return
        val api = api ?: return
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

    // Whether a MANUALLY-triggered refresh (top-bar button) is in flight — drives a
    // spin on the refresh icon for exactly as long as the fetch actually takes.
    // Background refreshes (stream/poll) don't touch this; only an explicit tap should
    // visibly announce itself.
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /**
     * Manual refresh (top-bar button): fetch now, and restart the poll's delay from
     * this moment — otherwise a tap right before the poll's next tick would be followed
     * by a near-duplicate background refresh a few seconds later.
     */
    fun manualRefresh() {
        val client = api ?: return
        val user = session?.username ?: return
        if (_refreshing.value) return
        pollJob?.cancel()
        pollJob = null
        viewModelScope.launch(Dispatchers.IO) {
            _refreshing.value = true
            try {
                fetchAndApply(client, user, showLoading = false)
            } finally {
                _refreshing.value = false
            }
        }
        if (shown) startPoll()
    }

    /**
     * Fetches games + challenges together. [showLoading] blanks to the spinner only when
     * there's nothing to show yet; background refreshes (stream/poll) update in place, and
     * a transient failure keeps the current content rather than flashing an error.
     */
    fun refresh(showLoading: Boolean) {
        val client = api ?: return
        val user = session?.username ?: return
        if (showLoading && _state.value !is State.Loaded) _state.value = State.Loading
        viewModelScope.launch(Dispatchers.IO) { fetchAndApply(client, user, showLoading) }
    }

    private suspend fun fetchAndApply(client: LichessApi, user: String, showLoading: Boolean) {
        try {
            // supervisorScope: a failure in EITHER parallel request is delivered ONLY to its
            // own await() (caught below), not propagated up the Job tree. With a plain
            // async/coroutineScope, a child ConnectException reaches the parent coroutine
            // regardless of the try/catch around await() and hits the uncaught-exception
            // handler — crashing the app the moment a startup fetch fails (e.g. no/flaky
            // network). A network failure must degrade to the error state, never a crash.
            val (c, gamesList) = supervisorScope {
                val games = async { client.getOngoingCorrespondenceGames() }
                val challenges = async { client.getChallenges() }
                challenges.await() to games.await()
            }
            // Bail if we logged out (or the client was swapped) while this request was in
            // flight: otherwise a late response clobbers the NeedsLogin gate with stale
            // games, leaving the user stuck on a dead snapshot they can't act on.
            if (api !== client) return
            // Clear any pending seek that appears to have matched into a new game
            // (and revive one whose match turns out to have been an early abort —
            // see ChessSettings.reconcileSeeks's doc for the full heuristic).
            settings.reconcileSeeks(user, gamesList.associate { it.gameId to it.plyCount() })
            _state.value = State.Loaded(
                Content(
                    games = gamesList.sortedForList(),
                    incoming = c.incoming,
                    outgoing = c.outgoing,
                ),
            )
        } catch (e: Exception) {
            if (api === client && _state.value !is State.Loaded) {
                _state.value = State.Error(e.message ?: "Unable to load games")
            }
        }
    }

    /**
     * Accepts an incoming challenge. The resulting game's id equals the challenge id.
     * `finalColor` is the challenger's color, so I play the opposite — and if that's
     * White I jump straight into the game to make the first move (via [openGame]).
     */
    fun acceptChallenge(challenge: LichessChallenge) {
        val api = api ?: return
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

    fun declineChallenge(id: String) = challengeAction { it.declineChallenge(id) }
    fun cancelChallenge(id: String) = challengeAction { it.cancelChallenge(id) }

    // Seek rows no longer expose a dismiss (an ✕ wrongly implies it cancels the
    // Lichess seek). Kept for reference; markers auto-clear via reconcileSeeks.
    // /** Removes the local pending-seek marker (does not cancel it on Lichess). */
    // fun dismissSeek(id: String) {
    //     viewModelScope.launch(Dispatchers.IO) { settings.removePendingSeek(username, id) }
    // }

    private fun challengeAction(block: suspend (LichessApi) -> Unit) {
        val api = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block(api) }
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
        val loginError by viewModel.loginError.collectAsState()

        // The pending challenge/seek whose detail popup is open, if any.
        var detail by remember { mutableStateOf<PendingDetail?>(null) }

        // Accepting a challenge as White jumps straight into the game for the first move.
        LaunchedEffect(openGame) {
            val open = openGame ?: return@LaunchedEffect
            viewModel.consumeOpenGame()
            navigateTo({ sa -> BoardScreen(sa, open.gameId, viewModel.token.orEmpty(), open.color) })
        }

        LightTheme(colors = themeColors) {
          Box(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
            if (state is HomeScreenViewModel.State.NeedsLogin) {
                // No stored session — gate to the login screen.
                LoginPane(
                    tokenState = viewModel.loginTokenState,
                    loginError = loginError,
                    onSubmit = { viewModel.attemptLogin(it) },
                    onDismissError = { viewModel.dismissLoginError() },
                    // Play without an account: open the new-game screen straight into in-person
                    // options (no token — the online path is hidden there while logged out).
                    onStartInPerson = { navigateTo({ sa -> NewGameScreen(sa, "", "", startInPerson = true) }) },
                )
            } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // Centered title, matching LP convention; manual refresh on the right
                // (the event stream + poll already keep the list fresh automatically,
                // but a tap-to-refresh gives an immediate, explicit way to force it).
                // Hand-rolled instead of LightTopBar's rightButton slot: that slot renders
                // via the SDK's own internal button view, which takes no custom Modifier,
                // so it can't carry a spin. This overlay reproduces its exact position
                // (TopEnd, matching LightTopBar's own height/horizontal-padding constants).
                Box(modifier = Modifier.fillMaxWidth()) {
                    LightTopBar(
                        center = LightTopBarCenter.Text("Chess"),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )
                    val refreshing by viewModel.refreshing.collectAsState()
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .height(3f.gridUnitsAsDp())
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        if (refreshing) {
                            val transition = rememberInfiniteTransition(label = "refresh-spin")
                            val angle by transition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(REFRESH_SPIN_MS, easing = LinearEasing),
                                ),
                                label = "angle",
                            )
                            LightIcon(
                                icon = LightIcons.REFRESH,
                                contentDescription = "Refreshing",
                                modifier = Modifier
                                    .rotate(angle)
                                    .lightClickable(onClick = { viewModel.manualRefresh() }),
                            )
                        } else {
                            LightIcon(
                                icon = LightIcons.REFRESH,
                                contentDescription = "Refresh",
                                modifier = Modifier.lightClickable(onClick = { viewModel.manualRefresh() }),
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val currentState = state) {
                        // Handled by the login gate above; nothing to draw here.
                        is HomeScreenViewModel.State.NeedsLogin -> {}

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
                                            onClick = { detail = PendingDetail.Incoming(challenge) },
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
                                                        viewModel.token.orEmpty(),
                                                        game.color,
                                                        game.fen,
                                                        nameWithRating(game.opponent.username, game.opponent.rating),
                                                        Variant.fromKey(game.variant.key),
                                                        game.lastMove,
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
                                                onClick = { detail = PendingDetail.Outgoing(challenge) },
                                            )
                                        }
                                        pendingSeeks.forEach { seek ->
                                            SeekRow(
                                                seek = seek,
                                                onClick = { detail = PendingDetail.Seek(seek) },
                                            )
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
                            onClick = { navigateTo({ sa -> HistoryScreen(sa, viewModel.token.orEmpty()) }) },
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
                                    NewGameScreen(sa, viewModel.token.orEmpty(), viewModel.username.orEmpty())
                                })
                            },
                            contentDescription = "New game",
                        ),
                    ),
                )
            }

            // Detail popup for a tapped pending challenge/seek, over the whole screen.
            detail?.let { d ->
                PendingDetailOverlay(
                    detail = d,
                    onAccept = {
                        if (d is PendingDetail.Incoming) viewModel.acceptChallenge(d.challenge)
                        detail = null
                    },
                    onDecline = {
                        if (d is PendingDetail.Incoming) viewModel.declineChallenge(d.challenge.id)
                        detail = null
                    },
                    onCancel = {
                        if (d is PendingDetail.Outgoing) viewModel.cancelChallenge(d.challenge.id)
                        detail = null
                    },
                    onClose = { detail = null },
                )
            }
            }
          }
        }
    }
}

// The pending item (challenge/seek) whose detail popup is open.
private sealed interface PendingDetail {
    data class Incoming(val challenge: LichessChallenge) : PendingDetail
    data class Outgoing(val challenge: LichessChallenge) : PendingDetail
    data class Seek(val seek: PendingSeek) : PendingDetail
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

// An incoming challenge, laid out like a game row (reserved asterisk column + name +
// subtitle) so it sits uniformly with the rest of the list. The asterisk is always
// shown — an incoming challenge needs your action, like a "your move" game. Tapping
// the row opens the detail popup (accept/decline live there, not inline).
@Composable
private fun IncomingChallengeRow(
    challenge: LichessChallenge,
    onClick: () -> Unit,
) {
    PendingRow(
        showAsterisk = true,
        dimmed = false,
        name = challenge.challenger?.displayName ?: "Someone",
        rating = challenge.challenger?.ratingOrNull,
        subtitle = "challenge · ${challengeTerms(challenge)}",
        onClick = onClick,
    )
}

// A challenge you've sent, awaiting acceptance — greyed in the "Pending" group.
// Tapping opens the detail popup (cancel lives there).
@Composable
private fun OutgoingChallengeRow(
    challenge: LichessChallenge,
    onClick: () -> Unit,
) {
    PendingRow(
        showAsterisk = false,
        dimmed = true,
        name = challenge.destUser?.displayName ?: "Open challenge",
        rating = challenge.destUser?.ratingOrNull,
        subtitle = "waiting · ${challengeTerms(challenge)}",
        onClick = onClick,
    )
}

// A locally-tracked seek awaiting a random match — greyed. Tapping opens the detail
// popup (which shows the terms; a correspondence seek can't be canceled via the API,
// so the popup offers no cancel). The marker auto-clears when the seek matches into a
// game (reconcileSeeks).
@Composable
private fun SeekRow(seek: PendingSeek, onClick: () -> Unit) {
    PendingRow(
        showAsterisk = false,
        dimmed = true,
        name = "Random opponent",
        rating = null,
        subtitle = "waiting · ${seekTerms(seek)}",
        onClick = onClick,
    )
}

// Shared layout for challenge/seek rows: reserved asterisk column + name (with elo)
// + dimmed subtitle, matching [GameRow] so every list row lines up.
@Composable
private fun PendingRow(
    showAsterisk: Boolean,
    dimmed: Boolean,
    name: String,
    rating: Int?,
    subtitle: String,
    onClick: () -> Unit,
) {
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
            modifier = Modifier.width(ASTERISK_COL_UNITS.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            if (showAsterisk) LightText(text = "*", variant = NAME_VARIANT)
        }
        Column(modifier = Modifier.weight(1f)) {
            if (dimmed) {
                LightText(
                    text = nameWithRating(name, rating),
                    variant = NAME_VARIANT,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                NameWithRating(
                    name = name,
                    rating = rating,
                    nameVariant = NAME_VARIANT,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LightText(text = subtitle, variant = SUBTITLE_VARIANT, lighten = true)
        }
    }
}

// e.g. "2 days/turn · casual". Days omitted if the challenge didn't specify one.
private fun challengeTerms(challenge: LichessChallenge): String {
    val parts = buildList {
        Variant.fromKey(challenge.variant.key).takeIf { it != Variant.STANDARD }?.let { add(it.displayName) }
        challenge.timeControl?.daysPerTurn?.let { add("$it ${plural(it, "day")}/turn") }
        add(if (challenge.rated) "rated" else "casual")
    }
    return parts.joinToString(" · ")
}

// ----- pending challenge/seek detail popup -----

// A full-screen popup with the complete terms of a tapped challenge/seek. Action
// buttons at the bottom: accept/decline for an incoming challenge, cancel for one you
// sent; a seek shows none (correspondence seeks can't be canceled via the API).
@Composable
private fun PendingDetailOverlay(
    detail: PendingDetail,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        val title = when (detail) {
            is PendingDetail.Incoming -> "Incoming challenge"
            is PendingDetail.Outgoing -> "Pending challenge"
            is PendingDetail.Seek -> "Pending seek"
        }
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onClose,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text(title),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
            detailLines(detail).forEach { (label, value) -> DetailRow(label, value) }
        }

        when (detail) {
            is PendingDetail.Incoming -> LightBottomBar(
                items = listOf(
                    LightBarButton.Text(text = "DECLINE", onClick = onDecline),
                    LightBarButton.Text(text = "ACCEPT", onClick = onAccept),
                ),
            )
            is PendingDetail.Outgoing -> LightBottomBar(
                items = listOf(
                    null,
                    LightBarButton.Text(text = "CANCEL", onClick = onCancel),
                ),
            )
            // Seeks can't be canceled via the API, so no action — back closes the popup.
            is PendingDetail.Seek -> Unit
        }
    }
}

// Label/value rows describing a pending challenge or seek.
private fun detailLines(detail: PendingDetail): List<Pair<String, String>> = when (detail) {
    is PendingDetail.Incoming -> {
        val c = detail.challenge
        buildList {
            add("From" to nameWithRating(c.challenger?.displayName ?: "Someone", c.challenger?.ratingOrNull))
            add("Variant" to Variant.fromKey(c.variant.key).displayName)
            c.timeControl?.daysPerTurn?.let { add("Time" to "$it ${plural(it, "day")}/turn") }
            add("Mode" to if (c.rated) "rated" else "casual")
            // finalColor is the CHALLENGER's color; the recipient (me) plays the opposite.
            add("Your side" to opposite(c.finalColor))
        }
    }
    is PendingDetail.Outgoing -> {
        val c = detail.challenge
        buildList {
            add("To" to (c.destUser?.let { nameWithRating(it.displayName, it.ratingOrNull) } ?: "Open challenge"))
            add("Variant" to Variant.fromKey(c.variant.key).displayName)
            c.timeControl?.daysPerTurn?.let { add("Time" to "$it ${plural(it, "day")}/turn") }
            add("Mode" to if (c.rated) "rated" else "casual")
            // I'm the challenger, so finalColor is my color.
            add("Your side" to (c.finalColor ?: "random"))
        }
    }
    is PendingDetail.Seek -> {
        val s = detail.seek
        buildList {
            add("Opponent" to "random")
            add("Variant" to Variant.fromKey(s.variant).displayName)
            add("Time" to "${s.days} ${plural(s.days, "day")}/turn")
            add("Mode" to if (s.rated) "rated" else "casual")
            add("Your side" to s.side)
        }
    }
}

// The color the recipient plays, given the challenger's resolved [finalColor].
private fun opposite(finalColor: String?): String = when (finalColor) {
    "white" -> "black"
    "black" -> "white"
    else -> "random"
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EDGE_UNITS.gridUnitsAsDp(), vertical = ROW_VERTICAL_UNITS.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Subheading, modifier = Modifier.weight(1f))
        LightText(text = value, variant = LightTextVariant.Subheading, lighten = true)
    }
}

// e.g. "Horde · 2 days/turn · casual · white". Variant shown only when non-standard;
// side omitted when random.
private fun seekTerms(seek: PendingSeek): String {
    val parts = buildList {
        Variant.fromKey(seek.variant).takeIf { it != Variant.STANDARD }?.let { add(it.displayName) }
        add("${seek.days} ${plural(seek.days, "day")}/turn")
        add(if (seek.rated) "rated" else "casual")
        if (seek.side != "random") add(seek.side)
    }
    return parts.joinToString(" · ")
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

// Subtitle: "their move"/time-remaining leads, then the variant name (non-standard
// games only). Turn state is the more time-sensitive fact, so it comes first.
private fun buildSubtitle(game: LichessGame): String {
    val parts = buildList {
        if (game.isMyTurn) {
            // `secondsLeft` from /api/account/playing is ALWAYS the account's own clock,
            // not the current mover's (verified against the board stream's wtime/btime).
            // So it's only meaningful on your turn — your move deadline. On the opponent's
            // turn it's your frozen clock, NOT their deadline (nowPlaying doesn't include
            // the opponent's time at all), so showing it there was misleading. Getting the
            // opponent's real time would require streaming each game, which we don't do on
            // the list.
            game.secondsLeft?.let { add("${formatDuration(it)} left") }
        } else {
            add("their move")
        }
        // Show the variant name only for non-standard games, using the engine's display
        // name — matches the board screen's subtitle (BoardViewModel). The previous check
        // compared the Lichess key ("standard") to the enum name ("STANDARD"), which never
        // matched, so "standard" was shown on every row.
        val variant = Variant.fromKey(game.variant.key)
        if (variant != Variant.STANDARD) add(variant.displayName)
    }
    return parts.joinToString(" · ")
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

// Ply count (half-moves played so far) derived from the FEN's active-color + fullmove
// fields — no extra API call. Standard start = ply 0; after White's 1st move = ply 1;
// after Black's 1st move (fullmove increments to 2) = ply 2. Used by
// [ChessSettings.reconcileSeeks] to tell an early-aborted game (< 2 plies, matching
// BoardViewModel's own canAbort threshold) from a legitimately finished one.
private fun LichessGame.plyCount(): Int? {
    val parts = fen.trim().split(" ")
    if (parts.size < 6) return null
    val activeColor = parts[1]
    val fullmove = parts[5].toIntOrNull() ?: return null
    return 2 * (fullmove - 1) + if (activeColor == "b") 1 else 0
}

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
