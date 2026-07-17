package com.andyweaver.chess.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Combined snapshot of all persisted chess settings, for callers (e.g. Compose
 * screens) that want to observe every value at once rather than several separate
 * flows.
 */
data class ChessSettingsSnapshot(
    val notificationsEnabled: Boolean = true,
    val confirmMoves: Boolean = true,
    val showLegalMoves: Boolean = true,
    // v1: removed — may re-add
    // val showTimeRemaining: Boolean = true,
    // val showLastMove: Boolean = true,
)

/**
 * A correspondence seek the user created that hasn't matched yet. Lichess offers no
 * API to list or cancel correspondence seeks, so we persist them locally to show in
 * the home "Pending" group. [id] is a local key only (not a server id). [side] is
 * "white" | "black" | "random". [variant] is a Lichess variant key ("standard",
 * "horde", …); defaulted so older persisted seeks (written before variants existed)
 * still decode.
 */
@Serializable
data class PendingSeek(
    val id: String,
    val days: Int,
    val rated: Boolean,
    val side: String,
    val createdAt: Long,
    val variant: String = "standard",
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
    val showLegalMoves: Flow<Boolean> = booleanFlow(Keys.SHOW_LEGAL_MOVES, default = true)
    // v1: removed — may re-add
    // val showTimeRemaining: Flow<Boolean> = booleanFlow(Keys.SHOW_TIME_REMAINING, default = true)
    // val showLastMove: Flow<Boolean> = booleanFlow(Keys.SHOW_LAST_MOVE, default = true)

    /** All settings combined into one snapshot flow. */
    val snapshot: Flow<ChessSettingsSnapshot> = combine(
        notificationsEnabled,
        confirmMoves,
        showLegalMoves,
    ) { notifications, confirm, showLegal ->
        ChessSettingsSnapshot(
            notificationsEnabled = notifications,
            confirmMoves = confirm,
            showLegalMoves = showLegal,
            // v1: removed — may re-add
            // showTimeRemaining = showTime,
            // showLastMove = showLast,
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) = setBoolean(Keys.NOTIFICATIONS_ENABLED, enabled)
    suspend fun setConfirmMoves(enabled: Boolean) = setBoolean(Keys.CONFIRM_MOVES, enabled)
    suspend fun setShowLegalMoves(enabled: Boolean) = setBoolean(Keys.SHOW_LEGAL_MOVES, enabled)
    // v1: removed — may re-add
    // suspend fun setShowTimeRemaining(enabled: Boolean) = setBoolean(Keys.SHOW_TIME_REMAINING, enabled)
    // suspend fun setShowLastMove(enabled: Boolean) = setBoolean(Keys.SHOW_LAST_MOVE, enabled)

    private fun booleanFlow(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[key] ?: default }

    private suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    // ----- pending correspondence seeks (local only; per account) -----

    /** Pending seeks for [account], newest first. */
    fun pendingSeeks(account: String): Flow<List<PendingSeek>> =
        dataStore.data.map { prefs ->
            (prefs[pendingSeeksKey(account)] ?: emptySet())
                .mapNotNull { decodeSeek(it) }
                .sortedByDescending { it.createdAt }
        }

    suspend fun addPendingSeek(account: String, seek: PendingSeek) {
        dataStore.edit { prefs ->
            val current = prefs[pendingSeeksKey(account)] ?: emptySet()
            prefs[pendingSeeksKey(account)] = current + json.encodeToString(seek)
        }
    }

    suspend fun removePendingSeek(account: String, id: String) {
        dataStore.edit { prefs ->
            val current = prefs[pendingSeeksKey(account)] ?: return@edit
            prefs[pendingSeeksKey(account)] = current.filterNot { decodeSeek(it)?.id == id }.toSet()
        }
    }

    /**
     * Clears seeks that have likely been matched. Since correspondence seeks aren't
     * listable/cancelable via the API, we infer a match when a game id appears that we
     * haven't seen for this account before: each newly-appeared game clears one (oldest)
     * pending seek. The first call for an account only records the baseline.
     */
    suspend fun reconcileSeeks(account: String, currentGameIds: Set<String>) {
        dataStore.edit { prefs ->
            val known = prefs[knownGamesKey(account)]
            if (known == null) {
                prefs[knownGamesKey(account)] = currentGameIds
                return@edit
            }
            val newlyAppeared = currentGameIds - known
            val seeks = prefs[pendingSeeksKey(account)] ?: emptySet()
            if (newlyAppeared.isNotEmpty() && seeks.isNotEmpty()) {
                val oldestIds = seeks.mapNotNull { decodeSeek(it) }
                    .sortedBy { it.createdAt }
                    .take(newlyAppeared.size)
                    .map { it.id }
                    .toSet()
                prefs[pendingSeeksKey(account)] = seeks.filterNot { decodeSeek(it)?.id in oldestIds }.toSet()
            }
            prefs[knownGamesKey(account)] = currentGameIds
        }
    }

    private fun decodeSeek(raw: String): PendingSeek? =
        runCatching { json.decodeFromString<PendingSeek>(raw) }.getOrNull()

    private fun pendingSeeksKey(account: String) = stringSetPreferencesKey("chess_pending_seeks_$account")
    private fun knownGamesKey(account: String) = stringSetPreferencesKey("chess_known_games_$account")

    private object Keys {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("chess_notifications_enabled")
        val CONFIRM_MOVES = booleanPreferencesKey("chess_confirm_moves")
        val SHOW_LEGAL_MOVES = booleanPreferencesKey("chess_show_legal_moves")
        // v1: removed — may re-add
        // val SHOW_TIME_REMAINING = booleanPreferencesKey("chess_show_time_remaining")
        // val SHOW_LAST_MOVE = booleanPreferencesKey("chess_show_last_move")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
