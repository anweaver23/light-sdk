package com.andyweaver.chess.lichess

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CORRESPONDENCE_SPEED = "correspondence"
private const val BASE_URL = "https://lichess.org"

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
    ) : BoardStreamEvent

    /** A `chatLine`, `opponentGone`, or any line that could not be parsed; safe to skip. */
    data object Unknown : BoardStreamEvent
}

/** The game's variant, e.g. `key = "standard" | "horde" | "chess960" | "atomic" | …`. */
@Serializable
data class BoardVariant(
    val key: String = "standard",
    val name: String = "Standard",
)

/** Result of a board action (move/resign/draw). Lichess returns `{"ok":true}` or `{"error":"..."}`. */
sealed interface LichessActionResult {
    data object Success : LichessActionResult
    data class Failure(val error: String) : LichessActionResult
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
 * A Lichess user as it appears in various payloads. Challenge user objects carry [name]
 * plus a flat [rating]; the `/api/rel/following` stream carries [username] and nests
 * ratings under [perfs]. [displayName] and [ratingOrNull] normalise across both shapes.
 */
@Serializable
data class LichessUser(
    val id: String? = null,
    val name: String? = null,
    val username: String? = null,
    val online: Boolean = false,
    val rating: Int? = null,
    val perfs: Map<String, LichessPerf>? = null,
) {
    val displayName: String get() = name ?: username ?: id ?: "?"

    /** Flat rating if present (challenges), else the correspondence perf (following). */
    val ratingOrNull: Int? get() = rating ?: perfs?.get(CORRESPONDENCE_SPEED)?.rating
}

/** Formats "name · 1500", or just the name when the rating is unknown. */
fun nameWithRating(name: String, rating: Int?): String =
    if (rating != null) "$name · $rating" else name

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
private data class AccountResponse(val id: String? = null, val username: String? = null)

/** One side of an archived game (`players.white` / `players.black`). */
@Serializable
data class ArchivedPlayer(
    val user: LichessUser? = null,
    val rating: Int? = null,
)

@Serializable
data class ArchivedPlayers(
    val white: ArchivedPlayer = ArchivedPlayer(),
    val black: ArchivedPlayer = ArchivedPlayer(),
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
)

class LichessApi(private val token: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        defaultRequest {
            header("Authorization", "Bearer $token")
        }
    }

    suspend fun getOngoingCorrespondenceGames(): List<LichessGame> {
        val response: LichessNowPlayingResponse = client.get("$BASE_URL/api/account/playing").body()
        return response.nowPlaying.filter { it.speed == CORRESPONDENCE_SPEED }
    }

    /**
     * Streams account events as a cold [Flow]: `GET /api/stream/event`. Emits on game
     * start/finish and challenge in/cancel/decline only (never per-move). Like
     * [streamBoardGame], the connection opens on collection and closes when the collector
     * stops — collect it only while the home screen is foregrounded.
     */
    fun streamEvents(): Flow<AccountEventType> = flow {
        client.prepareGet("$BASE_URL/api/stream/event").execute { response ->
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isNotBlank()) emit(parseEventType(line))
            }
        }
    }

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
        client.prepareGet("$BASE_URL/api/games/user/$username") {
            header("Accept", "application/json")
            parameter("max", max)
            if (until != null) parameter("until", until)
        }.execute { response ->
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
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
        client.prepareGet("$BASE_URL/api/rel/following").execute { response ->
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
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
     */
    fun streamBoardGame(gameId: String): Flow<BoardStreamEvent> = flow {
        client.prepareGet("$BASE_URL/api/board/game/stream/$gameId").execute { response ->
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isNotBlank()) {
                    emit(parseBoardEvent(line))
                }
            }
        }
    }

    private fun parseBoardEvent(line: String): BoardStreamEvent = try {
        val obj = json.parseToJsonElement(line).jsonObject
        when (obj["type"]?.jsonPrimitive?.content) {
            "gameFull" -> json.decodeFromJsonElement(BoardStreamEvent.GameFull.serializer(), obj)
            "gameState" -> json.decodeFromJsonElement(BoardStreamEvent.GameState.serializer(), obj)
            else -> BoardStreamEvent.Unknown
        }
    } catch (_: Exception) {
        BoardStreamEvent.Unknown
    }

    /** Submits a move in UCI notation (e.g. "e2e4", "e7e8q"). `POST /api/board/game/{gameId}/move/{move}`. */
    suspend fun submitMove(gameId: String, uciMove: String): LichessActionResult =
        postAction("$BASE_URL/api/board/game/$gameId/move/$uciMove")

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
    ): LichessActionResult {
        val response = client.submitForm(
            url = "$BASE_URL/api/challenge/$opponent",
            formParameters = Parameters.build {
                append("rated", rated.toString())
                append("days", days.toString())
                append("variant", variant)
                if (color != "random") append("color", color)
            },
        )
        return resultFrom(response)
    }

    /**
     * Creates a correspondence seek — a request to be matched with a random opponent.
     * `POST /api/board/seek` (form-urlencoded, `board:play`). For correspondence the call
     * returns immediately with a seek id (the seek then waits in the background), so unlike
     * a real-time seek we do not hold the connection open. [days] is one of 1,2,3,5,7,10,14;
     * [color] "white" | "black" | "random" (omitted when random). [ratingRange] like
     * "1500-1800", or null for any.
     */
    suspend fun seekCorrespondence(
        days: Int,
        rated: Boolean = false,
        color: String = "random",
        variant: String = "standard",
        ratingRange: String? = null,
    ): LichessActionResult {
        val response = client.submitForm(
            url = "$BASE_URL/api/board/seek",
            formParameters = Parameters.build {
                append("rated", rated.toString())
                append("days", days.toString())
                append("variant", variant)
                if (color != "random") append("color", color)
                if (!ratingRange.isNullOrBlank()) append("ratingRange", ratingRange)
            },
        )
        return resultFrom(response)
    }

    private suspend fun postAction(url: String): LichessActionResult = resultFrom(client.post(url))

    private suspend fun resultFrom(response: HttpResponse): LichessActionResult {
        val bodyText = response.bodyAsText()
        if (response.status.isSuccess()) return LichessActionResult.Success
        val error = runCatching {
            json.parseToJsonElement(bodyText).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull() ?: bodyText.ifBlank { "HTTP ${response.status.value}" }
        return LichessActionResult.Failure(error)
    }

    /**
     * Exports the game as PGN text (for Copy/Email PGN). `GET /game/export/{gameId}` with
     * `Accept: application/x-chess-pgn`.
     */
    suspend fun exportGamePgn(gameId: String): String =
        client.get("$BASE_URL/game/export/$gameId") {
            header("Accept", "application/x-chess-pgn")
        }.bodyAsText()

    fun close() = client.close()
}
