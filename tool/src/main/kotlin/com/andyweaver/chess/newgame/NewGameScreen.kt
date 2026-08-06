package com.andyweaver.chess.newgame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.andyweaver.chess.board.LocalGameScreen
import com.andyweaver.chess.lichess.LichessActionResult
import com.andyweaver.chess.lichess.LichessApi
import com.andyweaver.chess.lichess.LichessUser
import com.andyweaver.chess.ui.NameWithRating
import com.andyweaver.chess.settings.ChessSettings
import com.andyweaver.chess.settings.PendingSeek
import java.util.UUID
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Correspondence days-per-turn Lichess accepts, in the order the "Time per move"
// row cycles through them.
private val DAYS_OPTIONS = listOf(1, 2, 3, 5, 7, 10, 14)
const val EDGE_UNITS = 1f
const val ROW_VERTICAL_UNITS = 1f

/** Shown in a value slot that has no choice yet (the Opponent row before you pick one). */
private const val UNSET_VALUE = "—"

// ---------------------------------------------------------------------------
// Pure rules (kept top-level and Android-free so they can be unit tested; see
// tool/src/test/kotlin/com/andyweaver/chess/newgame/NewGameRulesTest.kt).
// ---------------------------------------------------------------------------

/**
 * Lichess's meaningful rating window for `ratingRange`. Bounds outside this
 * effectively disable that side of the filter, so there's nothing to gain by
 * emitting anything wider — and a value the server rejects would be a hard 400.
 */
internal const val RATING_MIN = 400
internal const val RATING_MAX = 2900

/**
 * The rating filter offered for a random-opponent seek.
 *
 * Deliberately a handful of relative bands rather than a slider or two number
 * fields: the API wants absolute `"min-max"`, but nobody thinks in absolute
 * ratings when they just want "someone near me", and a numeric entry on this
 * phone is a keyboard trip for a value that only ever needs to be approximate.
 * [delta] is the half-width in rating points around your own rating; [NONE]
 * (the default) sends no filter at all.
 */
enum class RatingBand(val label: String, val delta: Int?) {
    NONE("Any rating", null),
    WITHIN_100("Within 100", 100),
    WITHIN_200("Within 200", 200),
    WITHIN_300("Within 300", 300),
    WITHIN_500("Within 500", 500),
}

/**
 * Converts a [band] plus your own correspondence rating into the absolute
 * `"<min>-<max>"` string `POST /api/board/seek` wants, or null for no filter.
 *
 * Null whenever there's nothing meaningful to send: [RatingBand.NONE], or no
 * known own rating (a brand-new account has no `perfs.correspondence`). Bounds
 * are clamped to [RATING_MIN]..[RATING_MAX] and min is *guaranteed* strictly
 * below max — Lichess 400s on `"1500-1500"`, and clamping alone can collapse
 * the two ends together for an absurd rating.
 */
internal fun ratingRangeFor(band: RatingBand, ownRating: Int?): String? {
    val delta = band.delta ?: return null
    if (ownRating == null) return null
    var min = (ownRating - delta).coerceIn(RATING_MIN, RATING_MAX)
    var max = (ownRating + delta).coerceIn(RATING_MIN, RATING_MAX)
    if (min >= max) {
        // Only reachable for a rating outside the clamp window; keep the range
        // legal rather than letting an impossible input become an HTTP 400.
        if (max < RATING_MAX) max = min + 1 else min = max - 1
    }
    return "$min-$max"
}

/**
 * Whether a rated game is possible in this variant. Lichess rejects a rated
 * correspondence game in any non-standard variant with a 400 "can't be rated",
 * for both seeks and challenges — so the UI never offers the combination.
 */
internal fun ratedAllowed(variantKey: String): Boolean = variantKey == "standard"

