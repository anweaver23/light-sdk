package com.andyweaver.chess.lichess

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CORRESPONDENCE_SPEED = "correspondence"
private const val BASE_URL = "https://lichess.org"

/** Connect timeout for every request, streaming or not — a TCP+TLS handshake is quick or broken. */
private const val REST_CONNECT_TIMEOUT_MS = 15_000L

/** Whole-call and read timeout for ordinary (small, bounded) REST calls. */
private const val REST_REQUEST_TIMEOUT_MS = 15_000L

/**
 * Read timeout for a long-lived NDJSON stream. Lichess sends a keep-alive newline roughly
 * every 6 seconds, so this is ~10 keep-alives of slack — enough to ride out mobile jitter
 * while still noticing a genuinely dead socket within a minute.
 */
private const val STREAM_SOCKET_TIMEOUT_MS = 60_000L

/**
 * Whole-call timeout for the bulk NDJSON reads (games export, following list). These use
 * the streaming client for its generous read timeout, but they DO terminate, so they get a
 * finite per-request cap rather than the streams' infinite one.
 */
private const val BULK_REQUEST_TIMEOUT_MS = 60_000L

@Serializable
data class LichessOpponent(
    val id: String? = null,
    val username: String,
    val rating: Int? = null,
)

@Serializable
data class LichessGame(
    val gameId: String,
    val fullId: String,
    val color: String,
    val fen: String,
    val lastMove: String? = null,
    val isMyTurn: Boolean = false,
    val opponent: LichessOpponent,
    val speed: String,
    val secondsLeft: Int? = null,
    val variant: BoardVariant = BoardVariant(),
)

@Serializable
private data class LichessNowPlayingResponse(
    val nowPlaying: List<LichessGame> = emptyList(),
)

/** A player in a board game stream (`white`/`black` in a `gameFull` event). */
@Serializable
data class BoardPlayer(
    val id: String? = null,
    val name: String? = null,
    val title: String? = null,
    val rating: Int? = null,
    /** Lichess's "?" marker (GameEventPlayer.provisional in the OpenAPI spec). */
    val provisional: Boolean = false,
)

/** Clock config for a board game (milliseconds). Absent for correspondence games. */
@Serializable
data class BoardClock(
    val initial: Long = 0,
    val increment: Long = 0,
)

/**
 * One line of the `GET /api/board/game/stream/{gameId}` NDJSON stream, dispatched on the `"type"` field.
 * `chatLine`, `opponentGone`, and any unrecognised/unparseable line map to [Unknown] and can be ignored.
 */
sealed interface BoardStreamEvent {

    /**
     * The first line of the stream. All fields are immutable except [state], which is refreshed by
     * every subsequent [GameState] event.
     */
    @Serializable
    data class GameFull(
        val id: String,
        /** May be the literal "startpos" for a standard initial position. */
        val initialFen: String = "startpos",
        val rated: Boolean = false,
        val speed: String? = null,
        val variant: BoardVariant = BoardVariant(),
        val white: BoardPlayer = BoardPlayer(),
        val black: BoardPlayer = BoardPlayer(),
        val state: GameState,
        /** Present only for correspondence games. */
        val daysPerTurn: Int? = null,
        val clock: BoardClock? = null,
    ) : BoardStreamEvent

    /** A mutable game-state update (also nested inside [GameFull.state]). */
    @Serializable
    data class GameState(
        /** Space-separated UCI moves from the initial position, e.g. "e2e4 e7e5". */
        val moves: String = "",
        /** White/Black time remaining, milliseconds. */
        val wtime: Long = 0,
        val btime: Long = 0,
        /** White/Black increment, milliseconds. */
        val winc: Long = 0,
        val binc: Long = 0,
        /** e.g. "started", "mate", "resign", "draw", "outoftime", "aborted". */
        val status: String = "",
        /** "white" or "black" when the game is decided, else null. */
        val winner: String? = null,
        /** Draw-offer flags. */
        val wdraw: Boolean = false,
        val bdraw: Boolean = false,
        /** Takeback-offer flags (mirrors wdraw/bdraw). */
        val wtakeback: Boolean = false,
        val btakeback: Boolean = false,
    ) : BoardStreamEvent

    /**
     * A chat message posted to the game's "player" or "spectator" room. Only "player"
     * room lines are surfaced by [BoardViewModel] (spectator chat is ignored).
     */
    @Serializable
    data class ChatLine(
        val room: String = "player",
        val username: String = "",
        val text: String = "",
    ) : BoardStreamEvent

