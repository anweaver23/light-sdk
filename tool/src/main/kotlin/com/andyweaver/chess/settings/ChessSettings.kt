package com.andyweaver.chess.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

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
 * The logged-in Lichess session: the personal-access [token] and the [username] it
 * resolved to (via `GET /api/account`). Null when logged out.
 */
data class Session(val token: String, val username: String)

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
 * Tracks that a newly-appeared game id was matched (by [reconcileSeeks]'s heuristic)
 * to a specific consumed [seek]. Kept around — instead of discarding the seek
 * outright — so that if [gameId] later disappears from the ongoing-games list while
 * it was still early (an abort before either side really moved, which Lichess
 * re-queues the original seek for), we can revive a fresh [PendingSeek] with the same
 * terms. [lastObservedPly] is the highest ply count we've seen for [gameId] across
 * polls (see [reconcileSeeks]); once a game has reached real moves (>= 2 plies) and
 * then disappears, it was a legitimately finished game, not a re-queued seek, so no
 * revival happens.
 */
@Serializable
data class SeekMatch(
    val gameId: String,
    val seek: PendingSeek,
    val lastObservedPly: Int = 0,
)

/**
 * How much of one game's chat the user has already seen: [count] is a HIGH-WATER MARK
 * over the message list returned by `GET /api/board/game/{gameId}/chat` (plus anything
 * streamed on top of it), i.e. "the first [count] messages are read".
 *
 * An integer index is used rather than a timestamp because the chat endpoint returns an
 * ordered, append-only list and carries NO timestamps at all — there is nothing else to
 * compare against. [updatedAt] is only used to evict the oldest markers once the store
 * grows past [ChessSettings.MAX_CHAT_READ_MARKS].
 */
@Serializable
data class ChatReadMark(
    val gameId: String,
    val count: Int,
    val updatedAt: Long = 0,
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

    // ----- login session (the user's Lichess personal-access token) -----
    //
    // NOTE: stored in plaintext DataStore. The Light SDK's allowed-dependency list has no
    // EncryptedSharedPreferences / androidx.security, and those APIs need a raw Context
    // (which the plugin bans), so plaintext is the only sanctioned store. Mitigations:
    // Android's app sandbox + at-rest disk encryption; the token is a scoped, revocable
    // Lichess PAT (revoke at lichess.org/account/security); logout deletes it.

    /** The logged-in session, or null when logged out. */
    val session: Flow<Session?> = dataStore.data.map { prefs ->
        val token = prefs[Keys.AUTH_TOKEN]
        val username = prefs[Keys.AUTH_USERNAME]
        if (token.isNullOrBlank() || username.isNullOrBlank()) null else Session(token, username)
    }

    suspend fun saveSession(token: String, username: String) {
        dataStore.edit { prefs ->
            prefs[Keys.AUTH_TOKEN] = token
            prefs[Keys.AUTH_USERNAME] = username
        }
    }

    /**
     * Logs out: clears the token/username, that user's per-account seek partitions, and
     * the per-game chat read markers (which are keyed by game id, not by account, so they
     * would otherwise linger and mis-mark another account's games as already read).
     */
    suspend fun clearSession(username: String?) {
        dataStore.edit { prefs ->
            prefs.remove(Keys.AUTH_TOKEN)
            prefs.remove(Keys.AUTH_USERNAME)
            prefs.remove(Keys.CHAT_READ_MARKS)
            if (username != null) {
                prefs.remove(pendingSeeksKey(username))
                prefs.remove(knownGamesKey(username))
                prefs.remove(seekMatchesKey(username))
            }
        }
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
     * Clears seeks that have likely been matched, and revives ones whose match turned
     * out to be an early abort. Since correspondence seeks aren't listable/cancelable
     * via the API, we infer a match when a game id appears that we haven't seen for
     * this account before: each newly-appeared game consumes one (oldest) pending
     * seek — but instead of discarding that seek, we remember the (gameId, seek)
     * pairing as a [SeekMatch] so a later disappearance can be reasoned about.
     *
     * [currentGames] maps each currently-ongoing game id to its ply count (moves
     * played so far), or null if it couldn't be determined (e.g. an unparseable FEN);
     * see `HomeScreenViewModel`'s `plyCount()` helper (derived from the FEN's active
     * color + fullmove number, no extra API call).
     *
     * Known limitation this specifically handles: a seek can match into a game that
     * the opponent then ABORTS before either side has moved (Lichess allows this).
     * The aborted game vanishes from the ongoing-games list, and Lichess silently
     * re-queues the original seek, later producing an unrelated new game id. Without
     * tracking (gameId -> seek) pairs, that would either double-clear a marker that
     * was never re-added, or (worse) require guessing — so we track the pairing and
     * only permanently drop it once we've observed >= 2 plies (mirrors
     * `BoardViewModel.canAbort`'s own "< 2 plies" abort-eligibility threshold) at some
     * point before the game disappeared. If it disappears having stayed under that
     * threshold, we treat it as an abort and restore a fresh [PendingSeek] with the
     * same terms (new local id/timestamp — the original's id was never a server id).
     * A game reaching >= 2 plies and then disappearing is a legitimately finished game
     * (resign/checkmate/draw), so its match record is just dropped, no revival.
     *
     * The first call for an account only records the baseline (nothing to diff against
     * yet), matching the original behavior.
     */
    suspend fun reconcileSeeks(account: String, currentGames: Map<String, Int?>) {
        dataStore.edit { prefs ->
            val currentGameIds = currentGames.keys
            val known = prefs[knownGamesKey(account)]
            if (known == null) {
                prefs[knownGamesKey(account)] = currentGameIds
                return@edit
            }
            val newlyAppeared = currentGameIds - known
            val disappeared = known - currentGameIds

            var seeks = prefs[pendingSeeksKey(account)] ?: emptySet()
            var matches = (prefs[seekMatchesKey(account)] ?: emptySet()).mapNotNull { decodeMatch(it) }

            // Refresh the last-observed ply for matches whose game is still around.
            matches = matches.map { m ->
                val ply = currentGames[m.gameId]
                if (ply != null) m.copy(lastObservedPly = maxOf(m.lastObservedPly, ply)) else m
            }

            // Matches whose game just disappeared: revive the seek if it never really
            // started (an early abort), otherwise drop the match — it finished for real.
            val goneMatches = matches.filter { it.gameId in disappeared }
            if (goneMatches.isNotEmpty()) {
                for (m in goneMatches) {
                    if (m.lastObservedPly < 2) {
                        val revived = m.seek.copy(id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis())
                        seeks = seeks + json.encodeToString(revived)
                    }
                }
                matches = matches.filterNot { it.gameId in disappeared }
            }

            // Newly-appeared games consume the oldest pending seeks — but instead of
            // discarding them, remember the pairing as a tracked match (see above).
            // Pairing order is arbitrary (Lichess gives us no way to know which seek
            // matched which game) but deterministic, matching the original heuristic.
            if (newlyAppeared.isNotEmpty() && seeks.isNotEmpty()) {
                val oldestSeeks = seeks.mapNotNull { decodeSeek(it) }.sortedBy { it.createdAt }
                val newGameIds = newlyAppeared.sorted()
                val pairCount = minOf(oldestSeeks.size, newGameIds.size)
                for (i in 0 until pairCount) {
                    val seek = oldestSeeks[i]
                    val gameId = newGameIds[i]
                    matches = matches + SeekMatch(
                        gameId = gameId,
                        seek = seek,
                        lastObservedPly = currentGames[gameId] ?: 0,
                    )
                    seeks = seeks.filterNot { decodeSeek(it)?.id == seek.id }.toSet()
                }
            }

            prefs[pendingSeeksKey(account)] = seeks
            prefs[seekMatchesKey(account)] = matches.map { json.encodeToString(it) }.toSet()
            prefs[knownGamesKey(account)] = currentGameIds
        }
    }

    // ----- per-game chat read markers (drives the persistent unread badge) -----

    /**
     * How many of [gameId]'s chat messages have already been read (0 if the chat was
     * never opened). The board screen counts opponent messages positioned AFTER this
     * index as unread, which is what makes the badge survive an app restart — an
     * in-memory counter would reset to zero every launch and could never reflect
     * messages that arrived while the app was closed.
     */
    fun chatReadCount(gameId: String): Flow<Int> =
        dataStore.data.map { prefs -> readChatMarks(prefs).firstOrNull { it.gameId == gameId }?.count ?: 0 }

    /**
     * Records that the first [count] messages of [gameId] are read. Monotonic — a lower
     * [count] than the stored one is ignored, so a chat history that momentarily comes
     * back short (a failed/partial fetch) can never resurrect already-read messages.
     *
     * Called only when the chat panel is opened or closed, never per streamed message,
     * to keep DataStore writes rare.
     *
     * Pruning: rather than reconciling against the ongoing-games list (which would
     * couple this to the home screen's refresh), the store simply keeps the
     * [MAX_CHAT_READ_MARKS] most recently touched games and drops the rest. Evicting a
     * marker for a long-untouched game is harmless — worst case its chat shows as
     * unread once more.
     */
    suspend fun setChatReadCount(gameId: String, count: Int) {
        dataStore.edit { prefs ->
            val marks = readChatMarks(prefs)
            val existing = marks.firstOrNull { it.gameId == gameId }
            if (existing != null && existing.count >= count) return@edit
            val updated = (marks.filterNot { it.gameId == gameId } + ChatReadMark(gameId, count, System.currentTimeMillis()))
                .sortedByDescending { it.updatedAt }
                .take(MAX_CHAT_READ_MARKS)
            prefs[Keys.CHAT_READ_MARKS] = updated.map { json.encodeToString(it) }.toSet()
        }
    }

    private fun readChatMarks(prefs: Preferences): List<ChatReadMark> =
        (prefs[Keys.CHAT_READ_MARKS] ?: emptySet()).mapNotNull { decodeChatMark(it) }

    private fun decodeChatMark(raw: String): ChatReadMark? =
        runCatching { json.decodeFromString<ChatReadMark>(raw) }.getOrNull()

    private fun decodeSeek(raw: String): PendingSeek? =
        runCatching { json.decodeFromString<PendingSeek>(raw) }.getOrNull()

    private fun decodeMatch(raw: String): SeekMatch? =
        runCatching { json.decodeFromString<SeekMatch>(raw) }.getOrNull()

    private fun pendingSeeksKey(account: String) = stringSetPreferencesKey("chess_pending_seeks_$account")
    private fun knownGamesKey(account: String) = stringSetPreferencesKey("chess_known_games_$account")
    private fun seekMatchesKey(account: String) = stringSetPreferencesKey("chess_seek_matches_$account")

    private object Keys {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("chess_notifications_enabled")
        val CONFIRM_MOVES = booleanPreferencesKey("chess_confirm_moves")
        val SHOW_LEGAL_MOVES = booleanPreferencesKey("chess_show_legal_moves")
        val AUTH_TOKEN = stringPreferencesKey("chess_auth_token")
        val AUTH_USERNAME = stringPreferencesKey("chess_auth_username")
        val CHAT_READ_MARKS = stringSetPreferencesKey("chess_chat_read_marks")
        // v1: removed — may re-add
        // val SHOW_TIME_REMAINING = booleanPreferencesKey("chess_show_time_remaining")
        // val SHOW_LAST_MOVE = booleanPreferencesKey("chess_show_last_move")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Cap on stored chat read markers; the least-recently-touched are evicted. */
        const val MAX_CHAT_READ_MARKS = 200
    }
}