/**
 * New-game flow. Everything lives on ONE screen ([Step.OPTIONS]); the choices with
 * too many values to tap-cycle open a full-screen picker sub-step and come back:
 *
 * - [Step.OPPONENT] — random seek, a friend, or "Enter username" ([Step.USERNAME]).
 *   These now only *select* an opponent; nothing is sent until CREATE GAME.
 * - [Step.VARIANT]  — the nine variants the board can render.
 * - [Step.RATING]   — the seek-only rating filter ([RatingBand]).
 * - [Step.DONE]     — confirmation; back returns to the home list.
 *
 * The last two option rows depend on who you're playing, because the two endpoints
 * genuinely differ: a seek accepts `ratingRange` but ignores `color` entirely
 * (lila's `Seek.make` takes no colour), while a challenge accepts `color` and has
 * no `ratingRange`. So "Rating filter" shows only for a random opponent and "Your
 * side" only for a named one, and neither appears before an opponent is chosen.
 */
class NewGameViewModel(
    private val api: LichessApi,
    private val settings: ChessSettings,
    private val accountKey: String,
    // When launched from home's in-person entry, start on the in-person options directly.
    startInPerson: Boolean = false,
    // False when opened from the logged-out login screen: no account, so no Lichess calls.
    hasToken: Boolean = true,
) : LightViewModel<Unit>() {

    enum class Step { OPTIONS, VARIANT, OPPONENT, USERNAME, RATING, DONE }

    /** Online (Lichess) vs. in-person hot-seat play on this one device. */
    enum class Mode(val label: String) { ONLINE("Online"), IN_PERSON("In person") }

    /**
     * In-person board orientation. [ACROSS] keeps the board fixed and rotates
     *      * the far side's pieces for a player sitting opposite; [SIDE_BY_SIDE] flips the board after each move so the
     * player to move is always at the bottom. Passed to [LocalGameScreen].
     */
    enum class PlayMode(val label: String) { ACROSS("Across"), SIDE_BY_SIDE("Side by side") }

    enum class Side(val label: String, val apiValue: String) {
        RANDOM("Random", "random"),
        WHITE("White", "white"),
        BLACK("Black", "black"),
    }

    // Every variant the board can play. [apiValue] is the Lichess variant key.
    enum class Variant(val label: String, val apiValue: String) {
        STANDARD("Standard", "standard"),
        CRAZYHOUSE("Crazyhouse", "crazyhouse"),
        CHESS960("Chess960", "chess960"),
        KING_OF_THE_HILL("King of the Hill", "kingOfTheHill"),
        THREE_CHECK("Three-check", "threeCheck"),
        ANTICHESS("Antichess", "antichess"),
        ATOMIC("Atomic", "atomic"),
        HORDE("Horde", "horde"),
        RACING_KINGS("Racing Kings", "racingKings"),
    }

    /** Who you're playing: an unnamed random match (a seek) or a specific player (a challenge). */
    sealed class Opponent {
        object Random : Opponent()
        data class Named(val username: String) : Opponent()

        val label: String
            get() = when (this) {
                Random -> "Random"
                is Named -> username
            }
    }

    data class Options(
        val mode: Mode = Mode.ONLINE,
        val playMode: PlayMode = PlayMode.ACROSS,
        // No default: CREATE GAME stays inert until this is chosen.
        val opponent: Opponent? = null,
        val days: Int = 2,
        val rated: Boolean = false,
        val side: Side = Side.RANDOM,
        val variant: Variant = Variant.STANDARD,
        val ratingBand: RatingBand = RatingBand.NONE,
    )

    /** One-shot request to open the in-person board with a chosen variant/orientation. */
    data class LocalGameRequest(val variantKey: String, val across: Boolean)

    sealed class Friends {
        object Loading : Friends()
        data class Loaded(val users: List<LichessUser>) : Friends()
        data class Error(val message: String) : Friends()
    }

    data class UiState(
        val step: Step = Step.OPTIONS,
        val options: Options = Options(),
        val friends: Friends = Friends.Loading,
        // Own correspondence rating, needed to turn a RatingBand into absolute bounds.
        // Null (unknown / unrated / fetch failed) hides the rating-filter row entirely.
        val ownRating: Int? = null,
        val sending: Boolean = false,
        val doneMessage: String? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(
        UiState(options = Options(mode = if (startInPerson) Mode.IN_PERSON else Mode.ONLINE)),
    )
    val uiState: StateFlow<UiState> = _uiState

    // One-shot: set when the user starts an in-person game, consumed by the screen to
    // navigate to LocalGameScreen (mirrors HomeScreen's openGame pattern).
    private val _startLocal = MutableStateFlow<LocalGameRequest?>(null)
    val startLocal: StateFlow<LocalGameRequest?> = _startLocal

    init {
        // Fetched here rather than threaded in from home: only this screen needs it,
        // and a failure is harmless (the rating filter just doesn't appear).
        if (hasToken) {
            viewModelScope.launch(Dispatchers.IO) {
                val rating = runCatching { api.getOwnCorrespondenceRating() }.getOrNull()?.rating
                _uiState.update { it.copy(ownRating = rating) }
            }
        }
    }

    // ----- options -----

    fun cycleMode() = _uiState.update {
        val values = Mode.entries
        val next = values[(it.options.mode.ordinal + 1) % values.size]
        it.copy(options = it.options.copy(mode = next))
    }

    fun cyclePlayMode() = _uiState.update {
        val values = PlayMode.entries
        val next = values[(it.options.playMode.ordinal + 1) % values.size]
        it.copy(options = it.options.copy(playMode = next))
    }

    fun startLocalGame() {
        val opts = _uiState.value.options
        _startLocal.value = LocalGameRequest(
            variantKey = opts.variant.apiValue,
            across = opts.playMode == PlayMode.ACROSS,
        )
    }

    fun consumeStartLocal() { _startLocal.value = null }

    fun cycleDays() = _uiState.update {
        val next = DAYS_OPTIONS[(DAYS_OPTIONS.indexOf(it.options.days) + 1) % DAYS_OPTIONS.size]
        it.copy(options = it.options.copy(days = next))
    }

    /** No-op in a variant that can't be rated — the row is inert there, not silently wrong. */
    fun toggleRated() = _uiState.update {
        if (!ratedAllowed(it.options.variant.apiValue)) it
        else it.copy(options = it.options.copy(rated = !it.options.rated))
    }

    fun cycleSide() = _uiState.update {
        val values = Side.entries
        val next = values[(it.options.side.ordinal + 1) % values.size]
        it.copy(options = it.options.copy(side = next))
    }

    // Nine variants is too many to cycle through a single row, so the "Variant" row
    // opens a full-screen picker (Step.VARIANT) instead.
    fun goToVariant() = _uiState.update { it.copy(step = Step.VARIANT) }

    fun selectVariant(variant: Variant) = _uiState.update {
        it.copy(
            step = Step.OPTIONS,
            options = it.options.copy(
                variant = variant,
                // The other ordering of the same rule: choosing a variant while Rated
                // is already selected drops back to Casual rather than leaving an
                // invalid combination staged.
                rated = it.options.rated && ratedAllowed(variant.apiValue),
            ),
        )
    }

    fun goToRatingFilter() = _uiState.update { it.copy(step = Step.RATING) }

    fun selectRatingBand(band: RatingBand) = _uiState.update {
        it.copy(step = Step.OPTIONS, options = it.options.copy(ratingBand = band))
    }

    // ----- opponent selection -----

    fun goToOpponents() {
        _uiState.update { it.copy(step = Step.OPPONENT, friends = Friends.Loading) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { api.getFollowing() }
            _uiState.update {
                it.copy(
                    friends = result.fold(
                        onSuccess = { users -> Friends.Loaded(users) },
                        onFailure = { e -> Friends.Error(e.message ?: "Couldn't load your friends") },
                    ),
                )
            }
        }
    }

    fun goToUsername() = _uiState.update { it.copy(step = Step.USERNAME) }

    fun selectRandomOpponent() = selectOpponent(Opponent.Random)

    fun selectOpponentUsername(username: CharSequence) {
        val name = username.trim().toString()
        if (name.isEmpty()) return
        selectOpponent(Opponent.Named(name))
    }

    private fun selectOpponent(opponent: Opponent) = _uiState.update {
        it.copy(step = Step.OPTIONS, options = it.options.copy(opponent = opponent))
    }

    /** Handles the on-screen/hardware back per step; returns true when we consumed it. */
    override fun onBackPressed(): Boolean = when (_uiState.value.step) {
        Step.OPTIONS, Step.DONE -> false
        Step.VARIANT, Step.RATING, Step.OPPONENT -> { _uiState.update { it.copy(step = Step.OPTIONS) }; true }
        Step.USERNAME -> { _uiState.update { it.copy(step = Step.OPPONENT) }; true }
    }

    // ----- sending -----

    /** Submits the staged options. Inert until an opponent has been chosen. */
    fun createGame() {
        val state = _uiState.value
        val opts = state.options
        when (val opponent = opts.opponent) {
            null -> return
            Opponent.Random -> seekRandom(opts, state.ownRating)
            is Opponent.Named -> challengeUser(opponent.username, opts)
        }
    }

    private fun challengeUser(name: String, opts: Options) {
        send("Challenge sent to $name.\nIt appears on your home list once accepted.") {
            api.createCorrespondenceChallenge(name, opts.days, opts.rated, opts.side.apiValue, opts.variant.apiValue)
        }
    }

    private fun seekRandom(opts: Options, ownRating: Int?) {
        val ratingRange = ratingRangeFor(opts.ratingBand, ownRating)
        send("Looking for an opponent…\nThe game starts on your home list once matched.") {
            val result = api.seekCorrespondence(opts.days, opts.rated, opts.variant.apiValue, ratingRange)
            if (result is LichessActionResult.Success) {
                // Lichess can't list/cancel correspondence seeks, so remember it locally
                // to show in the home "Pending" group until it matches.
                settings.addPendingSeek(
                    accountKey,
                    PendingSeek(
                        id = UUID.randomUUID().toString(),
                        days = opts.days,
                        rated = opts.rated,
                        // A seek has no colour preference — the endpoint doesn't take one.
                        side = Side.RANDOM.apiValue,
                        createdAt = System.currentTimeMillis(),
                        variant = opts.variant.apiValue,
                    ),
                )
            }
            result
        }
    }

    private fun send(successMessage: String, block: suspend () -> LichessActionResult) {
        if (_uiState.value.sending) return
        _uiState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = block()) {
                LichessActionResult.Success ->
                    _uiState.update { it.copy(sending = false, step = Step.DONE, doneMessage = successMessage) }
                is LichessActionResult.Failure ->
                    _uiState.update { it.copy(sending = false, error = result.error) }
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}

class NewGameScreen(
    sealedActivity: SealedLightActivity,
    private val token: String,
    private val accountKey: String,
    private val startInPerson: Boolean = false,
) : LightScreen<Unit, NewGameViewModel>(sealedActivity) {

    override val viewModelClass: Class<NewGameViewModel>
        get() = NewGameViewModel::class.java

    override fun createViewModel(): NewGameViewModel = NewGameViewModel(
        LichessApi(token),
        ChessSettings(lightContext.dataStore),
        accountKey,
        startInPerson,
        hasToken = token.isNotBlank(),
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val startLocal by viewModel.startLocal.collectAsState()

        // Starting an in-person game jumps to the local (hot-seat) board.
        LaunchedEffect(startLocal) {
            val req = startLocal ?: return@LaunchedEffect
            viewModel.consumeStartLocal()
            navigateTo({ sa -> LocalGameScreen(sa, req.variantKey, req.across, token) })
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (state.step) {
                    NewGameViewModel.Step.OPTIONS -> OptionsStep(state)
                    NewGameViewModel.Step.VARIANT -> VariantStep(state.options.variant)
                    NewGameViewModel.Step.OPPONENT -> OpponentStep(state.friends)
                    NewGameViewModel.Step.USERNAME -> UsernameStep()
                    NewGameViewModel.Step.RATING -> RatingFilterStep(state.options.ratingBand)
                    NewGameViewModel.Step.DONE -> DoneStep(state.doneMessage.orEmpty())
                }

                if (state.sending) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(text = "Sending…", variant = LightTextVariant.Copy)
                    }
                }

                state.error?.let { error ->
                    LightFullscreenModal(message = error, onClose = { viewModel.clearError() })
                }
            }
        }
    }

    // ----- the one screen: every choice, then CREATE GAME -----

    @Composable
    private fun OptionsStep(state: NewGameViewModel.UiState) {
        val options = state.options
        val inPerson = options.mode == NewGameViewModel.Mode.IN_PERSON
        val opponent = options.opponent
        val canRate = ratedAllowed(options.variant.apiValue)
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(if (inPerson) "New in-person game" else "New daily game"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Online vs in-person pinned at the top; it swaps which options follow.
                // Hidden when there's no token (opened from the logged-out login screen):
                // online play is impossible without an account, so we stay in-person only.
                if (token.isNotBlank()) {
                    OptionRow("Game mode", options.mode.label) { viewModel.cycleMode() }
                }
                if (inPerson) {
                    OptionRow("Play mode", options.playMode.label) { viewModel.cyclePlayMode() }
                    // Nine variants — open a picker rather than cycling one at a time.
                    OptionRow("Variant", options.variant.label) { viewModel.goToVariant() }
                } else {
                    OptionRow("Opponent", opponent?.label ?: UNSET_VALUE) { viewModel.goToOpponents() }

                    val days = options.days
                    OptionRow("Time per move", "$days ${if (days == 1) "day" else "days"}") { viewModel.cycleDays() }
                    OptionRow(
                        label = "Rating",
                        value = if (options.rated) "Rated" else "Casual",
                        enabled = canRate,
                        onClick = { viewModel.toggleRated() },
                    )
                    OptionRow("Variant", options.variant.label) { viewModel.goToVariant() }

                    // The tail rows are opponent-type specific, because the seek and
                    // challenge endpoints accept genuinely different options.
                    when (opponent) {
                        null -> ""
                        NewGameViewModel.Opponent.Random ->
                            // Needs your own rating to turn a band into absolute bounds;
                            // without one (new/unrated account, or the fetch failed) the
                            // filter simply isn't offered.
                            if (state.ownRating != null) {
                                OptionRow("Rating filter", options.ratingBand.label) {
                                    viewModel.goToRatingFilter()
                                }
                            }
                        is NewGameViewModel.Opponent.Named ->
                            OptionRow("Your side", options.side.label) { viewModel.cycleSide() }
                    }
                }
            }
            LightBottomBar(
                items = listOf(
                    null,
                    if (inPerson) {
                        LightBarButton.Text(text = "START", onClick = { viewModel.startLocalGame() })
                    } else {
                        // Inert until an opponent is chosen (createGame() returns early);
                        // the hint above the bar says what's missing.
                        LightBarButton.Text(text = "CREATE", onClick = { viewModel.createGame() })
                    },
                ),
            )
        }
    }

    // ----- variant picker (opened from the "Variant" row) -----

    @Composable
    private fun VariantStep(selected: NewGameViewModel.Variant) {
        PickerStep(title = "Variant") {
            NewGameViewModel.Variant.entries.forEach { variant ->
                SelectableRow(
                    label = variant.label,
                    selected = variant == selected,
                    onClick = { viewModel.selectVariant(variant) },
                )
            }
        }
    }

    // ----- rating-filter picker (opened from the "Rating filter" row) -----

    @Composable
    private fun RatingFilterStep(selected: RatingBand) {
        PickerStep(title = "Rating filter") {
            RatingBand.entries.forEach { band ->
                SelectableRow(
                    label = band.label,
                    selected = band == selected,
                    onClick = { viewModel.selectRatingBand(band) },
                )
            }
        }
    }

    // ----- opponent picker -----

    @Composable
    private fun OpponentStep(friends: NewGameViewModel.Friends) {
        PickerStep(title = "Opponent") {
            ActionRow("Random opponent") { viewModel.selectRandomOpponent() }
            ActionRow("Enter username") { viewModel.goToUsername() }

            SectionLabel("Friends")
            when (friends) {
                is NewGameViewModel.Friends.Loading ->
                    HintText("Loading…")
                is NewGameViewModel.Friends.Error ->
                    HintText(friends.message)
                is NewGameViewModel.Friends.Loaded ->
                    if (friends.users.isEmpty()) {
                        HintText("You aren't following anyone on Lichess yet.")
                    } else {
                        friends.users.forEach { user ->
                            FriendRow(user) {
                                viewModel.selectOpponentUsername(user.username ?: user.displayName)
                            }
                        }
                    }
            }
        }
    }

    // ----- username entry (reached from the opponent picker) -----

    @Composable
    private fun UsernameStep() {
        val textFieldState = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()
        LightTextInputEditor(
            title = "Opponent username",
            state = textFieldState,
            onSubmit = { viewModel.selectOpponentUsername(it) },
            onBack = { goBack() },
            keyboardOptionsFlow = keyboardOptions,
            // No submitIcon -> LightTextInputEditor renders a descriptive text button.
            // This only SELECTS the opponent now; the game is created back on the
            // options screen, so the label mustn't promise otherwise.
            submitLabel = "SELECT",
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    }

    // ----- confirmation -----

    @Composable
    private fun DoneStep(message: String) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = EDGE_UNITS.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
            }
            LightBottomBar(
                items = listOf(
                    null,
                    LightBarButton.Text(text = "DONE", onClick = { goBack() }),
                ),
            )
        }
    }

    // ----- shared rows -----

    /** A full-screen sub-step: back-arrow top bar over a scrolling list of rows. */
    @Composable
    private fun PickerStep(title: String, content: @Composable () -> Unit) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                content()
            }
        }
    }

    /**
     * A label/value row. [enabled] = false makes it inert *and* dims the label, so a
     * choice that can't be changed (rated in a variant) reads as unavailable rather
     * than broken.
     */
    @Composable
    private fun OptionRow(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (enabled) it.lightClickable(onClick = onClick) else it }
                .padding(
                    horizontal = EDGE_UNITS.gridUnitsAsDp(),
                    vertical = ROW_VERTICAL_UNITS.gridUnitsAsDp(),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = label,
                variant = LightTextVariant.Subheading,
                lighten = !enabled,
                modifier = Modifier.weight(1f),
            )
            LightText(text = value, variant = LightTextVariant.Subheading, lighten = true)
        }
    }

    // A tappable list row; the current selection is underlined, matching the LP-native
    // selector style (e.g. the system ringtone/Messages picker).
    @Composable
    private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Subheading,
            underline = selected,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(
                    horizontal = EDGE_UNITS.gridUnitsAsDp(),
                    vertical = ROW_VERTICAL_UNITS.gridUnitsAsDp(),
                ),
        )
    }

    @Composable
    private fun ActionRow(label: String, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Subheading,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(
                    horizontal = EDGE_UNITS.gridUnitsAsDp(),
                    vertical = ROW_VERTICAL_UNITS.gridUnitsAsDp(),
                ),
        )
    }

    @Composable
    private fun FriendRow(user: LichessUser, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(
                    horizontal = EDGE_UNITS.gridUnitsAsDp(),
                    vertical = ROW_VERTICAL_UNITS.gridUnitsAsDp(),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NameWithRating(
                name = user.displayName,
                rating = user.ratingOrNull,
                prov = user.provOrNull,
                nameVariant = LightTextVariant.Subheading,
                modifier = Modifier.weight(1f),
            )
            if (user.online) {
                LightText(text = "online", variant = LightTextVariant.Detail, lighten = true)
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(
                start = EDGE_UNITS.gridUnitsAsDp(),
                top = 1f.gridUnitsAsDp(),
                bottom = 0.5f.gridUnitsAsDp(),
            ),
        )
    }

    @Composable
    private fun HintText(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.padding(
                horizontal = EDGE_UNITS.gridUnitsAsDp(),
                vertical = ROW_VERTICAL_UNITS.gridUnitsAsDp(),
            ),
        )
    }
}