    /** An `opponentGone`, or any line that could not be parsed; safe to skip. */
    data object Unknown : BoardStreamEvent
}

/**
 * The literal Lichess account that authors system announcements in the player chat
 * ("Takeback sent", "Draw offer accepted", …). Those lines are shown like any other
 * message but are NOT treated as opponent messages for unread-count purposes.
 */
const val SYSTEM_CHAT_USER = "lichess"

/**
 * One message of a game's PLAYER chat, from `GET /api/board/game/{gameId}/chat`.
 *
 * NOTE the field name: this endpoint calls the author [user], whereas the board
 * stream's `chatLine` event calls the same thing `username`
 * (see [BoardStreamEvent.ChatLine]) — they are NOT interchangeable.
 * The endpoint returns only the player room, so there is no `room` field to filter on.
 */
@Serializable
data class LichessChatMessage(
    val text: String = "",
    val user: String = "",
)

/** The game's variant, e.g. `key = "standard" | "horde" | "chess960" | "atomic" | …`. */
@Serializable
data class BoardVariant(
    val key: String = "standard",
    val name: String = "Standard",
)

/** Result of a board action (move/resign/draw). Lichess returns `{"ok":true}` or `{"error":"..."}`. */
sealed interface LichessActionResult {
    data object Success : LichessActionResult

    /**
     * @param error a message already fit to show a user (see [userMessage] — raw Ktor
     *   internals never reach here).
     * @param offline true when the request never made it to Lichess (no connection,
     *   DNS/TLS failure, timeout) as opposed to Lichess actively rejecting it. Lets the
     *   UI say "you're offline" instead of implying the move was refused. Defaults to
     *   false so existing `Failure("…")` construction still compiles.
     */
    data class Failure(val error: String, val offline: Boolean = false) : LichessActionResult
}

/**
 * Result of importing a PGN via `POST /api/import`. On success Lichess returns the created
 * game's [id] and public [url]; on failure it returns an error message (surfaced verbatim so
 * the user sees Lichess's own wording).
 */
sealed interface LichessImportResult {
    data class Success(val id: String, val url: String) : LichessImportResult
    data class Failure(val error: String) : LichessImportResult
}

/**
 * The kind of line seen on `GET /api/stream/event`. This stream is event-driven for
 * game start/finish and challenges only — it does NOT push per-move updates. The home
 * screen reacts by re-fetching the relevant list; the full payload isn't needed here.
 */
enum class AccountEventType { GAME_START, GAME_FINISH, CHALLENGE, CHALLENGE_CANCELED, CHALLENGE_DECLINED, UNKNOWN }

/** A per-variant rating block (from a following user's `perfs` map). */
@Serializable
data class LichessPerf(
    val rating: Int? = null,
    val prov: Boolean = false,
)

/**
 * A Lichess user as it appears in various payloads. Challenge user objects (`ChallengeUser`
 * in the OpenAPI spec) carry [name] plus a flat [rating] AND a flat [provisional] flag; the
 * `/api/rel/following` stream carries [username] and nests ratings (and provisional-ness)
 * under [perfs]. [displayName]/[ratingOrNull]/[provOrNull] normalise across both shapes.
 */
@Serializable
data class LichessUser(
    val id: String? = null,
    val name: String? = null,
    val username: String? = null,
    val online: Boolean = false,
    val rating: Int? = null,
    /** Flat provisional flag, present on challenge user payloads. */
    val provisional: Boolean = false,
    val perfs: Map<String, LichessPerf>? = null,
) {
    val displayName: String get() = name ?: username ?: id ?: "?"

    /** Flat rating if present (challenges), else the correspondence perf (following). */
    val ratingOrNull: Int? get() = rating ?: perfs?.get(CORRESPONDENCE_SPEED)?.rating

    /**
     * Whether the shown rating is provisional (Lichess's "?" marker): the flat
     * [provisional] flag (challenges) or, failing that, the correspondence perf's own
     * flag (`/api/rel/following`).
     */
    val provOrNull: Boolean get() = provisional || (perfs?.get(CORRESPONDENCE_SPEED)?.prov ?: false)
}

/** Formats "name · 1500", or "name · 1500?" when [prov] (provisional). */
fun nameWithRating(name: String, rating: Int?, prov: Boolean = false): String =
    if (rating != null) "$name · $rating${if (prov) "?" else ""}" else name

/** Time-control block of a challenge; [daysPerTurn] is set for correspondence. */
@Serializable
data class ChallengeTimeControl(
    val type: String? = null,
    val daysPerTurn: Int? = null,
)

