package com.andyweaver.chess.newgame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.andyweaver.chess.lichess.LichessActionResult
import com.andyweaver.chess.lichess.LichessApi
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Minimal "start on the deferred new-game flow": enter an opponent's Lichess
// username and send an UNRATED correspondence challenge (fixed 2 days/turn for
// now). The full flow (seek vs challenge, time-control picker) is still deferred;
// this is a working, testable placeholder in LP style. Once the opponent accepts,
// the game appears in the home list on the next fetch.
private const val DAYS_PER_TURN = 2

class NewGameViewModel(private val api: LichessApi) : LightViewModel<Unit>() {

    sealed class Status {
        object Idle : Status()
        object Sending : Status()
        object Sent : Status()
        data class Error(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    fun submit(opponent: CharSequence) {
        val name = opponent.trim().toString()
        if (name.isEmpty() || _status.value is Status.Sending) return
        _status.value = Status.Sending
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = when (val result = api.createCorrespondenceChallenge(name, DAYS_PER_TURN)) {
                is LichessActionResult.Success -> Status.Sent
                is LichessActionResult.Failure -> Status.Error(result.error)
            }
        }
    }

    fun clearError() {
        _status.value = Status.Idle
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}

class NewGameScreen(
    sealedActivity: SealedLightActivity,
    private val token: String,
) : LightScreen<Unit, NewGameViewModel>(sealedActivity) {

    override val viewModelClass: Class<NewGameViewModel>
        get() = NewGameViewModel::class.java

    override fun createViewModel(): NewGameViewModel = NewGameViewModel(LichessApi(token))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val status by viewModel.status.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()

        // Challenge sent → return to the list (the game shows up once accepted).
        LaunchedEffect(status) {
            if (status is NewGameViewModel.Status.Sent) goBack()
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTextInputEditor(
                    title = "Opponent username",
                    state = textFieldState,
                    onSubmit = { viewModel.submit(it) },
                    onBack = { goBack() },
                    keyboardOptionsFlow = keyboardOptions,
                    submitLabel = "CHALLENGE",
                    submitIcon = LightIcons.ACCEPT,
                    singleLine = true,
                    modifier = Modifier.fillMaxSize(),
                )

                (status as? NewGameViewModel.Status.Error)?.let { error ->
                    LightFullscreenModal(
                        message = error.message,
                        onClose = { viewModel.clearError() },
                    )
                }
            }
        }
    }
}
