package com.andyweaver.chess.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Combined snapshot of all persisted chess settings, for callers (e.g. Compose
 * screens) that want to observe every value at once rather than four separate
 * flows.
 */
data class ChessSettingsSnapshot(
    val notificationsEnabled: Boolean = true,
    val confirmMoves: Boolean = true,
    val showTimeRemaining: Boolean = true,
    val showLastMove: Boolean = true,
)

/**
 * DataStore(Preferences)-backed repository for chess-app settings. Mirrors the
 * pattern used by [com.thelightphone.sdk.LightPushManager] and the weather
 * example's `WeatherViewModel` (a `DataStore<Preferences>` obtained from
 * `lightContext.dataStore`, read via `Flow`, written via suspend `edit {}`).
 *
 * Construct with the tool's shared DataStore, e.g. from a [com.thelightphone.sdk.LightScreen]:
 * ```
 * ChessSettings(lightContext.dataStore)
 * ```
 */
class ChessSettings(private val dataStore: DataStore<Preferences>) {

    val notificationsEnabled: Flow<Boolean> = booleanFlow(Keys.NOTIFICATIONS_ENABLED, default = true)
    val confirmMoves: Flow<Boolean> = booleanFlow(Keys.CONFIRM_MOVES, default = true)
    val showTimeRemaining: Flow<Boolean> = booleanFlow(Keys.SHOW_TIME_REMAINING, default = true)
    val showLastMove: Flow<Boolean> = booleanFlow(Keys.SHOW_LAST_MOVE, default = true)

    /** All four settings combined into one snapshot flow. */
    val snapshot: Flow<ChessSettingsSnapshot> = combine(
        notificationsEnabled,
        confirmMoves,
        showTimeRemaining,
        showLastMove,
    ) { notifications, confirm, showTime, showLast ->
        ChessSettingsSnapshot(
            notificationsEnabled = notifications,
            confirmMoves = confirm,
            showTimeRemaining = showTime,
            showLastMove = showLast,
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) = setBoolean(Keys.NOTIFICATIONS_ENABLED, enabled)
    suspend fun setConfirmMoves(enabled: Boolean) = setBoolean(Keys.CONFIRM_MOVES, enabled)
    suspend fun setShowTimeRemaining(enabled: Boolean) = setBoolean(Keys.SHOW_TIME_REMAINING, enabled)
    suspend fun setShowLastMove(enabled: Boolean) = setBoolean(Keys.SHOW_LAST_MOVE, enabled)

    private fun booleanFlow(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[key] ?: default }

    private suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    private object Keys {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("chess_notifications_enabled")
        val CONFIRM_MOVES = booleanPreferencesKey("chess_confirm_moves")
        val SHOW_TIME_REMAINING = booleanPreferencesKey("chess_show_time_remaining")
        val SHOW_LAST_MOVE = booleanPreferencesKey("chess_show_last_move")
    }
}
