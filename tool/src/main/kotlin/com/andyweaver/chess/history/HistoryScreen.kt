package com.andyweaver.chess.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.andyweaver.chess.board.ReviewScreen
import com.andyweaver.chess.lichess.LichessApi
import com.andyweaver.chess.lichess.LichessArchivedGame
import com.andyweaver.chess.lichess.nameWithRating
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
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
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20
private const val EDGE_PADDING_UNITS = 1f
private val NAME_VARIANT = LightTextVariant.Subheading
private val SUBTITLE_VARIANT = LightTextVariant.Detail

// Statuses that mean the game is still in progress.
private val ONGOING_STATUSES = setOf("created", "started")

class HistoryViewModel(private val token: String) : LightViewModel<Unit>() {

    sealed class State {
        object Loading : State()
        data class Loaded(
            val games: List<LichessArchivedGame>,
            val loadingMore: Boolean,
            val endReached: Boolean,
        ) : State()
        data class Error(val message: String) : State()
    }

    private val api = LichessApi(token)
    var username: String = ""
        private set
    private var oldestCreatedAt: Long? = null
    private var loading = false

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Load once per screen instance (don't re-fetch on app resume).
        if (_state.value is State.Loading && !loading) loadFirst()
    }

    private fun loadFirst() {
        loading = true
        _state.value = State.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (username.isEmpty()) username = api.getAccountUsername()
                val page = api.getUserGames(username, PAGE_SIZE)
                oldestCreatedAt = page.lastOrNull()?.createdAt
                _state.value = State.Loaded(page, loadingMore = false, endReached = page.size < PAGE_SIZE)
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Couldn't load games")
            } finally {
                loading = false
            }
        }
    }

    fun loadMore() {
        val current = _state.value as? State.Loaded ?: return
        if (loading || current.endReached || username.isEmpty()) return
        val until = oldestCreatedAt ?: return
        loading = true
        _state.value = current.copy(loadingMore = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = api.getUserGames(username, PAGE_SIZE, until = until)
                val existingIds = current.games.mapTo(HashSet()) { it.id }
                val fresh = page.filterNot { it.id in existingIds }
                oldestCreatedAt = fresh.lastOrNull()?.createdAt ?: oldestCreatedAt
                _state.value = State.Loaded(
                    games = current.games + fresh,
                    loadingMore = false,
                    endReached = fresh.isEmpty(),
                )
            } catch (e: Exception) {
                _state.value = current.copy(loadingMore = false)
            } finally {
                loading = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}

class HistoryScreen(
    sealedActivity: SealedLightActivity,
    private val token: String,
) : LightScreen<Unit, HistoryViewModel>(sealedActivity) {

    override val viewModelClass: Class<HistoryViewModel>
        get() = HistoryViewModel::class.java

    override fun createViewModel(): HistoryViewModel = HistoryViewModel(token)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

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
                    center = LightTopBarCenter.Text("History"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val s = state) {
                        is HistoryViewModel.State.Loading -> Hint("Loading…")
                        is HistoryViewModel.State.Error -> Hint(s.message)
                        is HistoryViewModel.State.Loaded ->
                            if (s.games.isEmpty()) {
                                Hint("No games yet.")
                            } else {
                                GameList(state = s, username = viewModel.username)
                            }
                    }
                }
            }
        }
    }

    @Composable
    private fun GameList(state: HistoryViewModel.State.Loaded, username: String) {
        LightScrollView(modifier = Modifier.fillMaxSize()) {
            state.games.forEach { game ->
                HistoryRow(game = game, username = username, onClick = { openReview(game, username) })
            }
            // Chunked loading: tap to pull the next page of older games.
            if (!state.endReached) {
                LoadMoreRow(loading = state.loadingMore) { viewModel.loadMore() }
            }
        }
    }

    @Composable
    private fun LoadMoreRow(loading: Boolean, onClick: () -> Unit) {
        LightText(
            text = if (loading) "Loading…" else "Load older games",
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            lighten = true,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (loading) Modifier else Modifier.lightClickable(onClick = onClick))
                .padding(vertical = 1.25f.gridUnitsAsDp()),
        )
    }

    private fun openReview(game: LichessArchivedGame, username: String) {
        val iAmWhite = isWhite(game, username)
        val opp = if (iAmWhite) game.players.black else game.players.white
        val oppName = opp.user?.displayName ?: "Anonymous"
        val mySide = if (iAmWhite) "white" else "black"
        navigateTo({ sa ->
            ReviewScreen(
                sa,
                movesSan = game.moves,
                initialFen = game.initialFen,
                myColorName = mySide,
                title = "vs $oppName",
                result = "$mySide · ${resultText(game, iAmWhite)}",
            )
        })
    }

    @Composable
    private fun Hint(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(horizontal = EDGE_PADDING_UNITS.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun HistoryRow(game: LichessArchivedGame, username: String, onClick: () -> Unit) {
    val iAmWhite = isWhite(game, username)
    val opp = if (iAmWhite) game.players.black else game.players.white
    val oppName = opp.user?.displayName ?: "Anonymous"
    val mySide = if (iAmWhite) "white" else "black"
    val subtitle = buildList {
        add(mySide)
        add(resultText(game, iAmWhite))
        game.speed?.let { add(it) }
        relativeTime(game.lastMoveAt)?.let { add(it) }
    }.joinToString(" · ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = EDGE_PADDING_UNITS.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(
            text = nameWithRating(oppName, opp.rating),
            variant = NAME_VARIANT,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(text = subtitle, variant = SUBTITLE_VARIANT, lighten = true)
    }
}

// True if the account (by [username]) played White in [game].
private fun isWhite(game: LichessArchivedGame, username: String): Boolean =
    game.players.white.user?.name?.equals(username, ignoreCase = true) == true

private fun resultText(game: LichessArchivedGame, iAmWhite: Boolean): String = when {
    game.status.lowercase() in ONGOING_STATUSES -> "ongoing"
    game.status.lowercase() == "stalemate" -> "stale"
    game.winner == null -> "draw"
    (game.winner == "white") == iAmWhite -> "win"
    else -> "loss"
}

// Short relative time for [epochMs] vs now, or null if the timestamp is missing.
private fun relativeTime(epochMs: Long): String? {
    if (epochMs <= 0) return null
    val diff = System.currentTimeMillis() - epochMs
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> if (hours > 1) "$hours hrs ago" else "$hours hr ago"
        days < 30 -> if (days > 1) "$days days ago" else "$days day ago"
        else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(epochMs))
    }
}
