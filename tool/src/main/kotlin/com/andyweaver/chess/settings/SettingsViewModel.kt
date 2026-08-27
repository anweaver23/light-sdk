package com.andyweaver.chess.settings

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs [SettingsScreen]. Reads the persisted chess settings from
 * [ChessSettings] as a single [StateFlow] snapshot, and flips one at a time
 * (persisting via [ChessSettings]'s suspend setters) when a row is tapped.
 */
class SettingsViewModel(private val settings: ChessSettings) : LightViewModel<Unit>() {

    val snapshot: StateFlow<ChessSettingsSnapshot> = settings.snapshot.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChessSettingsSnapshot(),
    )

    // v1: notifications toggle removed (push needs the unbuilt relay) — re-add with it.
    // fun toggleNotifications() = toggle(snapshot.value.notificationsEnabled, settings::setNotificationsEnabled)

    fun toggleConfirmMoves() = toggle(snapshot.value.confirmMoves, settings::setConfirmMoves)

    fun toggleShowLegalMoves() = toggle(snapshot.value.showLegalMoves, settings::setShowLegalMoves)
    fun toggleDragAndDrop() = toggle(snapshot.value.dragAndDrop, settings::setDragAndDrop)
    fun toggleScrubBar() = toggle(snapshot.value.scrubBar, settings::setScrubBar)

    /**
     * Advances the "Move step speed" row to the next preset (Slow → Normal → Fast → Slow),
     * matching the tap-to-cycle option rows in the new-game flow.
     */
    fun cycleMoveStepSpeed() {
        val next = snapshot.value.moveStepSpeed.next
        viewModelScope.launch(Dispatchers.IO) {
            settings.setMoveStepSpeed(next)
        }
    }

    // v1: removed — may re-add
    // fun toggleShowTimeRemaining() = toggle(snapshot.value.showTimeRemaining, settings::setShowTimeRemaining)

    // v1: removed — may re-add
    // fun toggleShowLastMove() = toggle(snapshot.value.showLastMove, settings::setShowLastMove)

    private fun toggle(current: Boolean, setter: suspend (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            setter(!current)
        }
    }

    // Whether the "log out?" confirmation is showing.
    private val _confirmingLogOut = MutableStateFlow(false)
    val confirmingLogOut: StateFlow<Boolean> = _confirmingLogOut

    fun requestLogOut() { _confirmingLogOut.value = true }
    fun cancelLogOut() { _confirmingLogOut.value = false }

    /**
     * Log out: clear the stored token/username (and that user's local seek partitions),
     * then invoke [onComplete] (used to pop back to home, which now shows the login gate).
     *
     * The clear runs in a [NonCancellable] block and we navigate back only AFTER it has
     * persisted. Popping the settings screen destroys this ViewModel and cancels its
     * [viewModelScope], so calling `goBack()` up front (as the caller used to) would kill
     * the clear before it ran — leaving the user still logged in.
     */
    fun confirmLogOut(onComplete: () -> Unit) {
        _confirmingLogOut.value = false
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                val username = settings.session.first()?.username
                settings.clearSession(username)
            }
            onComplete()
        }
    }
}
