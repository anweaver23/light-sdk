package com.andyweaver.chess.settings

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun toggleNotifications() = toggle(snapshot.value.notificationsEnabled, settings::setNotificationsEnabled)

    fun toggleConfirmMoves() = toggle(snapshot.value.confirmMoves, settings::setConfirmMoves)

    // v1: removed — may re-add
    // fun toggleShowTimeRemaining() = toggle(snapshot.value.showTimeRemaining, settings::setShowTimeRemaining)

    // v1: removed — may re-add
    // fun toggleShowLastMove() = toggle(snapshot.value.showLastMove, settings::setShowLastMove)

    private fun toggle(current: Boolean, setter: suspend (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            setter(!current)
        }
    }
}
