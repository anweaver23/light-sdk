package com.andyweaver.chess.lichess

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
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

/** Result of a board action (move/resign/draw). Lichess returns `{"ok":true}` or `{"error":"..."}`. */
sealed interface LichessActionResult {
    data object Success : LichessActionResult
    data class Failure(val error: String) : LichessActionResult
}

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

    /**
     * Offers/accepts ([accept] = true) or declines ([accept] = false) a draw.
     * `POST /api/board/game/{gameId}/draw/{yes|no}`.
     */
    suspend fun handleDraw(gameId: String, accept: Boolean): LichessActionResult =
        postAction("$BASE_URL/api/board/game/$gameId/draw/${if (accept) "yes" else "no"}")

    /**
     * Creates an UNRATED correspondence challenge to [opponent]. `POST /api/challenge/{username}`
     * (form-urlencoded). [daysPerTurn] must be one of 1, 2, 3, 5, 7, 10, 14. The opponent must accept
     * before the game starts and appears in both players' ongoing games. Rated is always false here —
     * per project testing rules we only ever create unrated games.
     */
    suspend fun createCorrespondenceChallenge(opponent: String, daysPerTurn: Int = 2): LichessActionResult {
        val response = client.submitForm(
            url = "$BASE_URL/api/challenge/$opponent",
            formParameters = Parameters.build {
                append("rated", "false")
                append("days", daysPerTurn.toString())
            },
        )
        val bodyText = response.bodyAsText()
        if (response.status.isSuccess()) return LichessActionResult.Success
        val error = runCatching {
            json.parseToJsonElement(bodyText).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull() ?: bodyText.ifBlank { "HTTP ${response.status.value}" }
        return LichessActionResult.Failure(error)
    }

    private suspend fun postAction(url: String): LichessActionResult {
        val response = client.post(url)
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
