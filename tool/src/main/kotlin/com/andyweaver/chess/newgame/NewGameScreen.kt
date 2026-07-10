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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
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
private const val EDGE_UNITS = 1f
private const val ROW_VERTICAL_UNITS = 1f

/**
 * New-game flow, options first then opponent (per Andy's spec):
 *
 * 1. [Step.OPTIONS]  — time per move, casual/rated, and which side you play.
 * 2. [Step.OPPONENT] — pick a random correspondence seek, type a username, or a friend.
 * 3. [Step.USERNAME] — free-text username entry (reached from step 2).
 * 4. [Step.DONE]     — confirmation; back returns to the home list.
 *
 * Variant can be Standard or any of the variants the board can render (Horde, KotH,
 * Three-check, Racing Kings, Antichess). Rated is allowed (both test accounts can play
 * rated correspondence). A seek waits in the background for a match; a challenge
 * waits for the named player to accept — both surface on home when they resolve.
 */
class NewGameViewModel(
    private val api: LichessApi,
    private val settings: ChessSettings,
    private val accountKey: String,
) : LightViewModel<Unit>() {

    enum class Step { OPTIONS, VARIANT, OPPONENT, USERNAME, DONE }

    enum class Side(val label: String, val apiValue: String) {
        RANDOM("Random", "random"),
        WHITE("White", "white"),
        BLACK("Black", "black"),
    }

    // Only the variants the board can render faithfully (see BoardViewModel's
    // UNSUPPORTED_VARIANTS) — so we never create a game that lands on the board's
    // "not supported yet" screen. [apiValue] is the Lichess variant key.
    enum class Variant(val label: String, val apiValue: String) {
        STANDARD("Standard", "standard"),
        HORDE("Horde", "horde"),
        KING_OF_THE_HILL("King of the Hill", "kingOfTheHill"),
        THREE_CHECK("Three-check", "threeCheck"),
        RACING_KINGS("Racing Kings", "racingKings"),
        ANTICHESS("Antichess", "antichess"),
    }

    data class Options(
        val days: Int = 2,
        val rated: Boolean = false,
        val side: Side = Side.RANDOM,
        val variant: Variant = Variant.STANDARD,
    )

    sealed class Friends {
        object Loading : Friends()
        data class Loaded(val users: List<LichessUser>) : Friends()
        data class Error(val message: String) : Friends()
    }

    data class UiState(
        val step: Step = Step.OPTIONS,
        val options: Options = Options(),
        val friends: Friends = Friends.Loading,
        val sending: Boolean = false,
        val doneMessage: String? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    // ----- options -----

    fun cycleDays() = _uiState.update {
        val next = DAYS_OPTIONS[(DAYS_OPTIONS.indexOf(it.options.days) + 1) % DAYS_OPTIONS.size]
        it.copy(options = it.options.copy(days = next))
    }

    fun toggleRated() = _uiState.update {
        it.copy(options = it.options.copy(rated = !it.options.rated))
    }

    fun cycleSide() = _uiState.update {
        val values = Side.values()
        val next = values[(it.options.side.ordinal + 1) % values.size]
        it.copy(options = it.options.copy(side = next))
    }

    // Six variants is too many to cycle through a single row, so the "Variant" row
    // opens a full-screen picker (Step.VARIANT) instead.
    fun goToVariant() = _uiState.update { it.copy(step = Step.VARIANT) }

    fun selectVariant(variant: Variant) = _uiState.update {
        it.copy(step = Step.OPTIONS, options = it.options.copy(variant = variant))
    }

    // ----- navigation between steps -----

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

    /** Handles the on-screen/hardware back per step; returns true when we consumed it. */
    override fun onBackPressed(): Boolean = when (_uiState.value.step) {
        Step.OPTIONS, Step.DONE -> false
        Step.VARIANT -> { _uiState.update { it.copy(step = Step.OPTIONS) }; true }
        Step.OPPONENT -> { _uiState.update { it.copy(step = Step.OPTIONS) }; true }
        Step.USERNAME -> { _uiState.update { it.copy(step = Step.OPPONENT) }; true }
    }

    // ----- sending -----

    fun challengeUser(username: CharSequence) {
        val name = username.trim().toString()
        if (name.isEmpty()) return
        val opts = _uiState.value.options
        send("Challenge sent to $name.\nIt appears on your home list once accepted.") {
            api.createCorrespondenceChallenge(name, opts.days, opts.rated, opts.side.apiValue, opts.variant.apiValue)
        }
    }

    fun seekRandom() {
        val opts = _uiState.value.options
        send("Looking for an opponent…\nThe game starts on your home list once matched.") {
            val result = api.seekCorrespondence(opts.days, opts.rated, opts.side.apiValue, opts.variant.apiValue)
            if (result is LichessActionResult.Success) {
                // Lichess can't list/cancel correspondence seeks, so remember it locally
                // to show in the home "Pending" group until it matches.
                settings.addPendingSeek(
                    accountKey,
                    PendingSeek(
                        id = UUID.randomUUID().toString(),
                        days = opts.days,
                        rated = opts.rated,
                        side = opts.side.apiValue,
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
) : LightScreen<Unit, NewGameViewModel>(sealedActivity) {

    override val viewModelClass: Class<NewGameViewModel>
        get() = NewGameViewModel::class.java

    override fun createViewModel(): NewGameViewModel =
        NewGameViewModel(LichessApi(token), ChessSettings(lightContext.dataStore), accountKey)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (state.step) {
                    NewGameViewModel.Step.OPTIONS -> OptionsStep(state.options)
                    NewGameViewModel.Step.VARIANT -> VariantStep(state.options.variant)
                    NewGameViewModel.Step.OPPONENT -> OpponentStep(state.friends)
                    NewGameViewModel.Step.USERNAME -> UsernameStep()
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

    // ----- step 1: options -----

    @Composable
    private fun OptionsStep(options: NewGameViewModel.Options) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("New game"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val days = options.days
                OptionRow("Time per move", "$days ${if (days == 1) "day" else "days"}") { viewModel.cycleDays() }
                OptionRow("Mode", if (options.rated) "Rated" else "Casual") { viewModel.toggleRated() }
                OptionRow("Your side", options.side.label) { viewModel.cycleSide() }
                // Six variants — open a picker rather than cycling one at a time.
                OptionRow("Variant", options.variant.label) { viewModel.goToVariant() }
            }
            LightBottomBar(
                items = listOf(
                    null,
                    LightBarButton.Text(text = "NEXT", onClick = { viewModel.goToOpponents() }),
                ),
            )
        }
    }

    // ----- variant picker (opened from the OPTIONS "Variant" row) -----

    @Composable
    private fun VariantStep(selected: NewGameViewModel.Variant) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Variant"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                NewGameViewModel.Variant.values().forEach { variant ->
                    SelectableRow(
                        label = variant.label,
                        selected = variant == selected,
                        onClick = { viewModel.selectVariant(variant) },
                    )
                }
            }
        }
    }

    // ----- step 2: opponent -----

    @Composable
    private fun OpponentStep(friends: NewGameViewModel.Friends) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Opponent"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ActionRow("Random opponent") { viewModel.seekRandom() }
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
                                FriendRow(user) { viewModel.challengeUser(user.username ?: user.displayName) }
                            }
                        }
                }
            }
        }
    }

    // ----- step 3: username entry -----

    @Composable
    private fun UsernameStep() {
        val textFieldState = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()
        LightTextInputEditor(
            title = "Opponent username",
            state = textFieldState,
            onSubmit = { viewModel.challengeUser(it) },
            onBack = { goBack() },
            keyboardOptionsFlow = keyboardOptions,
            // No submitIcon -> LightTextInputEditor renders a descriptive text button
            // (a bare icon here read as an ambiguous triangle).
            submitLabel = "CREATE GAME",
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    }

    // ----- step 4: confirmation -----

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

    @Composable
    private fun OptionRow(label: String, value: String, onClick: () -> Unit) {
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
            LightText(text = label, variant = LightTextVariant.Subheading, modifier = Modifier.weight(1f))
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
