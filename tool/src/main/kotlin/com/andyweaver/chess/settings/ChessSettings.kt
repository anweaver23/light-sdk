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
    val dragAndDrop: Boolean = false,
    val moveStepSpeed: MoveStepSpeed = MoveStepSpeed.DEFAULT,
    // v1: removed — may re-add
    // val showTimeRemaining: Boolean = true,
    // val showLastMove: Boolean = true,
)

/** Repeat interval (ms per move) for the "Slow" move-step preset. */
const val MOVE_STEP_INTERVAL_SLOW_MS = 500L

/** Repeat interval (ms per move) for the "Normal" (default) move-step preset. */
const val MOVE_STEP_INTERVAL_NORMAL_MS = 300L

/** Repeat interval (ms per move) for the "Fast" move-step preset. */
const val MOVE_STEP_INTERVAL_FAST_MS = 150L

/**
 * How fast holding down a browse arrow (board / review bottom bars) repeat-steps through
 * the game's moves. [intervalMs] is the delay between successive steps while held.
 *
 * Persisted by the stable string [key], NOT by ordinal or name, so presets can be added,
 * renamed or reordered later without a saved value decoding as the wrong speed. An
 * unrecognised stored key falls back to [DEFAULT] rather than throwing (same tolerance as
 * the rest of this file's decoders).
 */
enum class MoveStepSpeed(val key: String, val label: String, val intervalMs: Long) {
    SLOW("slow", "Slow", MOVE_STEP_INTERVAL_SLOW_MS),
    NORMAL("normal", "Normal", MOVE_STEP_INTERVAL_NORMAL_MS),
    FAST("fast", "Fast", MOVE_STEP_INTERVAL_FAST_MS),
    ;

    /** The next preset in the tap-to-cycle Settings row (wraps around). */
    val next: MoveStepSpeed get() = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = NORMAL