/** One challenge from `GET /api/challenge` (`in`/`out`). */
@Serializable
data class LichessChallenge(
    val id: String,
    val status: String = "",
    val challenger: LichessUser? = null,
    val destUser: LichessUser? = null,
    val speed: String? = null,
    val rated: Boolean = false,
    val variant: BoardVariant = BoardVariant(),
    val timeControl: ChallengeTimeControl? = null,
    /** Requested color from the creator's side: "white" | "black" | "random". */
    val color: String? = null,
    /** Resolved concrete color of the CHALLENGER: "white" | "black". The recipient plays the opposite. */
    val finalColor: String? = null,
    /** "in" (incoming to me) or "out" (I created it). */
    val direction: String? = null,
) {
    val isCorrespondence: Boolean
        get() = speed == CORRESPONDENCE_SPEED || timeControl?.type == CORRESPONDENCE_SPEED
}

/** Incoming (targeted at me) and outgoing (created by me) pending challenges. */
data class LichessChallenges(
    val incoming: List<LichessChallenge> = emptyList(),
    val outgoing: List<LichessChallenge> = emptyList(),
)

@Serializable
private data class ChallengesResponse(
    @kotlinx.serialization.SerialName("in") val incoming: List<LichessChallenge> = emptyList(),
    @kotlinx.serialization.SerialName("out") val outgoing: List<LichessChallenge> = emptyList(),
)

@Serializable
private data class AccountResponse(
    val id: String? = null,
    val username: String? = null,
    val perfs: Map<String, LichessPerf>? = null,
)

/** One side of an archived game (`players.white` / `players.black`). */
@Serializable
data class ArchivedPlayer(
    val user: LichessUser? = null,
    val rating: Int? = null,
    /** Lichess's "?" marker (GamePlayerUser.provisional in the OpenAPI spec). */
    val provisional: Boolean = false,
)

@Serializable
data class ArchivedPlayers(
    val white: ArchivedPlayer = ArchivedPlayer(),
    val black: ArchivedPlayer = ArchivedPlayer(),
)

/** The game's base clock: [initial] and [increment] in seconds. Absent for correspondence. */
@Serializable
data class ArchivedClock(
    val initial: Int = 0,
    val increment: Int = 0,
)

/**
 * A finished (or ongoing) game from `GET /api/games/user/{username}`. [moves] is
 * space-separated SAN; [winner] is "white"/"black" or null for a draw/ongoing game.
 * [createdAt] is an epoch-ms timestamp used for pagination.
 */
@Serializable
data class LichessArchivedGame(
    val id: String,
    val rated: Boolean = false,
    val variant: String? = null,
    val speed: String? = null,
    val status: String = "",
    val winner: String? = null,
    val createdAt: Long = 0,
    /** Epoch-ms timestamp of the last move / game end (included in the games export). */
    val lastMoveAt: Long = 0,
    val players: ArchivedPlayers = ArchivedPlayers(),
    val moves: String = "",
    /** Present for games that don't start from the standard position. */
    val initialFen: String? = null,
    /**
     * Per-ply clock remaining, in centiseconds, one entry per half-move (requested
     * with `clocks=true`). Empty for correspondence games (no clock) and when not
     * requested. Index i corresponds to the position after ply i+1.
     */
    val clocks: List<Int> = emptyList(),
    /** Base clock (initial/increment seconds); null for correspondence. */
    val clock: ArchivedClock? = null,
)

class LichessApi(private val token: String) {