        fun fromKey(key: String?): MoveStepSpeed = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

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
 * A game THIS user aborted themselves (via the board menu's "Abort game"), recorded so
 * [ChessSettings.reconcileSeeks] can tell the two abort cases apart.
 *
 * Lichess re-queues the original correspondence seek when a game made from it is aborted
 * BY THE OPPONENT, but not when you abort it yourself. Both look identical from the
 * outside — the game simply vanishes from `/api/account/playing` having never reached two
 * plies — so without this record the "revive the pending seek" heuristic fires on your own
 * aborts too, leaving a phantom pending-seek row on the home screen forever.
 *
 * [abortedAt] is used only for pruning (age + LRU eviction); see
 * [ChessSettings.MAX_SELF_ABORTS] and [ChessSettings.SELF_ABORT_TTL_MS].
 */
@Serializable
data class SelfAbort(
    val gameId: String,
    val abortedAt: Long = 0,
)

/**
 * The mutable state [reconcileSeekState] reads and rewrites: everything the seek-matching
 * heuristic touches, pulled out of DataStore so the logic itself is a pure function and can
 * be unit-tested without Android (the pattern session 10b used for the animation helpers).
 *
 * [knownGames] is null before the first observation for an account — there is nothing to
 * diff against yet, so that call only records a baseline.
 */
internal data class SeekReconcileState(
    val pendingSeeks: List<PendingSeek> = emptyList(),
    val matches: List<SeekMatch> = emptyList(),
    val knownGames: Set<String>? = null,
    val selfAborts: List<SelfAbort> = emptyList(),
)

/**
 * Pure core of [ChessSettings.reconcileSeeks] — see that function's KDoc for the full
 * heuristic. [currentGames] maps each currently-ongoing game id to its ply count (or null
 * if undeterminable). [now] and [newSeekId] are injected so revival is deterministic in
 * tests.
 *
 * Self-abort handling: a match whose game disappeared under two plies is normally revived
 * (Lichess re-queued the seek). If the user aborted that game themselves, Lichess did NOT
 * re-queue anything, so the match is dropped silently instead. Either way the self-abort
 * record has served its purpose once the game is gone, so it is consumed.
 */
internal fun reconcileSeekState(
    state: SeekReconcileState,
    currentGames: Map<String, Int?>,
    now: Long,
    maxSelfAborts: Int,
    selfAbortTtlMs: Long,
    newSeekId: () -> String,
): SeekReconcileState {
    val currentGameIds = currentGames.keys.toSet()
    val known = state.knownGames
        ?: return state.copy(knownGames = currentGameIds, selfAborts = pruneSelfAborts(state.selfAborts, now, maxSelfAborts, selfAbortTtlMs))

    val newlyAppeared = currentGameIds - known
    val disappeared = known - currentGameIds

    var seeks = state.pendingSeeks
    // Refresh the last-observed ply for matches whose game is still around.
    var matches = state.matches.map { m ->
        val ply = currentGames[m.gameId]
        if (ply != null) m.copy(lastObservedPly = maxOf(m.lastObservedPly, ply)) else m
    }

    val selfAbortedIds = state.selfAborts.map { it.gameId }.toSet()

    // Matches whose game just disappeared: revive the seek if it never really started AND
    // the abort wasn't ours; otherwise drop the match — it finished for real (or we aborted).
    for (m in matches.filter { it.gameId in disappeared }) {
        if (m.lastObservedPly < 2 && m.gameId !in selfAbortedIds) {
            seeks = seeks + m.seek.copy(id = newSeekId(), createdAt = now)
        }
    }
    matches = matches.filterNot { it.gameId in disappeared }

    // Newly-appeared games consume the oldest pending seeks — but instead of discarding
    // them, remember the pairing as a tracked match (see reconcileSeeks' KDoc). Pairing
    // order is arbitrary (Lichess gives us no way to know which seek matched which game)
    // but deterministic.
    if (newlyAppeared.isNotEmpty() && seeks.isNotEmpty()) {
        val oldestSeeks = seeks.sortedBy { it.createdAt }
        val newGameIds = newlyAppeared.sorted()
        val pairCount = minOf(oldestSeeks.size, newGameIds.size)
        for (i in 0 until pairCount) {
            val seek = oldestSeeks[i]
            val gameId = newGameIds[i]
            matches = matches + SeekMatch(gameId, seek, currentGames[gameId] ?: 0)
            seeks = seeks.filterNot { it.id == seek.id }
        }
    }

    // A self-abort record is only ever needed until its game leaves the ongoing list, so
    // drop the consumed ones; age/LRU pruning then bounds anything that never resolved
    // (e.g. the abort call failed, or the record outlived the app's memory of the game).
    val remainingAborts = state.selfAborts.filterNot { it.gameId in disappeared }
    return SeekReconcileState(
        pendingSeeks = seeks,
        matches = matches,
        knownGames = currentGameIds,
        selfAborts = pruneSelfAborts(remainingAborts, now, maxSelfAborts, selfAbortTtlMs),
    )
}

internal fun pruneSelfAborts(
    aborts: List<SelfAbort>,
    now: Long,
    maxSelfAborts: Int,
    selfAbortTtlMs: Long,
): List<SelfAbort> = aborts
    .filter { now - it.abortedAt < selfAbortTtlMs }
    .sortedByDescending { it.abortedAt }
    .take(maxSelfAborts)

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

    /**
     * Drag a piece to move it, in addition to tap-to-select-then-tap-destination.
     *
     * Defaults to OFF: tapping is always available and is the gentler interaction on a
     * small screen, so dragging is opt-in. Turning it on never disables tapping — the two
     * coexist, and a drag that doesn't move past touch slop is just a tap.
     */
    val dragAndDrop: Flow<Boolean> = booleanFlow(Keys.DRAG_AND_DROP, default = false)

    /** How fast a held-down browse arrow repeat-steps through moves. */
    val moveStepSpeed: Flow<MoveStepSpeed> =
        dataStore.data.map { prefs -> MoveStepSpeed.fromKey(prefs[Keys.MOVE_STEP_SPEED]) }
    // v1: removed — may re-add
    // val showTimeRemaining: Flow<Boolean> = booleanFlow(Keys.SHOW_TIME_REMAINING, default = true)
    // val showLastMove: Flow<Boolean> = booleanFlow(Keys.SHOW_LAST_MOVE, default = true)

    /** All settings combined into one snapshot flow. */
    val snapshot: Flow<ChessSettingsSnapshot> = combine(
        notificationsEnabled,
        confirmMoves,
        showLegalMoves,
        dragAndDrop,
        moveStepSpeed,
    ) { notifications, confirm, showLegal, drag, stepSpeed ->
        ChessSettingsSnapshot(
            notificationsEnabled = notifications,
            confirmMoves = confirm,
            showLegalMoves = showLegal,
            dragAndDrop = drag,
            moveStepSpeed = stepSpeed,
            // v1: removed — may re-add
            // showTimeRemaining = showTime,
            // showLastMove = showLast,
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) = setBoolean(Keys.NOTIFICATIONS_ENABLED, enabled)
    suspend fun setConfirmMoves(enabled: Boolean) = setBoolean(Keys.CONFIRM_MOVES, enabled)
    suspend fun setShowLegalMoves(enabled: Boolean) = setBoolean(Keys.SHOW_LEGAL_MOVES, enabled)
    suspend fun setDragAndDrop(enabled: Boolean) = setBoolean(Keys.DRAG_AND_DROP, enabled)

    suspend fun setMoveStepSpeed(speed: MoveStepSpeed) {
        dataStore.edit { prefs -> prefs[Keys.MOVE_STEP_SPEED] = speed.key }
    }
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
     * the game-id-keyed stores (chat read markers and self-abort records) — those aren't
     * partitioned by account, so they would otherwise linger and apply to another
     * account's games.
     */
    suspend fun clearSession(username: String?) {
        dataStore.edit { prefs ->
            prefs.remove(Keys.AUTH_TOKEN)
            prefs.remove(Keys.AUTH_USERNAME)
            prefs.remove(Keys.CHAT_READ_MARKS)
            prefs.remove(Keys.SELF_ABORTS)
            prefs.remove(Keys.DECLINED_DRAW_CLAIMS)
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
     * A game the USER aborted themselves is excluded from revival: Lichess only re-queues
     * the seek when the OPPONENT aborts. See [recordSelfAbort] and [SelfAbort].
     *
     * The first call for an account only records the baseline (nothing to diff against
     * yet), matching the original behavior.
     *
     * The logic itself lives in the pure [reconcileSeekState]; this function is only the
     * DataStore decode/encode shell around it.
     */
    suspend fun reconcileSeeks(account: String, currentGames: Map<String, Int?>) {
        dataStore.edit { prefs ->
            val before = SeekReconcileState(
                pendingSeeks = (prefs[pendingSeeksKey(account)] ?: emptySet()).mapNotNull { decodeSeek(it) },
                matches = (prefs[seekMatchesKey(account)] ?: emptySet()).mapNotNull { decodeMatch(it) },
                knownGames = prefs[knownGamesKey(account)],
                selfAborts = readSelfAborts(prefs),
            )
            val after = reconcileSeekState(
                state = before,
                currentGames = currentGames,
                now = System.currentTimeMillis(),
                maxSelfAborts = MAX_SELF_ABORTS,
                selfAbortTtlMs = SELF_ABORT_TTL_MS,
                newSeekId = { UUID.randomUUID().toString() },
            )
            prefs[pendingSeeksKey(account)] = after.pendingSeeks.map { json.encodeToString(it) }.toSet()
            prefs[seekMatchesKey(account)] = after.matches.map { json.encodeToString(it) }.toSet()
            prefs[knownGamesKey(account)] = after.knownGames ?: emptySet()
            prefs[Keys.SELF_ABORTS] = after.selfAborts.map { json.encodeToString(it) }.toSet()
        }
    }

    // ----- games the user aborted themselves -----

    /**
     * Records that the user aborted [gameId] themselves, so [reconcileSeeks] does NOT
     * revive the pending seek it came from (Lichess only re-queues a correspondence seek
     * when the OPPONENT aborts).
     *
     * Call this BEFORE issuing `POST /api/board/game/{id}/abort`, not after: the ~20s home
     * poll can observe the game's disappearance at any moment once the server has processed
     * the abort, including before the HTTP call returns. Writing the record first closes
     * that race in the safe direction — the worst case is a record for an abort that then
     * failed, which [forgetSelfAbort] undoes and which prunes itself regardless.
     *
     * Idempotent (re-recording the same id just refreshes its timestamp). Bounded: at most
     * [MAX_SELF_ABORTS] records, and each expires after [SELF_ABORT_TTL_MS]; records are
     * also consumed as soon as their game leaves the ongoing-games list. Game ids are
     * unique on Lichess and never reused, so a stale record can only ever affect the game
     * it names.
     */
    suspend fun recordSelfAbort(gameId: String) {
        dataStore.edit { prefs ->
            val now = System.currentTimeMillis()
            val updated = pruneSelfAborts(
                aborts = readSelfAborts(prefs).filterNot { it.gameId == gameId } + SelfAbort(gameId, now),
                now = now,
                maxSelfAborts = MAX_SELF_ABORTS,
                selfAbortTtlMs = SELF_ABORT_TTL_MS,
            )
            prefs[Keys.SELF_ABORTS] = updated.map { json.encodeToString(it) }.toSet()
        }
    }

    /**
     * Undoes a [recordSelfAbort] — call this if the abort request FAILED, so a later
     * genuine opponent abort of the same game can still revive its seek.
     */
    suspend fun forgetSelfAbort(gameId: String) {
        dataStore.edit { prefs ->
            val remaining = readSelfAborts(prefs).filterNot { it.gameId == gameId }
            prefs[Keys.SELF_ABORTS] = remaining.map { json.encodeToString(it) }.toSet()
        }
    }

    private fun readSelfAborts(prefs: Preferences): List<SelfAbort> =
        (prefs[Keys.SELF_ABORTS] ?: emptySet()).mapNotNull { decodeSelfAbort(it) }

    private fun decodeSelfAbort(raw: String): SelfAbort? =
        runCatching { json.decodeFromString<SelfAbort>(raw) }.getOrNull()

    // ----- declined threefold-repetition claims -----

    /**
     * Games where the user has dismissed the "Claim draw" prompt. Once dismissed, the
     * prompt never returns for that game.
     *
     * This has to be PERSISTED rather than kept in the view model, because the prompt is
     * deliberately blocking — it replaces the top bar and consumes the back press, so the
     * game can't be left without answering. An in-memory flag would be lost the moment the
     * board screen is destroyed, and reopening the game would re-block the user on a prompt
     * they had already declined. Draws can still be claimed from the menu afterwards.
     *
     * Bounded the same way as [SelfAbort]: newest [MAX_DECLINED_CLAIMS] kept, and cleared
     * with the session.
     */
    fun declinedDrawClaim(gameId: String): Flow<Boolean> =
        dataStore.data.map { prefs -> gameId in (prefs[Keys.DECLINED_DRAW_CLAIMS] ?: emptySet()) }

    /** Records that the claim prompt for [gameId] was dismissed; it will not be shown again. */
    suspend fun recordDeclinedDrawClaim(gameId: String) {
        dataStore.edit { prefs ->
            val existing = (prefs[Keys.DECLINED_DRAW_CLAIMS] ?: emptySet())
            // A LinkedHashSet keeps insertion order so "drop the oldest" is meaningful;
            // re-adding an id moves it to the newest end rather than duplicating it.
            val updated = LinkedHashSet(existing.filterNot { it == gameId })
            updated.add(gameId)
            prefs[Keys.DECLINED_DRAW_CLAIMS] = updated.toList().takeLast(MAX_DECLINED_CLAIMS).toSet()
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
        val DRAG_AND_DROP = booleanPreferencesKey("chess_drag_and_drop")
        val MOVE_STEP_SPEED = stringPreferencesKey("chess_move_step_speed")
        val SELF_ABORTS = stringSetPreferencesKey("chess_self_aborts")
        val AUTH_TOKEN = stringPreferencesKey("chess_auth_token")
        val AUTH_USERNAME = stringPreferencesKey("chess_auth_username")
        val CHAT_READ_MARKS = stringSetPreferencesKey("chess_chat_read_marks")
        val DECLINED_DRAW_CLAIMS = stringSetPreferencesKey("chess_declined_draw_claims")
        // v1: removed — may re-add
        // val SHOW_TIME_REMAINING = booleanPreferencesKey("chess_show_time_remaining")
        // val SHOW_LAST_MOVE = booleanPreferencesKey("chess_show_last_move")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Cap on stored chat read markers; the least-recently-touched are evicted. */
        const val MAX_CHAT_READ_MARKS = 200

        /** Cap on stored self-abort records; the oldest are evicted. */
        const val MAX_SELF_ABORTS = 50

        /** Cap on stored declined-claim game ids; the oldest are evicted. */
        const val MAX_DECLINED_CLAIMS = 100

        /**
         * How long a self-abort record survives. It normally only needs to outlive one home
         * refresh (the game disappears and the record is consumed), so this is generous
         * belt-and-braces for a record whose game somehow never disappears.
         */
        const val SELF_ABORT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