    // coerceInputValues: if Lichess sends an explicit `null` for a non-nullable field
    // that has a default (e.g. status/moves/clocks on some game shapes), fall back to the
    // default instead of throwing. Without this, getUserGames' runCatching{}.getOrNull()
    // would silently DROP the whole game line — the way a finished game could vanish from
    // history. winner is already nullable, so a drawn game (no winner) parses either way.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * TWO clients, because streaming and ordinary REST want opposite timeout regimes and a
     * single client cannot express both.
     *
     * The trap: on the OkHttp engine Ktor's `requestTimeoutMillis` maps to OkHttp's
     * *callTimeout*, which covers reading the body — so any finite global value would
     * guillotine a long-lived NDJSON stream mid-game. And `socketTimeoutMillis` maps to
     * OkHttp's read timeout, which defaults to 10s when [HttpTimeout] isn't installed at
     * all; since Lichess only sends keep-alive newlines about every 6s, that default was
     * killing board/event streams on any jitter. (It's also the source of the user-visible
     * "Socket timeout has expired [url=…, socket_timeout=unknown] ms" — `unknown` renders
     * precisely because no HttpTimeout config existed to name a value.)
     *
     * Two clients rather than one-with-per-request-overrides: the streams are the unusual
     * case but they're also the ones that must never be capped, and per-request overrides
     * are easy to forget on a newly added REST call — this way the safe default (finite)
     * applies unless a call deliberately opts into [streamClient].
     */
    private fun buildClient(timeouts: HttpTimeoutConfig.() -> Unit) = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout, timeouts)
        defaultRequest {
            header("Authorization", "Bearer $token")
        }
        // ONE central place to feed Connectivity, instead of instrumenting ~20 call sites.
        // A response arriving at all — even a 404 or 429 — proves the network works, so
        // only *thrown* transport failures count against us (Connectivity.reportFailure
        // ignores anything that isn't a transport failure).
        HttpResponseValidator {
            validateResponse { Connectivity.reportSuccess() }
            handleResponseExceptionWithRequest { cause, _ -> Connectivity.reportFailure(cause) }
        }
    }

    /** Short, bounded calls: fail fast so the UI can say something rather than hang. */
    private val client = buildClient {
        connectTimeoutMillis = REST_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REST_REQUEST_TIMEOUT_MS
        socketTimeoutMillis = REST_REQUEST_TIMEOUT_MS
    }

    /**
     * Long-lived NDJSON reads. Generous socket timeout (comfortably longer than Lichess's
     * ~6s keep-alive cadence) and NO overall request timeout, since a healthy board stream
     * legitimately stays open for the whole time the screen is visible.
     */
    private val streamClient = buildClient {
        connectTimeoutMillis = REST_CONNECT_TIMEOUT_MS
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = STREAM_SOCKET_TIMEOUT_MS
    }

    suspend fun getOngoingCorrespondenceGames(): List<LichessGame> {
        val response: LichessNowPlayingResponse = client.get("$BASE_URL/api/account/playing").body()
        return response.nowPlaying.filter { it.speed == CORRESPONDENCE_SPEED }
    }

    /**
     * A self-healing NDJSON stream over [url].
     *
     * Reconnects with exponential backoff + jitter (see [reconnectDelayMillis]) on any
     * transient failure, including a clean EOF — Lichess routinely closes long-lived
     * streams and a silently-ended stream is exactly the "board went dead mid-game" bug.
     * A 429 waits the full minute Lichess asks for; 401/403/400/404 are fatal and rethrown
     * so a dead token can't be retried forever. [CancellationException] always propagates
     * untouched (the SDK cancels these on screen hide / app pause).
     *
     * Gives up after [MAX_RECONNECT_ATTEMPTS] *consecutive* failures and rethrows the last
     * cause, so the existing `catch` at the call sites still surfaces an error. A
     * connection that survived [STREAM_STABLE_MS] resets that counter — otherwise a server
     * that accepts and instantly drops us would retry forever.
     *
     * @param onStatus optional progress signal ([StreamStatus.Connected] /
     *   [StreamStatus.Reconnecting] / [StreamStatus.GaveUp]) so a caller can tell
     *   "reconnecting" from "gave up". Deliberately a callback rather than extra Flow
     *   elements, to keep the emitted type unchanged for existing collectors.
     */
    private fun <T> ndjsonStream(
        url: String,
        onStatus: ((StreamStatus) -> Unit)?,
        request: HttpRequestBuilder.() -> Unit = {},
        parse: (String) -> T,
    ): Flow<T> = flow {
        var attempt = 0
        // An exception thrown by the COLLECTOR must never be mistaken for a connection
        // failure: swallowing it and emitting again trips Kotlin's "flow exception
        // transparency is violated" check. Tracked by identity and rethrown below.
        var downstreamFailure: Throwable? = null
        while (true) {
            var connectedAt = 0L
            val failure: Throwable? = try {
                streamClient.prepareGet(url, request).execute { response ->
                    if (!response.status.isSuccess()) {
                        throw LichessHttpException(response.status.value, "HTTP ${response.status.value}")
                    }
                    connectedAt = System.currentTimeMillis()
                    onStatus?.invoke(StreamStatus.Connected)
                    val channel = response.bodyAsChannel()
                    while (true) {
                        val line = channel.readLine() ?: break
                        if (line.isNotBlank()) {
                            val value = parse(line)
                            try {
                                emit(value)
                            } catch (t: Throwable) {
                                downstreamFailure = t
                                throw t
                            }
                        }
                    }
                }
                null // clean EOF — Lichess closed the stream; reconnect rather than end silently.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e
            }

            if (failure != null) {
                if (failure === downstreamFailure) throw failure
                Connectivity.reportFailure(failure)
                if (!isRetryableStreamFailure(failure)) {
                    onStatus?.invoke(StreamStatus.GaveUp(failure))
                    throw failure
                }
            }
            // A connection that stayed up long enough counts as healthy: start the backoff
            // curve over rather than escalating for an unrelated, much later drop.
            if (connectedAt != 0L && System.currentTimeMillis() - connectedAt >= STREAM_STABLE_MS) attempt = 0
            attempt++
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                onStatus?.invoke(StreamStatus.GaveUp(failure))
                throw failure ?: LichessHttpException(0, "Lost connection to the Lichess stream")
            }
            val wait = reconnectDelayMillis(attempt, failure)
            onStatus?.invoke(StreamStatus.Reconnecting(attempt, wait, failure))
            delay(wait)
        }
    }

    /**
     * Streams account events as a cold [Flow]: `GET /api/stream/event`. Emits on game
     * start/finish and challenge in/cancel/decline only (never per-move). Like
     * [streamBoardGame], the connection opens on collection and closes when the collector
     * stops — collect it only while the home screen is foregrounded. Reconnects itself; see
     * [ndjsonStream].
     */
    fun streamEvents(onStatus: ((StreamStatus) -> Unit)? = null): Flow<AccountEventType> =
        ndjsonStream("$BASE_URL/api/stream/event", onStatus) { parseEventType(it) }

    private fun parseEventType(line: String): AccountEventType = try {
        when (json.parseToJsonElement(line).jsonObject["type"]?.jsonPrimitive?.content) {
            "gameStart" -> AccountEventType.GAME_START
            "gameFinish" -> AccountEventType.GAME_FINISH
            "challenge" -> AccountEventType.CHALLENGE
            "challengeCanceled" -> AccountEventType.CHALLENGE_CANCELED
            "challengeDeclined" -> AccountEventType.CHALLENGE_DECLINED
            else -> AccountEventType.UNKNOWN
        }
    } catch (_: Exception) {
        AccountEventType.UNKNOWN
    }

    /** The authenticated account's Lichess username. `GET /api/account`. */
    suspend fun getAccountUsername(): String {
        val resp: AccountResponse = client.get("$BASE_URL/api/account").body()
        return resp.username ?: resp.id ?: ""
    }

    /**
     * The authenticated account's own correspondence rating (and provisional flag),
     * or null if `/api/account` doesn't carry a `perfs.correspondence` block (e.g. a
     * brand-new account with no correspondence games yet). `GET /api/account`.
     */
    suspend fun getOwnCorrespondenceRating(): LichessPerf? {
        val resp: AccountResponse = client.get("$BASE_URL/api/account").body()
        return resp.perfs?.get(CORRESPONDENCE_SPEED)
    }

    /**
     * A page of [username]'s games, newest first, across all speeds/variants.
     * `GET /api/games/user/{username}` (NDJSON). Pass [until] (epoch-ms) to fetch games
     * created strictly before it — use the oldest game's `createdAt` from the previous
     * page to load the next chunk (messages-style infinite scroll).
     */
    suspend fun getUserGames(username: String, max: Int, until: Long? = null): List<LichessArchivedGame> {
        val games = mutableListOf<LichessArchivedGame>()
        // NOTE: use application/json (not x-ndjson). The shared client's ContentNegotiation
        // already advertises application/json; adding x-ndjson on top yields an Accept
        // ordering that makes Lichess return PGN instead. application/json returns one JSON
        // object per line, which we parse below. (Body is read manually, not via CN.)
        // streamClient: this is a streaming export that can take a while to drain, so it
        // needs the generous socket timeout — but it terminates, so cap the whole call.
        streamClient.prepareGet("$BASE_URL/api/games/user/$username") {
            timeout { requestTimeoutMillis = BULK_REQUEST_TIMEOUT_MS }
            header("Accept", "application/json")
            parameter("max", max)
            // Per-move clock times, used by the review screen (non-correspondence games).
            parameter("clocks", true)
            if (until != null) parameter("until", until)
        }.execute { response ->
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readLine() ?: break
                if (line.isNotBlank()) {
                    runCatching { json.decodeFromString(LichessArchivedGame.serializer(), line) }
                        .getOrNull()?.let { games += it }
                }
            }
        }
        return games
    }

    /** Pending correspondence challenges in both directions. `GET /api/challenge`. */
    suspend fun getChallenges(): LichessChallenges {
        val resp: ChallengesResponse = client.get("$BASE_URL/api/challenge").body()
        return LichessChallenges(
            incoming = resp.incoming.filter { it.isCorrespondence },
            outgoing = resp.outgoing.filter { it.isCorrespondence },
        )
    }

    /** Accepts an incoming challenge. `POST /api/challenge/{id}/accept`. */
    suspend fun acceptChallenge(id: String): LichessActionResult =
        postAction("$BASE_URL/api/challenge/$id/accept")

    /** Declines an incoming challenge. `POST /api/challenge/{id}/decline`. */
    suspend fun declineChallenge(id: String): LichessActionResult =
        postAction("$BASE_URL/api/challenge/$id/decline")

    /** Cancels a challenge I created. `POST /api/challenge/{id}/cancel`. */
    suspend fun cancelChallenge(id: String): LichessActionResult =
        postAction("$BASE_URL/api/challenge/$id/cancel")

    /**
     * The users this account follows (Lichess's closest thing to a friend list).
     * `GET /api/rel/following` (NDJSON, `follow:read`). Read fully into a list rather than
     * kept open. Sorted online-first, then alphabetically.
     */
    suspend fun getFollowing(): List<LichessUser> {
        val users = mutableListOf<LichessUser>()
        // streamClient for the same reason as getUserGames: a drained NDJSON body, capped.
        streamClient.prepareGet("$BASE_URL/api/rel/following") {
            timeout { requestTimeoutMillis = BULK_REQUEST_TIMEOUT_MS }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readLine() ?: break
                if (line.isNotBlank()) {
                    runCatching { json.decodeFromString(LichessUser.serializer(), line) }
                        .getOrNull()?.let { users += it }
                }
            }
        }
        return users.sortedWith(
            compareByDescending<LichessUser> { it.online }
                .thenBy { it.displayName.lowercase() },
        )
    }

    /**
     * Streams the board game state as a cold [Flow]. `GET /api/board/game/stream/{gameId}`.
     *
     * The underlying HTTP connection is opened when collection starts and CLOSED when the collector
     * stops (completes or cancels) — collect it only while the board screen is visible. The body is
     * read line by line and never fully buffered. Blank lines (keep-alives) are skipped; lines that
     * fail to parse are emitted as [BoardStreamEvent.Unknown] rather than aborting the stream.
     *
     * Self-healing: a dropped connection reconnects with backoff (see [ndjsonStream]). Each
     * reconnect replays `gameFull` from Lichess, so the view model resynchronises for free.
     */
    fun streamBoardGame(gameId: String, onStatus: ((StreamStatus) -> Unit)? = null): Flow<BoardStreamEvent> =
        ndjsonStream("$BASE_URL/api/board/game/stream/$gameId", onStatus) { parseBoardEvent(it) }

    private fun parseBoardEvent(line: String): BoardStreamEvent = try {
        val obj = json.parseToJsonElement(line).jsonObject
        when (obj["type"]?.jsonPrimitive?.content) {
            "gameFull" -> json.decodeFromJsonElement(BoardStreamEvent.GameFull.serializer(), obj)
            "gameState" -> json.decodeFromJsonElement(BoardStreamEvent.GameState.serializer(), obj)
            "chatLine" -> json.decodeFromJsonElement(BoardStreamEvent.ChatLine.serializer(), obj)
            else -> BoardStreamEvent.Unknown
        }
    } catch (_: Exception) {
        BoardStreamEvent.Unknown
    }

    /**
     * Submits a move in UCI notation (e.g. "e2e4", "e7e8q", or a Crazyhouse drop "N@f3").
     * `POST /api/board/game/{gameId}/move/{move}`. The '@' in a drop is percent-encoded so
     * it isn't misread inside the URL path.
     */
    suspend fun submitMove(gameId: String, uciMove: String): LichessActionResult =
        postAction("$BASE_URL/api/board/game/$gameId/move/${uciMove.replace("@", "%40")}")

    /** Resigns the game. `POST /api/board/game/{gameId}/resign`. */
    suspend fun resignGame(gameId: String): LichessActionResult =
        postAction("$BASE_URL/api/board/game/$gameId/resign")

    /** Aborts the game (only valid before both players have moved). `POST /api/board/game/{gameId}/abort`. */
    suspend fun abortGame(gameId: String): LichessActionResult =
        postAction("$BASE_URL/api/board/game/$gameId/abort")

    /**
     * Offers/accepts ([accept] = true) or declines ([accept] = false) a draw.
     * `POST /api/board/game/{gameId}/draw/{yes|no}`.
     */
    suspend fun handleDraw(gameId: String, accept: Boolean): LichessActionResult =
        postAction("$BASE_URL/api/board/game/$gameId/draw/${if (accept) "yes" else "no"}")

    /**
     * Offers/accepts ([accept] = true) or declines ([accept] = false) a takeback.
     * `POST /api/board/game/{gameId}/takeback/{yes|no}`.
     */
    suspend fun takeback(gameId: String, accept: Boolean): LichessActionResult =
        postAction("$BASE_URL/api/board/game/$gameId/takeback/${if (accept) "yes" else "no"}")

    /**
     * Posts a chat message to the game's "player" room. `POST /api/board/game/{gameId}/chat`
     * (form-urlencoded: `room=player&text=...`).
     */
    suspend fun sendChatMessage(gameId: String, text: String): LichessActionResult = safeAction {
        val response = client.submitForm(
            url = "$BASE_URL/api/board/game/$gameId/chat",
            formParameters = Parameters.build {
                append("room", "player")
                append("text", text)
            },
        )
        resultFrom(response)
    }

    /**
     * The messages already posted in the game's PLAYER chat, oldest first.
     * `GET /api/board/game/{gameId}/chat` (`board:play`).
     *
     * This is the only way to see messages sent BEFORE the board stream was opened —
     * the stream's `chatLine` events are live-only and never replay history, and
     * `gameFull` carries no chat at all. Only the private 2-player room is returned
     * (the public spectator chat is a different endpoint, `/api/game/{id}/chat`), so
     * no room filtering is needed here.
     *
     * Returns an empty list on any failure rather than throwing — chat is strictly
     * supplementary to the board, so a chat fetch must never break opening a game.
     */
    suspend fun getChatMessages(gameId: String): List<LichessChatMessage> = try {
        val response = client.get("$BASE_URL/api/board/game/$gameId/chat")
        if (response.status.isSuccess()) parseChatMessages(response.bodyAsText()) else emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The OpenAPI spec declares this response as `application/x-ndjson` but types it as a
     * JSON *array* (`PlayerGameChat`), and Lichess in practice sends the array. Parse
     * whichever we actually get: a leading `[` means one JSON array, otherwise fall back
     * to one object per line. A single unparseable line is skipped, never fatal.
     */
    private fun parseChatMessages(body: String): List<LichessChatMessage> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            return runCatching {
                json.decodeFromString(ListSerializer(LichessChatMessage.serializer()), trimmed)
            }.getOrDefault(emptyList())
        }
        return trimmed.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString(LichessChatMessage.serializer(), it) }.getOrNull() }
            .toList()
    }

    /**
     * Creates a correspondence challenge to [opponent]. `POST /api/challenge/{username}`
     * (form-urlencoded). [days] must be one of 1, 2, 3, 5, 7, 10, 14. [color] is
     * "white" | "black" | "random" (omitted from the request when random, so Lichess
     * assigns 50/50). [variant] is a Lichess variant key ("standard", "horde", …). The
     * opponent must accept before the game starts and appears in both players' ongoing games.
     */
    suspend fun createCorrespondenceChallenge(
        opponent: String,
        days: Int = 2,
        rated: Boolean = false,
        color: String = "random",
        variant: String = "standard",
    ): LichessActionResult = safeAction {
        val response = client.submitForm(
            url = "$BASE_URL/api/challenge/$opponent",
            formParameters = Parameters.build {
                append("rated", rated.toString())
                append("days", days.toString())
                append("variant", variant)
                if (color != "random") append("color", color)
            },
        )
        resultFrom(response)
    }

    /**
     * Creates a correspondence seek — a request to be matched with a random opponent.
     * `POST /api/board/seek` (form-urlencoded, `board:play`). For correspondence the call
     * returns immediately with a seek id (the seek then waits in the background), so unlike
     * a real-time seek we do not hold the connection open. [days] is one of 1,2,3,5,7,10,14.
     * [ratingRange] is absolute and inclusive, like "1500-1800" (min strictly < max, or
     * Lichess 400s), or null for any.
     *
     * NOTE deliberately no `color`: the correspondence branch of this endpoint doesn't
     * accept one (lila's `Seek.make` takes no colour param), so anything sent is bound
     * and silently discarded — offering the choice would be a lie.
     */
    suspend fun seekCorrespondence(
        days: Int,
        rated: Boolean = false,
        variant: String = "standard",
        ratingRange: String? = null,
    ): LichessActionResult = safeAction {
        val response = client.submitForm(
            url = "$BASE_URL/api/board/seek",
            formParameters = Parameters.build {
                append("rated", rated.toString())
                append("days", days.toString())
                append("variant", variant)
                if (!ratingRange.isNullOrBlank()) append("ratingRange", ratingRange)
            },
        )
        resultFrom(response)
    }

    /**
     * Imports a game from [pgn] into the authenticated account. `POST /api/import`
     * (form-urlencoded, field `pgn`; any OAuth token works). Returns the created game's id
     * and public url on success. Used by the in-person (local) game screen to save a
     * finished hot-seat game to Lichess.
     *
     * NOTE: Lichess's importer normalises/validates the PGN and can REJECT some inputs —
     * certain variants it can't ingest, or malformed movetext — returning an error message.
     * We surface that message to the user rather than crashing; the engine's [Chess.toPgn]
     * emits a proper `Variant` tag, but acceptance is ultimately Lichess's call.
     */
    suspend fun importGame(pgn: String): LichessImportResult = try {
        val response = client.submitForm(
            url = "$BASE_URL/api/import",
            formParameters = Parameters.build { append("pgn", pgn) },
        )
        val bodyText = response.bodyAsText()
        if (response.status.isSuccess()) {
            runCatching {
                val obj = json.parseToJsonElement(bodyText).jsonObject
                val id = obj["id"]?.jsonPrimitive?.content
                val url = obj["url"]?.jsonPrimitive?.content
                    ?: id?.let { "$BASE_URL/$it" }
                if (url != null) {
                    LichessImportResult.Success(id ?: "", url)
                } else {
                    LichessImportResult.Failure("Lichess accepted the game but returned no link.")
                }
            }.getOrElse { LichessImportResult.Failure("Couldn't read Lichess's response.") }
        } else {
            val error = runCatching {
                json.parseToJsonElement(bodyText).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull() ?: bodyText.ifBlank { "HTTP ${response.status.value}" }
            LichessImportResult.Failure(error)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A thrown network error (no connectivity, DNS/TLS, timeout) would otherwise escape
        // the caller's launch{} and crash the app — surface it as a failure instead.
        Connectivity.reportFailure(e)
        LichessImportResult.Failure(e.userMessage())
    }

    private suspend fun postAction(url: String): LichessActionResult = safeAction { resultFrom(client.post(url)) }

    /**
     * Runs a board/challenge action, turning ANY failure — including a thrown network error
     * (no connectivity, DNS/TLS failure, timeout) — into [LichessActionResult.Failure] so the
     * method honours its Success/Failure contract instead of throwing. Without this, a network
     * exception escapes the caller's `viewModelScope.launch { … }` and reaches the coroutine
     * uncaught-exception handler, crashing the app. [CancellationException] is rethrown so
     * coroutine cancellation (e.g. leaving the screen mid-request) still works normally.
     *
     * The failure is CLASSIFIED before being stringified: a transport failure sets
     * [LichessActionResult.Failure.offline] and gets a friendly message, so callers can
     * distinguish "you're offline" from "Lichess said no" instead of showing raw Ktor text.
     */
    private suspend fun safeAction(block: suspend () -> LichessActionResult): LichessActionResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val offline = Connectivity.reportFailure(e)
            LichessActionResult.Failure(e.userMessage(), offline = offline)
        }

    private suspend fun resultFrom(response: HttpResponse): LichessActionResult {
        val bodyText = response.bodyAsText()
        if (response.status.isSuccess()) return LichessActionResult.Success
        val error = runCatching {
            json.parseToJsonElement(bodyText).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull() ?: bodyText.ifBlank { "HTTP ${response.status.value}" }
        return LichessActionResult.Failure(error)
    }

    // v1: PGN export disabled — may re-add. Only caller was BoardViewModel's
    // copyPgn/sharePgn (also disabled), so this is commented out with them.
    // /**
    //  * Exports the game as PGN text (for Copy/Email PGN). `GET /game/export/{gameId}` with
    //  * `Accept: application/x-chess-pgn`.
    //  */
    // suspend fun exportGamePgn(gameId: String): String =
    //     client.get("$BASE_URL/game/export/$gameId") {
    //         header("Accept", "application/x-chess-pgn")
    //     }.bodyAsText()

    fun close() {
        client.close()
        streamClient.close()
    }
}
