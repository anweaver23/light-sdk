package com.andyweaver.chess.board

import androidx.lifecycle.viewModelScope
import com.andyweaver.chess.engine.Chess
import com.andyweaver.chess.engine.Color
import com.andyweaver.chess.engine.GameStatus
import com.andyweaver.chess.engine.Move
import com.andyweaver.chess.engine.MoveGenerator
import com.andyweaver.chess.engine.MoveRecord
import com.andyweaver.chess.engine.Piece
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Position
import com.andyweaver.chess.engine.Replay
import com.andyweaver.chess.engine.Square
import com.andyweaver.chess.engine.Variant
import com.andyweaver.chess.lichess.BoardStreamEvent
import com.andyweaver.chess.lichess.LichessActionResult
import com.andyweaver.chess.lichess.LichessApi
import com.andyweaver.chess.lichess.SYSTEM_CHAT_USER
import com.andyweaver.chess.lichess.nameWithRating
import com.andyweaver.chess.settings.ChessSettings
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Which set of controls the bottom bar shows. */
enum class BottomMode { BROWSE, PENDING }

/** A destructive/irreversible action awaiting a CONFIRM/✕ overlay. */
enum class Confirmation { RESIGN, DRAW, ABORT, TAKEBACK }

/**
 * One in-game chat message (the "player" room only). [fromOpponent] drives the unread
 * count; [system] marks Lichess's own announcements ("Takeback sent", …), which are
 * shown in the list but are never [fromOpponent].
 */
data class ChatMessage(
    val username: String,
    val text: String,
    val fromOpponent: Boolean,
    val system: Boolean = false,
)

/**
 * Splices the server's chat [history] together with whatever has already arrived live on
 * the stream ([current]), so a message delivered by BOTH paths isn't shown twice.
 *
 * Chat is append-only and both sides observe the same ordering, so [history] and [current]
 * are prefixes of one conversation. The merge is therefore just "whichever reaches further
 * wins": [history] is authoritative up to its own length, and anything [current] holds
 * beyond that arrived after the fetch snapshot and is appended.
 *
 * Idempotent, and — unlike matching on content overlap — correct when re-fetching after
 * sending. That case is what made the previous approach duplicate the entire conversation:
 * the refetched [history] contained the just-sent messages while [current] did not, so no
 * suffix-of-history matched any prefix-of-current at any length and both were concatenated.
 *
 * Residual race: a message arriving live in the window between the server composing the
 * history response and it landing here can't be told apart from one already inside it, so
 * it's dropped. It returns on the next fetch (every `onScreenShow`), and the window is
 * sub-second on a screen that has only just opened.
 */
internal fun mergeChatHistory(history: List<ChatMessage>, current: List<ChatMessage>): List<ChatMessage> =
    history + current.drop(history.size)

/**
 * One piece slide. [startSquare] is where the overlay piece begins, [endSquare] where it
 * lands — the static board hides its piece on [endSquare] for the duration so it isn't
 * drawn twice.
 */
data class PieceSlide(
    val startSquare: Int,
    val endSquare: Int,
    val piece: Piece,
)

/**
 * The slide(s) to animate for one browsed-position transition — usually a single piece,
 * but castling animates both the king and rook. A forward step animates from→to; a
 * backward step animates to→from, so undoing a move reads as literal reverse playback
 * (the just-made move's piece slides back home; nothing else moves, the last-move frames
 * simply reappear on the earlier move). [id] restarts the animation even when the same
 * squares repeat.
 */
data class AnimatedMove(
    val slides: List<PieceSlide>,
    val id: Long,
    /** Extra delay (ms) before the slide starts — used only for "arrival" animations
     * (opening a live game/review on its last move) so the eye registers the pre-move
     * position before it moves. Zero (immediate) for every other animation: manual
     * step/scrub, a live opponent move landing, or the user's own staged move. */
    val startDelayMs: Int = 0,
    /**
     * Forward capture slides only: the board to render WHILE the slide is in flight — the
     * pre-move position with the capturing piece lifted off its origin (the overlay draws it
     * sliding). It keeps the captured piece visible until the slide settles, then switches to
     * the post-move board — so you see what's taken (and, for Atomic, the whole region clears
     * on settle). Null for every other animation (non-captures, backward steps).
     */
    val preMoveBoard: List<Piece?>? = null,
)

/**
 * The [PieceSlide]s for one replay [step], with landing pieces read from [newBoard] (the
 * board of the DESTINATION position — exactly the squares the board hides during the
 * slide). [forward] = stepping into the move (from→to); else undoing it (to→from).
 * Castling yields two slides (king + rook, using the true king destination even in
 * Chess960 where the move records the rook square); other moves yield one. Drops yield
 * none (handled by the caller). Shared by the board and review view models.
 */
internal fun stepSlides(step: MoveRecord, newBoard: List<Piece?>, forward: Boolean): List<PieceSlide> {
    val move = step.move
    fun slide(from: Int, to: Int): PieceSlide? {
        val start = if (forward) from else to
        val land = if (forward) to else from
        val piece = newBoard.getOrNull(land) ?: return null
        return PieceSlide(startSquare = start, endSquare = land, piece = piece)
    }
    if (move.isCastle) {
        val (kingTo, rookFrom, rookTo) = MoveGenerator.castleSquares(step.before, move)
        return listOfNotNull(slide(move.from, kingTo), slide(rookFrom, rookTo))
    }
    return listOfNotNull(slide(move.from, move.to))
}

/**
 * Squares the active variant highlights as its goal (a single combined dashed border in
 * the UI): King of the Hill's 2×2 centre, Racing Kings' 8th rank. Empty for every other
 * variant. Shared by the live board and the review screen so both render it identically.
 */
internal fun goalSquaresFor(variant: Variant): Set<Int> = when (variant) {
    Variant.KING_OF_THE_HILL -> setOf(
        Square.of(3, 3), Square.of(4, 3), Square.of(3, 4), Square.of(4, 4), // d4, e4, d5, e5
    )
    Variant.RACING_KINGS -> (0..7).map { Square.of(it, 7) }.toSet() // the 8th rank
    else -> emptySet()
}

/**
 * True if [move] made in [before] captures a piece (a normal capture or en passant — the
 * captured pawn is then behind the target square). Castling and drops never capture.
 * Forward capture slides animate specially (see [captureSlideAnim]) so the captured piece
 * stays on the board until the capturer lands.
 */
internal fun isCaptureMove(before: Position, move: Move): Boolean {
    if (move.isCastle || move.isDrop) return false
    val capturedSquare = if (move.isEnPassant) {
        Square.of(Square.file(move.to), Square.rank(move.from))
    } else {
        move.to
    }
    return before.pieceAt(capturedSquare) != null
}

/**
 * The [AnimatedMove] for a FORWARD capture slide: the capturing piece slides from its origin
 * to the capture square over the PRE-move board — where the captured piece is still present —
 * so the capture reads (you see WHAT is taken) and the board only updates to the post-move
 * state once the slide settles. This is what keeps the captured piece visible during an
 * arrival replay's start delay (and covers Atomic, whose whole capture region clears on
 * settle). The overlay piece is the one that ends up on the target ([after]'s piece there),
 * or the mover if the target ends empty (an Atomic explosion). [id] is supplied by the caller.
 */
internal fun captureSlideAnim(before: Position, after: Position, move: Move, id: Long): AnimatedMove? {
    val piece = after.board.getOrNull(move.to) ?: before.pieceAt(move.from) ?: return null
    // The mover is lifted off its origin so the static board doesn't draw it twice; the
    // overlay slide draws it travelling to the capture square. The captured piece stays on
    // the pre-move board (on the target, or behind it for en passant) until the slide ends.
    val pre = before.board.toMutableList()
    pre[move.from] = null
    return AnimatedMove(
        slides = listOf(PieceSlide(startSquare = move.from, endSquare = move.to, piece = piece)),
        id = id,
        preMoveBoard = pre,
    )
}

/**
 * Three-check only: how many times each colour has BEEN checked, counted over the
 * positions actually shown (index 0..[uptoIndex]). A position whose side to move is in
 * check means the move that produced it delivered a check to that side. Empty for every
 * other variant. The badge on each king shows the count for that king's colour.
 */
internal fun threeCheckCounts(positions: List<Position>, uptoIndex: Int, variant: Variant): Map<Color, Int> {
    if (variant != Variant.THREE_CHECK) return emptyMap()
    var white = 0
    var black = 0
    val last = uptoIndex.coerceIn(0, positions.lastIndex)
    for (i in 1..last) {
        val pos = positions[i]
        if (Chess.isInCheck(pos)) {
            if (pos.sideToMove == Color.WHITE) white++ else black++
        }
    }
    return mapOf(Color.WHITE to white, Color.BLACK to black)
}

// v1: PGN export disabled — may re-add
// /** How a fetched PGN should be delivered (performed in the UI layer). */
// enum class PgnDelivery { CLIPBOARD, SHARE }
//
// /** One-shot side effect: a fetched PGN plus how to deliver it. */
// data class PgnEvent(val pgn: String, val delivery: PgnDelivery)

/**
 * Immutable render snapshot for the board screen. Everything [BoardScreen] draws
 * is derived here so the composable stays a pure function of this state.
 *
 * [board] is the 64-entry piece list of the position currently displayed (which,
 * while a move is pending, is the latest position with the pending move applied).
 */
data class BoardUiState(
    val opponentName: String = "Opponent",
    /**
     * The opponent's RAW Lichess username, without the " · rating" suffix [opponentName]
     * carries. Null until the board stream's gameFull arrives. Used where the bare handle
     * is wanted (the chat compose screen's title).
     */
    val opponentUsername: String? = null,
    val subtitle: String = "",
    val board: List<Piece?> = Chess.startPosition.board,
    val myColor: Color = Color.WHITE,
    val flipped: Boolean = false,
    /**
     * "Across the table" rendering for the in-person (local) game: the board stays
     * unflipped (White at the bottom) and the far side's pieces (Black) are drawn rotated
     * 180° so they read upright to a player sitting opposite. Additive to every existing
     * behaviour — default false, so the live board and review are completely unaffected —
     * and combines ADDITIVELY with the checkmate-king rotation (see [SquareCell]/[ChessBoard]).
     */
    val acrossMode: Boolean = false,
    /**
     * In-person (local) game only: the side to move at the currently VIEWED position — the
     * player who can move right now (Task B lets you fork from a past position). Drives the
     * promotion-picker colour and which pocket (Crazyhouse) is tappable, independently of
     * [myColor], which is now pinned to the fixed BOTTOM side of the board (orientation), not
     * the mover. Null and unused on the online board and the review screen.
     */
    val moverColor: Color? = null,
    val selectedSquare: Int? = null,
    val legalDestinations: Set<Int> = emptySet(),
    val lastMoveFrom: Int? = null,
    val lastMoveTo: Int? = null,
    /** Live check only (not checkmate) — draws the marching-ants dashed border. */
    val checkedKingSquare: Int? = null,
    /** Checkmate only — draws no border; the king itself renders turned sideways. */
    val checkmateKingSquare: Int? = null,
    /** When true, the promotion picker is shown; render the four choices in [myColor]. */
    val promotionActive: Boolean = false,
    val mode: BottomMode = BottomMode.BROWSE,
    val canStepBack: Boolean = false,
    val canStepForward: Boolean = false,
    val terminal: Boolean = false,
    val menuOpen: Boolean = false,
    val confirmation: Confirmation? = null,
    /** Local (in-person, pass-and-play) game only: "are you sure?" overlay before letting a
     *  back-press with moves already played navigate away and lose progress. */
    val confirmingExit: Boolean = false,
    /** Lichess only allows offering a draw after both players have moved. */
    val canOfferDraw: Boolean = false,
    /** Lichess only allows aborting before both players have moved. */
    val canAbort: Boolean = false,
    /** The opponent has offered a draw and we haven't responded yet. */
    val incomingDrawOffer: Boolean = false,
    /** Lichess only allows requesting a takeback after at least one move has been played. */
    val canRequestTakeback: Boolean = false,
    /** The opponent has offered a takeback and we haven't responded yet. */
    val incomingTakebackOffer: Boolean = false,
    /** Non-null when the game is a variant our engine doesn't recognise yet
     * (the display name to show on the "not supported" screen instead of a board). */
    val unsupportedVariant: String? = null,
    /** The active variant, for variant-specific rendering (pockets, goal squares). */
    val variant: Variant = Variant.STANDARD,
    /** Crazyhouse reserves the player may drop, and the opponent's, as type→count. */
    val myPocket: Map<PieceType, Int> = emptyMap(),
    val opponentPocket: Map<PieceType, Int> = emptyMap(),
    /**
     * Crazyhouse in ANALYSIS only: true when it is the OPPONENT's turn in the sandbox
     * line, so the top (opponent) reserve bar is the interactive one and the bottom (mine)
     * is read-only. The bars themselves never swap sides — bottom is always mine, top
     * always the opponent's — so the board doesn't visually jump mid-line; only which one
     * accepts taps follows the side to move. Always false in a live game and on the
     * review screen, where only my own bar is ever tappable.
     */
    val opponentPocketTappable: Boolean = false,
    /** A pocket piece the player has picked up to drop (Crazyhouse), if any. */
    val selectedDrop: PieceType? = null,
    /** Legal squares for the [selectedDrop]. */
    val dropTargets: Set<Int> = emptySet(),
    /** Squares to outline in red as the variant's goal (KotH centre, Racing Kings rank 8). */
    val goalSquares: Set<Int> = emptySet(),
    /**
     * Three-check only: times each colour has been checked, shown as a count badge on
     * that colour's king. Empty for every other variant.
     */
    val checkCounts: Map<Color, Int> = emptyMap(),
    /**
     * Material display (Lichess-style), non-Crazyhouse only. [myCaptured] are the
     * opponent-coloured pieces I've captured; [opponentCaptured] are my-coloured
     * pieces the opponent has captured. At most one advantage is > 0 (the leader's
     * point lead); the other is 0. Empty/zero for Crazyhouse (the pockets cover it).
     */
    val myCaptured: List<PieceType> = emptyList(),
    val opponentCaptured: List<PieceType> = emptyList(),
    val myAdvantage: Int = 0,
    val opponentAdvantage: Int = 0,
    /**
     * Review only: each player's clock remaining at the displayed position (formatted).
     * [myClockLabel] renders bottom-right, [opponentClockLabel] top-right. Both show the
     * base time before either side has moved. Null on the live board and for
     * correspondence games (no per-move clock).
     */
    val myClockLabel: String? = null,
    val opponentClockLabel: String? = null,
    val viewFraction: Float = 1f,
    /**
     * Total plies in the game (== the last position index, i.e. the number of moves
     * played). Used by the move-scrub gesture to advance a FIXED number of moves per unit
     * of drag regardless of game length. Zero when there are no moves yet.
     */
    val totalPlies: Int = 0,
    /** A one-shot piece slide to play for the transition into this position (or null). */
    val animatingMove: AnimatedMove? = null,
    val message: String? = null,
    /**
     * True while the board is showing the local analysis sandbox instead of the live
     * game (see [BoardViewModel.enterAnalysis]) — [BoardScreen] swaps the top-bar title to
     * "Analysis" while this is set. The live game/stream keeps running underneath; nothing
     * played here is ever submitted to Lichess.
     */
    val analysisActive: Boolean = false,
    /** In-game chat messages ("player" room only), oldest first. */
    val chatMessages: List<ChatMessage> = emptyList(),
    /** True while the chat panel is open (drives [BoardScreen]'s overlay and unread reset). */
    val chatOpen: Boolean = false,
    /**
     * Opponent messages the user hasn't seen: everything past the read mark persisted in
     * [ChessSettings], so this survives an app restart and covers messages that arrived
     * while the app was closed (not just ones streamed this session).
     */
    val unreadChatCount: Int = 0,
)

/**
 * Backs [BoardScreen]. Streams one Lichess board game while the screen is
 * visible/foregrounded, exposes a single [BoardUiState] to render, and drives all
 * move/resign/draw/PGN interactions.
 *
 * Threading: the stream is collected on [viewModelScope] (Main dispatcher). Ktor's
 * OkHttp engine performs the network IO off-thread, so all state mutation happens
 * on one thread and needs no locking. Action calls (move/resign/draw/PGN export)
 * likewise launch on Main and let the suspend API do its own IO.
 */
class BoardViewModel(
    private val api: LichessApi,
    private val settings: ChessSettings,
    private val gameId: String,
    private val myColor: Color,
    currentFen: String? = null,
    seededOpponentName: String? = null,
    seededVariant: Variant = Variant.STANDARD,
    seededLastMove: String? = null,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(
        BoardUiState(myColor = myColor, flipped = myColor == Color.BLACK),
    )
    val uiState: StateFlow<BoardUiState> = _uiState.asStateFlow()

    // v1: PGN export disabled — may re-add
    // // One-shot PGN delivery, consumed by the UI (clipboard / share intent).
    // private val _pgnEvent = MutableStateFlow<PgnEvent?>(null)
    // val pgnEvent: StateFlow<PgnEvent?> = _pgnEvent.asStateFlow()

    // ----- internal game/render state (all mutated on the Main thread) -----
    private var initialFen: String? = null
    private var replay: Replay = Chess.replay("")
    private var viewIndex: Int = 0
    private var selectedSquare: Int? = null
    private var legalDests: Set<Int> = emptySet()
    // A pawn move to the last rank awaiting a promotion-piece choice (from, to).
    private var pendingPromotion: Pair<Int, Int>? = null
    private var pendingMove: Move? = null
    private var awaitingServer: Boolean = false
    private var streamStatus: String = ""
    private var streamWinner: String? = null
    // Seeded from the home row so the top bar shows the real name on first paint
    // (no "Opponent" flash); reconciled from gameFull when the stream arrives.
    private var opponentName: String = seededOpponentName?.takeIf { it.isNotBlank() } ?: "Opponent"
    // The opponent's raw handle (no rating suffix), learned from gameFull. Not seeded —
    // the home row only hands us the formatted "name · rating" display string.
    private var opponentUsernameRaw: String? = null
    // My own raw (non-display) Lichess username, used to tell an incoming chatLine (and a
    // history entry) apart from our own messages. Learned from gameFull's white/black
    // player block, but ALSO resolved eagerly from the saved login session — the chat
    // history fetch can complete before gameFull arrives, and without a username every
    // history entry would be misattributed to the opponent (and counted unread).
    private var myUsernameRaw: String? = null
    // The full player-room conversation, oldest first: seeded from the chat history
    // endpoint (the stream never replays history) and appended to by live chatLine events.
    private val chatMessages = mutableListOf<ChatMessage>()
    private var chatOpen: Boolean = false
    private var unreadChatCount: Int = 0
    // High-water mark of messages already read, mirroring ChessSettings' persisted value
    // so the badge survives an app restart. Derived, not incremented: unreadChatCount is
    // always recomputed as "opponent messages after this index" (see refreshUnread).
    private var chatReadCount: Int = 0
    private var chatReadLoaded: Boolean = false
    private var chatHistoryJob: Job? = null
    private var unsupportedVariant: String? = null
    // Seeded from the home row; corrected from gameFull when the stream arrives.
    private var variant: Variant = seededVariant
    // Crazyhouse: a pocket piece picked up to drop, and its legal target squares.
    private var selectedDrop: PieceType? = null
    private var dropTargets: Set<Int> = emptySet()
    // Last-move highlight seeded from the home row's UCI, so the highlight paints on
    // the very first frame instead of popping in when the stream's move list arrives.
    // Used only while the replay has no moves of its own (the seed-only state).
    private val seededLastFrom: Int? = seededLastMove?.takeIf { it.length >= 4 }?.let { Square.fromName(it.substring(0, 2)) }
    private val seededLastTo: Int? = seededLastMove?.takeIf { it.length >= 4 }?.let { Square.fromName(it.substring(2, 4)) }
    private var opponentOfferedDraw: Boolean = false
    // Set true once we accept/decline an incoming offer, to hide the prompt until
    // the stream reflects the change; reset when the offer clears.
    private var drawResponsePending: Boolean = false
    private var opponentOfferedTakeback: Boolean = false
    // Mirrors drawResponsePending for the takeback flow.
    private var takebackResponsePending: Boolean = false
    private var menuOpen: Boolean = false
    private var confirmation: Confirmation? = null

    // ----- analysis sandbox (a separate local branch, parallel to the live game) -----
    // Entered via the action menu or a long-press on the board (see BoardScreen's
    // ChessBoard long-press). Snapshots the currently DISPLAYED position (whatever
    // viewIndex the user is browsing) into its own local Replay and lets them play ANY
    // legal move from there using the engine only — no confirm step, no submission to
    // Lichess. The live stream keeps running underneath (handleEvent/applyState are
    // untouched by this), so leaving analysis returns to a fully up-to-date live game.
    private var analysisActive: Boolean = false
    private var analysisMoves: MutableList<String> = mutableListOf()
    // The snapshotted real-game position the sandbox branches from. Held as a Position
    // (not a FEN) so Crazyhouse pockets and promoted-piece marks survive the snapshot —
    // Position.toFen() emits only the six standard fields (see Chess.replayFrom).
    private var analysisBase: Position = Chess.startPosition
    private var analysisReplay: Replay = Chess.replayFrom(Chess.startPosition, emptyList())
    // The REAL game's last-move highlight at the moment analysis was entered. Shown
    // whenever the sandbox is sitting on its base position (index 0) — on entry, after a
    // long-press reset, and when the user steps all the way back — so entering analysis
    // doesn't blank the highlight the live board was just showing. Null when the live
    // board wasn't showing one (viewing the game's own start position).
    private var analysisBaseLastFrom: Int? = null
    private var analysisBaseLastTo: Int? = null
    // Which position of the analysis branch is on screen — lets the user step/scrub back
    // through their own analysis moves and then play a DIFFERENT move from there, which
    // truncates the line at that point and appends the new move (see applyAnalysisMove).
    private var analysisViewIndex: Int = 0
    private var analysisSelectedSquare: Int? = null
    private var analysisLegalDests: Set<Int> = emptySet()
    private var analysisSelectedDrop: PieceType? = null
    private var analysisDropTargets: Set<Int> = emptySet()
    private var analysisPendingPromotion: Pair<Int, Int>? = null
    // One-shot slide for an analysis move, cleared right after each render (unlike the
    // live game's `pendingAnim`, analysis has no background stream re-triggering
    // recomputes, so it's safe to null it immediately — same discipline as
    // LocalGameViewModel/ReviewViewModel).
    private var analysisPendingAnim: AnimatedMove? = null
    private var analysisAnimCounter = 0L
    private var message: String? = null
    private var confirmMoves: Boolean = true
    private var showLegalMoves: Boolean = true

    private var streamJob: Job? = null

    // The animation currently in play, and a counter to key each new one. This PERSISTS
    // across recomputes (it is emitted on every one, never nulled after emit) — the UI
    // keys its Animatable off `animatingMove.id`, so re-emitting the same id is a no-op
    // (the Animatable rests at 1f once done) and a *new* id triggers a fresh slide. That
    // id-keying is what prevents unrelated recomputes (menu, settings, selection) from
    // replaying an animation — so we must NOT null this between them, or an intermediate
    // recompute during an arrival slide would flip the key to null, reset the Animatable,
    // and flash the piece from destination back to origin. Each genuine transition
    // (step/move/live-move/arrival) overwrites it with a new id; everything else leaves it.
    // Declared BEFORE init{} so init{}'s seed-time arrival animation isn't wiped by a
    // late `= null` initializer (property initializers run in textual order).
    private var pendingAnim: AnimatedMove? = null
    private var animCounter = 0L

    // The UCI of the last move whose arrival animation has already been shown, and the
    // exact AnimatedMove (same id) that showed it — set ONLY when an arrival animation
    // actually plays (seed or resetView), never eagerly. Guards applyState's resetView
    // branch against re-animating the SAME move a second time once the real GameFull
    // arrives: when re-emitting the "already shown" case, it must re-emit THIS SAME
    // AnimatedMove object (same id), not null — ChessBoard keys its Animatable off
    // anim?.id, so flipping animatingMove to null (a different key) resets/aborts
    // whatever's mid-flight instead of just "not starting a new one". Re-emitting the
    // same id is a harmless no-op once the slide has already finished.
    private var arrivalAnimatedUci: String? = null
    private var lastArrivalAnim: AnimatedMove? = null

    init {
        // Seed the displayed board from the game's current FEN (passed by the home
        // screen) so the FIRST render shows the real position instead of the standard
        // start — avoids a start-position flash before the live stream arrives. When
        // gameFull lands it reconciles from the authoritative move list as usual.
        if (currentFen != null) {
            replay = runCatching { Chess.replay("", currentFen, variant) }.getOrElse { Chess.replay("", null, variant) }
            viewIndex = replay.positions.lastIndex
            // If the home row also seeded a last move, show ITS arrival animation from
            // the very first frame instead of a static already-arrived position — the
            // seeded state has no move list yet, so buildStepAnim can't be used; build
            // the slide directly from the seeded destination board. Doesn't handle
            // castling specially (a plain single-piece slide guess) — fine, since this
            // is superseded within about one network round-trip once the real GameFull
            // arrives and applyState rebuilds the animation from the authoritative replay.
            if (seededLastFrom != null && seededLastTo != null) {
                val piece = replay.positions.last().board.getOrNull(seededLastTo)
                if (piece != null) {
                    animCounter += 1
                    val anim = AnimatedMove(
                        slides = listOf(PieceSlide(seededLastFrom, seededLastTo, piece)),
                        id = animCounter,
                        startDelayMs = ARRIVAL_ANIM_DELAY_MS,
                    )
                    pendingAnim = anim
                    lastArrivalAnim = anim
                    arrivalAnimatedUci = seededLastMove
                }
            }
        }
        // Keep the confirm-moves preference current; defaults to true until loaded.
        viewModelScope.launch { settings.confirmMoves.collect { confirmMoves = it } }
        // Show-legal-moves affects rendering directly, so re-render on change.
        viewModelScope.launch { settings.showLegalMoves.collect { showLegalMoves = it; recompute() } }
        recompute()
    }

    // ----- lifecycle: stream only while visible AND foregrounded -----

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        startStream()
        // Fires on first show AND on every return/app-resume, mirroring how the home
        // screen re-fetches. The stream is closed while backgrounded and drops any
        // messages sent in the meantime, so re-reading the history here is what catches
        // them up — no polling loop needed.
        loadChatHistory()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        stopStream()
    }

    override fun onAppPause() {
        super.onAppPause()
        stopStream()
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
        api.close()
    }

    private fun startStream() {
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            try {
                api.streamBoardGame(gameId).collect { handleEvent(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "Connection lost"
                recompute()
            }
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
    }

    // ----- stream handling -----

    private fun handleEvent(event: BoardStreamEvent) {
        when (event) {
            is BoardStreamEvent.GameFull -> {
                variant = Variant.fromKey(event.variant.key)
                // Lichess reports "startpos" even for variants with a fixed but
                // NON-standard start (Racing Kings, Horde); fall back to the variant's
                // real starting FEN so we don't render the standard chess start.
                initialFen = event.initialFen.takeUnless { it == "startpos" || it.isBlank() }
                    ?: variant.startFen
                // Only a variant we don't recognise at all falls back to the "not
                // supported" screen; every known variant now plays.
                unsupportedVariant = event.variant.name
                    .takeIf { event.variant.key.lowercase() !in KNOWN_VARIANTS }
                val opp = if (myColor == Color.WHITE) event.black else event.white
                val oppName = opp.name?.takeIf { it.isNotBlank() } ?: "Opponent"
                opponentName = nameWithRating(oppName, opp.rating, opp.provisional)
                opponentUsernameRaw = opp.name?.takeIf { it.isNotBlank() }
                val me = if (myColor == Color.WHITE) event.white else event.black
                myUsernameRaw = me.name
                applyState(event.state, resetView = true)
            }

            is BoardStreamEvent.GameState -> applyState(event, resetView = false)

            is BoardStreamEvent.ChatLine -> handleChatLine(event)

            BoardStreamEvent.Unknown -> Unit
        }
    }

    private fun handleChatLine(event: BoardStreamEvent.ChatLine) {
        if (event.room != "player") return // ignore spectator chat
        chatMessages += chatMessage(event.username, event.text)
        // While the panel is open the message is seen the moment it lands, so advance the
        // read mark in memory rather than counting it unread; it's persisted on close.
        if (chatOpen) chatReadCount = chatMessages.size
        refreshUnread()
        recompute()
    }

    /**
     * Loads the messages posted BEFORE the stream opened. The board stream's `chatLine`
     * events are live-only — they never replay history, and `gameFull` carries none — so
     * without this fetch every message vanished on app restart and anything the opponent
     * sent while we were away was invisible.
     *
     * Runs concurrently with (not before) the board stream so chat never delays the board.
     * That means a message can race in via BOTH paths, which [mergeChatHistory] resolves.
     */
    private fun loadChatHistory() {
        if (chatHistoryJob?.isActive == true) return
        chatHistoryJob = viewModelScope.launch {
            if (!chatReadLoaded) {
                chatReadCount = settings.chatReadCount(gameId).first()
                chatReadLoaded = true
            }
            if (myUsernameRaw == null) myUsernameRaw = settings.session.first()?.username
            val history = api.getChatMessages(gameId).map { chatMessage(it.user, it.text) }
            val merged = mergeChatHistory(history, chatMessages.toList())
            chatMessages.clear()
            chatMessages += merged
            // Messages that arrive while the panel is already open count as seen.
            if (chatOpen) chatReadCount = chatMessages.size
            refreshUnread()
            recompute()
        }
    }

    /**
     * Builds a [ChatMessage], classifying the author. Lichess posts its own system
     * announcements ("Takeback sent", "Draw offer accepted") into the player room under
     * the literal user "lichess"; those are shown but must not be [ChatMessage.fromOpponent],
     * or the unread badge would light up for the user's own takeback/draw actions.
     */
    private fun chatMessage(username: String, text: String): ChatMessage {
        val system = username.equals(SYSTEM_CHAT_USER, ignoreCase = true)
        return ChatMessage(
            username = username,
            text = text,
            fromOpponent = !system && !username.equals(myUsernameRaw, ignoreCase = true),
            system = system,
        )
    }

    /** Unread = opponent messages sitting past the read high-water mark. */
    private fun refreshUnread() {
        unreadChatCount = chatMessages.drop(chatReadCount).count { it.fromOpponent }
    }

    /**
     * Marks everything currently loaded as read, in memory and in DataStore, so the badge
     * clears and STAYS cleared across restarts. Only called on chat open/close — never per
     * streamed message — to keep DataStore writes rare.
     */
    private fun markChatRead() {
        chatReadCount = chatMessages.size
        chatReadLoaded = true
        unreadChatCount = 0
        val count = chatReadCount
        viewModelScope.launch { settings.setChatReadCount(gameId, count) }
    }

    /** Opens the chat panel and clears the unread count. */
    fun openChat() {
        menuOpen = false
        chatOpen = true
        markChatRead()
        recompute()
        // Cheap re-check in case the stream dropped messages while it was down.
        loadChatHistory()
    }

    fun closeChat() {
        chatOpen = false
        markChatRead()
        recompute()
    }

    /** Sends a chat message to the game's "player" room. Ignores blank text. */
    fun sendChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            when (val res = api.sendChatMessage(gameId, trimmed)) {
                LichessActionResult.Success -> Unit // the stream echoes it back as a chatLine
                is LichessActionResult.Failure -> {
                    message = "Couldn't send message: ${res.error}"
                    recompute()
                }
            }
        }
    }

    private fun applyState(state: BoardStreamEvent.GameState, resetView: Boolean) {
        val oldPositions = replay.positions
        val wasAtLatest = viewIndex == oldPositions.lastIndex

        streamStatus = state.status
        streamWinner = state.winner
        opponentOfferedDraw = if (myColor == Color.WHITE) state.bdraw else state.wdraw
        // Offer withdrawn/resolved -> allow the prompt to show again next time.
        if (!opponentOfferedDraw) drawResponsePending = false

        opponentOfferedTakeback = if (myColor == Color.WHITE) state.btakeback else state.wtakeback
        if (!opponentOfferedTakeback) takebackResponsePending = false

        // Re-derive the whole timeline from the authoritative move list.
        replay = try {
            Chess.replay(state.moves, initialFen, variant)
        } catch (_: Exception) {
            replay
        }
        val newPositions = replay.positions

        viewIndex = if (resetView || wasAtLatest) {
            newPositions.lastIndex
        } else {
            viewIndex.coerceIn(0, newPositions.lastIndex)
        }

        // Our optimistic pending move has landed (or a new move arrived) -> release it.
        val pendingLanded = pendingMove != null && run {
            val last = replay.steps.lastOrNull()
            (last != null && last.move.toUci() == pendingMove!!.toUci()) ||
                newPositions.size > oldPositions.size
        }
        if (pendingLanded) {
            pendingMove = null
            awaitingServer = false
        }

        // Any change in ply count means the board moved under any selection.
        if (newPositions.size != oldPositions.size) {
            clearSelection()
        }

        pendingAnim = when {
            // Already animated at staging time (Fix 1) — re-animating here would yo-yo
            // the piece back to its origin and re-slide, since the static board already
            // shows the destination from the optimistic display.
            pendingLanded -> null
            // First connection to the stream (screen just opened/foregrounded, or a
            // reconnect after backgrounding): animate the arrival of the last move —
            // but only if it's not the SAME move the seed state (or a previous
            // GameFull) already animated, or we'd re-trigger a redundant/stuttering
            // second slide for a move already shown. A genuinely newer last move (e.g.
            // one landed between the home screen's snapshot and the board opening, or
            // while backgrounded) still animates normally.
            resetView -> {
                val lastStep = replay.steps.lastOrNull()
                val newLastUci = lastStep?.move?.toUci()
                val alreadyShown = newLastUci != null && newLastUci == arrivalAnimatedUci
                // The seed animation (built in init{} from the home row's FEN + last-move
                // UCI) can only ever draw a PLAIN slide: it has no move list, so a capture's
                // pre-move board — the thing that keeps the captured piece visible until the
                // capturer lands — can't be reconstructed there (we don't know what was
                // taken). Once the authoritative GameFull arrives we CAN build that capture
                // slide, so upgrade to it even for the SAME move the seed already showed —
                // giving a live game the same captured-piece visibility as the review screen.
                // Only matters for captures; a non-capture's seed slide already matches what
                // buildStepAnim produces, so those still re-emit unchanged (no reset).
                val seedNeedsCaptureUpgrade = alreadyShown &&
                    lastArrivalAnim?.preMoveBoard == null &&
                    lastStep != null && isCaptureMove(lastStep.before, lastStep.move)
                if (alreadyShown && !seedNeedsCaptureUpgrade) {
                    // Same move already shown at full quality (seed, or an earlier connect) —
                    // re-emit the SAME AnimatedMove (see the field doc above) so this doesn't
                    // reset an in-flight slide.
                    lastArrivalAnim
                } else {
                    arrivalAnimatedUci = newLastUci
                    val anim = buildStepAnim(newPositions.lastIndex - 1, newPositions.lastIndex)
                        ?.copy(startDelayMs = ARRIVAL_ANIM_DELAY_MS)
                    lastArrivalAnim = anim
                    anim
                }
            }
            // A move arrived live while browsing the latest position (the opponent's move).
            wasAtLatest && newPositions.size == oldPositions.size + 1 ->
                buildStepAnim(oldPositions.lastIndex, newPositions.lastIndex)
            else -> null
        }

        recompute()
    }

    // ----- history browsing -----
    // (pendingAnim/animCounter now declared above, before init{} — see that comment.)

    /**
     * The slide to animate for a single-step transition [oldIndex] → [newIndex]. Only
     * adjacent steps animate (multi-step jumps and drops snap). Forward: from→to.
     * Backward: to→from (reverse) — the moved piece slides back home. The overlay piece
     * is taken from the DESTINATION position at the landing square, which is exactly the
     * square the static board will hide during the slide.
     */
    private fun buildStepAnim(oldIndex: Int, newIndex: Int): AnimatedMove? {
        val delta = newIndex - oldIndex
        if (delta != 1 && delta != -1) return null
        val step = replay.steps.getOrNull(minOf(oldIndex, newIndex)) ?: return null
        if (step.move.isDrop) return null
        // Captures: forward, slide the capturer in over the pre-move board so the captured
        // piece stays visible until it lands (Atomic then clears the region on settle) —
        // see captureSlideAnim. Backward out of an Atomic capture can't cleanly reverse (the
        // exploded pieces would reappear), so it snaps; a normal backward capture uses the
        // regular reverse slide below (the earlier position already shows the captured piece).
        if (isCaptureMove(step.before, step.move)) {
            if (delta == 1) {
                animCounter += 1
                return captureSlideAnim(step.before, step.after, step.move, animCounter)
            }
            if (step.before.variant == Variant.ATOMIC) return null
        }
        val slides = stepSlides(step, replay.positions[newIndex].board, forward = delta == 1)
        if (slides.isEmpty()) return null
        animCounter += 1
        return AnimatedMove(slides = slides, id = animCounter)
    }

    /**
     * The slide(s) to animate for a move just staged by the user, built directly from
     * the pre-move [before] position and the [move] (it isn't in the replay yet, so
     * [stepSlides]/a [MoveRecord] don't apply). Drops have no board start square and
     * don't animate, matching [stepSlides]'s drop handling.
     */
    private fun buildMoveAnim(before: Position, move: Move): AnimatedMove? {
        if (move.isDrop) return null
        // A capture staged by the user: slide the capturer in over the pre-move board so the
        // captured piece stays visible until it lands (Atomic clears its region on settle).
        if (isCaptureMove(before, move)) {
            animCounter += 1
            return captureSlideAnim(before, safeApply(before, move), move, animCounter)
        }
        val slides = if (move.isCastle) {
            val (kingTo, rookFrom, rookTo) = MoveGenerator.castleSquares(before, move)
            listOfNotNull(
                before.pieceAt(move.from)?.let { PieceSlide(move.from, kingTo, it) },
                before.pieceAt(rookFrom)?.let { PieceSlide(rookFrom, rookTo, it) },
            )
        } else {
            listOfNotNull(before.pieceAt(move.from)?.let { PieceSlide(move.from, move.to, it) })
        }
        if (slides.isEmpty()) return null
        animCounter += 1
        return AnimatedMove(slides = slides, id = animCounter)
    }

    fun stepBack() {
        if (analysisActive) { analysisStepBack(); return }
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex > 0) {
            val old = viewIndex
            viewIndex--
            pendingAnim = buildStepAnim(old, viewIndex)
            clearSelection()
            recompute()
        }
    }

    fun stepForward() {
        if (analysisActive) { analysisStepForward(); return }
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex < replay.positions.lastIndex) {
            val old = viewIndex
            viewIndex++
            pendingAnim = buildStepAnim(old, viewIndex)
            clearSelection()
            recompute()
        }
    }

    fun stepToStart() {
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex != 0) {
            viewIndex = 0
            clearSelection()
            recompute()
        }
    }

    fun stepToEnd() {
        if (pendingMove != null || pendingPromotion != null) return
        val last = replay.positions.lastIndex
        if (viewIndex != last) {
            viewIndex = last
            clearSelection()
            recompute()
        }
    }

    /** Scrub to a position by fraction of the whole game (0 = start, 1 = latest). */
    fun seekToFraction(fraction: Float) {
        if (analysisActive) { analysisSeekToFraction(fraction); return }
        if (pendingMove != null || pendingPromotion != null) return
        val last = replay.positions.lastIndex
        if (last <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)
        if (target != viewIndex) {
            val old = viewIndex
            viewIndex = target
            pendingAnim = buildStepAnim(old, target)
            clearSelection()
            recompute()
        }
    }

    // ----- move interaction -----

    fun onSquareTap(square: Int) {
        if (analysisActive) { onAnalysisSquareTap(square); return }
        val positions = replay.positions
        // Read-only while a move is pending/in-flight, while picking a promotion
        // piece, while reviewing history, when the game is over, or when not our turn.
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex != positions.lastIndex) return
        if (isTerminal()) return
        val latest = replay.finalPosition
        if (latest.sideToMove != myColor) return

        // Crazyhouse: a pocket piece is picked up — this tap chooses where to drop it.
        val drop = selectedDrop
        if (drop != null) {
            if (square in dropTargets) commitDrop(drop, square) else clearSelectionAndRecompute()
            return
        }

        val piece = latest.pieceAt(square)
        val selected = selectedSquare

        if (selected == null) {
            if (piece != null && piece.color == myColor) select(square, latest)
            return
        }

        when {
            square == selected -> clearSelectionAndRecompute()
            square in legalDests -> makeMove(selected, square)
            piece != null && piece.color == myColor -> select(square, latest)
            else -> clearSelectionAndRecompute()
        }
    }

    private fun select(square: Int, position: Position) {
        selectedSquare = square
        legalDests = Chess.legalDestinations(position, square)
        recompute()
    }

    /**
     * Crazyhouse: pick up (or put back) a pocket piece to drop. [color] is the side whose
     * reserves were tapped — always [myColor] in a live game (only my bar is interactive),
     * but in analysis either bar can be the interactive one, since the user plays BOTH
     * sides there (see [BoardUiState.opponentPocketTappable]). A tap on the side that is
     * NOT to move is inert rather than dropping the wrong colour's piece.
     */
    fun onPocketTap(type: PieceType, color: Color) {
        if (analysisActive) { onAnalysisPocketTap(type, color); return }
        if (color != myColor) return
        if (pendingMove != null || pendingPromotion != null) return
        if (viewIndex != replay.positions.lastIndex) return
        if (isTerminal()) return
        val latest = replay.finalPosition
        if (latest.sideToMove != myColor) return
        if (latest.pocket.count(myColor, type) <= 0) return
        if (selectedDrop == type) {
            clearSelectionAndRecompute() // tapping the held piece again puts it back
            return
        }
        clearSelection()
        selectedDrop = type
        dropTargets = Chess.legalDropSquares(latest, type)
        recompute()
    }

    private fun commitDrop(type: PieceType, square: Int) {
        clearSelection()
        stage(Move(from = square, to = square, drop = type))
    }

    private fun makeMove(from: Int, to: Int) {
        val latest = replay.finalPosition
        val isPromotion = latest.pieceAt(from)?.type == PieceType.PAWN &&
            (Square.rank(to) == 0 || Square.rank(to) == 7)
        if (isPromotion) {
            // Hold the move and ask which piece to promote to (BoardScreen shows a picker).
            pendingPromotion = from to to
            clearSelection()
            recompute()
            return
        }
        commitMove(from, to, promotion = null)
    }

    /** User picked a promotion piece from the picker. */
    fun choosePromotion(type: PieceType) {
        if (analysisActive) { chooseAnalysisPromotion(type); return }
        val (from, to) = pendingPromotion ?: return
        pendingPromotion = null
        commitMove(from, to, promotion = type)
    }

    fun cancelPromotion() {
        if (analysisActive) { cancelAnalysisPromotion(); return }
        pendingPromotion = null
        clearSelection()
        recompute()
    }

    private fun commitMove(from: Int, to: Int, promotion: PieceType?) {
        val latest = replay.finalPosition
        val suffix = when (promotion) {
            PieceType.QUEEN -> "q"
            PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"
            PieceType.KNIGHT -> "n"
            else -> ""
        }
        val uci = Square.name(from) + Square.name(to) + suffix
        val move = Chess.parseUci(uci, latest)
        clearSelection()
        if (move == null) {
            recompute()
            return
        }
        stage(move)
    }

    // Stage a move: hold it for confirmation, or submit immediately if confirm-moves is off.
    private fun stage(move: Move) {
        pendingMove = move
        pendingAnim = buildMoveAnim(replay.finalPosition, move)
        if (confirmMoves) {
            awaitingServer = false
            recompute()
        } else {
            awaitingServer = true
            recompute()
            submit(move)
        }
    }

    fun confirmPendingMove() {
        val move = pendingMove ?: return
        if (awaitingServer) return
        awaitingServer = true
        recompute()
        submit(move)
    }

    fun cancelPendingMove() {
        pendingMove = null
        awaitingServer = false
        // Stop the staged move's slide if it's still mid-flight — the move is being
        // undone, so there's nothing to animate to. (Nulling here is a deliberate
        // "cancel this animation", not the incidental wipe recompute used to do.)
        pendingAnim = null
        recompute()
    }

    private fun submit(move: Move) {
        viewModelScope.launch {
            when (val res = api.submitMove(gameId, move.toUci())) {
                LichessActionResult.Success -> Unit // stream will reflect the new position
                is LichessActionResult.Failure -> {
                    pendingMove = null
                    awaitingServer = false
                    message = "Move rejected: ${res.error}"
                    recompute()
                }
            }
        }
    }

    // ----- analysis sandbox -----

    /**
     * Enter the analysis sandbox: snapshot the currently DISPLAYED position (whatever
     * the user is browsing, live latest or a past step) as a fresh local branch, and let
     * them play any legal move against the engine only. Blocked while a live move is
     * staged/awaiting a promotion choice, same as browsing.
     */
    fun enterAnalysis() {
        if (pendingMove != null || pendingPromotion != null) return
        menuOpen = false
        snapshotAnalysis()
        analysisActive = true
        recompute()
    }

    /**
     * Long-press while already in analysis: throw the sandbox line away and start over
     * from a fresh snapshot of the live game. Explicit rather than relying on
     * [enterAnalysis] being re-entrant, so the reset can't be broken by an unrelated
     * change to the entry path. Both share [snapshotAnalysis], so the reset restores the
     * real game's last-move highlight exactly like a first entry does.
     */
    fun resetAnalysis() {
        if (!analysisActive) return
        menuOpen = false
        snapshotAnalysis()
        recompute()
    }

    /** Long-press on the board: enter the sandbox, or reset it if it's already open. */
    fun onBoardLongPress() {
        if (analysisActive) resetAnalysis() else enterAnalysis()
    }

    // Snapshot the live game's currently displayed position as the sandbox's base, and
    // wipe every piece of branch state. Also captures the live board's CURRENT last-move
    // highlight (the same from/to recompute() would render at this viewIndex) so the
    // sandbox's base position keeps showing it instead of starting blank.
    private fun snapshotAnalysis() {
        // Keep the base as a Position (no FEN round-trip) — see analysisBase.
        analysisBase = replay.positions.getOrElse(viewIndex) { replay.finalPosition }
            .let { if (it.variant != variant) it.copy(variant = variant) else it }
        analysisMoves = mutableListOf()
        analysisReplay = Chess.replayFrom(analysisBase, emptyList())
        analysisViewIndex = 0
        // Mirrors recompute()'s own last-move derivation (pendingMove can't be set here —
        // enterAnalysis is blocked while a move is staged).
        when {
            viewIndex > 0 -> {
                val step = replay.steps[viewIndex - 1]
                analysisBaseLastFrom = step.move.from
                analysisBaseLastTo = step.move.to
            }
            // Seed-only state (no move list yet): the home row's last move.
            replay.steps.isEmpty() -> {
                analysisBaseLastFrom = seededLastFrom
                analysisBaseLastTo = seededLastTo
            }
            // Viewing the game's own start position — no last move to show.
            else -> {
                analysisBaseLastFrom = null
                analysisBaseLastTo = null
            }
        }
        clearAnalysisSelection()
        analysisPendingPromotion = null
        analysisPendingAnim = null
    }

    /** Back-press while in analysis just leaves the sandbox — see [onBackPressed]. */
    fun exitAnalysis() {
        analysisActive = false
        clearAnalysisSelection()
        analysisPendingPromotion = null
        analysisPendingAnim = null
        recompute()
    }

    /**
     * The analysis position currently on screen — where the side to move may play. A move
     * from here truncates the line (see [applyAnalysisMove]), so this is the position all
     * the analysis tap handlers read, NOT the tip.
     */
    private fun analysisViewed(): Position {
        val positions = analysisReplay.positions
        return positions[analysisViewIndex.coerceIn(0, positions.lastIndex)]
    }

    // Browse the analysis branch's own move history — mirrors stepBack/stepForward for
    // the live game. Unlike the live game, browsing back here is not read-only: playing a
    // move from a past analysis position forks the line (truncate-and-replace).
    private fun analysisStepBack() {
        if (analysisPendingPromotion != null) return
        if (analysisViewIndex > 0) {
            val old = analysisViewIndex
            analysisViewIndex--
            analysisPendingAnim = buildAnalysisStepAnim(old, analysisViewIndex)
            clearAnalysisSelection()
            recompute()
        }
    }

    private fun analysisStepForward() {
        if (analysisPendingPromotion != null) return
        if (analysisViewIndex < analysisReplay.positions.lastIndex) {
            val old = analysisViewIndex
            analysisViewIndex++
            analysisPendingAnim = buildAnalysisStepAnim(old, analysisViewIndex)
            clearAnalysisSelection()
            recompute()
        }
    }

    private fun analysisSeekToFraction(fraction: Float) {
        if (analysisPendingPromotion != null) return
        val last = analysisReplay.positions.lastIndex
        if (last <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)
        if (target != analysisViewIndex) {
            val old = analysisViewIndex
            analysisViewIndex = target
            analysisPendingAnim = buildAnalysisStepAnim(old, target)
            clearAnalysisSelection()
            recompute()
        }
    }

    private fun onAnalysisSquareTap(square: Int) {
        if (analysisPendingPromotion != null) return
        // Moves are played from the VIEWED position, not the tip: stepping back and
        // playing something different truncates the line there and continues from it
        // (see applyAnalysisMove) — the standard lightweight-analysis behaviour.
        val pos = analysisViewed()

        val drop = analysisSelectedDrop
        if (drop != null) {
            if (square in analysisDropTargets) commitAnalysisDrop(drop, square) else clearAnalysisSelectionAndRecompute()
            return
        }

        val piece = pos.pieceAt(square)
        val selected = analysisSelectedSquare

        if (selected == null) {
            if (piece != null && piece.color == pos.sideToMove) selectAnalysis(square, pos)
            return
        }

        when {
            square == selected -> clearAnalysisSelectionAndRecompute()
            square in analysisLegalDests -> makeAnalysisMove(pos, selected, square)
            piece != null && piece.color == pos.sideToMove -> selectAnalysis(square, pos)
            else -> clearAnalysisSelectionAndRecompute()
        }
    }

    private fun selectAnalysis(square: Int, pos: Position) {
        analysisSelectedSquare = square
        analysisLegalDests = Chess.legalDestinations(pos, square)
        recompute()
    }

    private fun onAnalysisPocketTap(type: PieceType, color: Color) {
        if (analysisPendingPromotion != null) return
        // Drops, like board moves, come from the VIEWED position — the pocket shown is
        // that position's, so dropping from a stepped-back position forks the line there.
        val pos = analysisViewed()
        // The two bars stay put (bottom = me, top = opponent) so the board never jumps
        // mid-line; instead the INTERACTIVE one follows the side to move. A tap on the
        // other bar is inert — never a silent wrong-colour drop.
        if (color != pos.sideToMove) return
        if (pos.pocket.count(pos.sideToMove, type) <= 0) return
        if (analysisSelectedDrop == type) {
            clearAnalysisSelectionAndRecompute()
            return
        }
        clearAnalysisSelection()
        analysisSelectedDrop = type
        analysisDropTargets = Chess.legalDropSquares(pos, type)
        recompute()
    }

    private fun commitAnalysisDrop(type: PieceType, square: Int) {
        clearAnalysisSelection()
        applyAnalysisMove(Move(from = square, to = square, drop = type))
    }

    private fun makeAnalysisMove(pos: Position, from: Int, to: Int) {
        val isPromotion = pos.pieceAt(from)?.type == PieceType.PAWN &&
            (Square.rank(to) == 0 || Square.rank(to) == 7)
        if (isPromotion) {
            analysisPendingPromotion = from to to
            clearAnalysisSelection()
            recompute()
            return
        }
        commitAnalysisMove(pos, from, to, promotion = null)
    }

    private fun chooseAnalysisPromotion(type: PieceType) {
        val (from, to) = analysisPendingPromotion ?: return
        analysisPendingPromotion = null
        // analysisViewIndex can't move while a promotion is pending (the step/scrub
        // handlers bail on analysisPendingPromotion), so the viewed position is still the
        // one the pawn was picked up from — including when that's mid-line (a fork).
        commitAnalysisMove(analysisViewed(), from, to, promotion = type)
    }

    private fun cancelAnalysisPromotion() {
        analysisPendingPromotion = null
        clearAnalysisSelection()
        recompute()
    }

    private fun commitAnalysisMove(pos: Position, from: Int, to: Int, promotion: PieceType?) {
        val suffix = when (promotion) {
            PieceType.QUEEN -> "q"
            PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"
            PieceType.KNIGHT -> "n"
            else -> ""
        }
        val uci = Square.name(from) + Square.name(to) + suffix
        val move = Chess.parseUci(uci, pos)
        clearAnalysisSelection()
        if (move == null) {
            recompute()
            return
        }
        applyAnalysisMove(move)
    }

    // Apply a legal move to the analysis branch (engine-only, never submitted to
    // Lichess), rebuild its local replay, and animate the move exactly like the
    // in-person local game — a one-shot slide, nulled right after this render.
    //
    // TRUNCATE-AND-REPLACE: the move is played from the VIEWED position, so anything
    // after it is discarded before appending — the new move becomes the tip. (Position
    // index i == "after i moves", so analysisViewIndex indexes analysisMoves directly;
    // when already at the tip, analysisViewIndex == analysisMoves.size and the truncation
    // is a no-op.) This is a single line, not a variation tree: there's no UI to navigate
    // siblings, and the app's whole ethos is against adding one. Every derived value
    // (canStepBack/Forward, viewFraction, totalPlies, the scrub mapping, material and
    // pockets) is recomputed from the shortened-then-extended replay in recomputeAnalysis,
    // so nothing can be left pointing past the new tip.
    private fun applyAnalysisMove(move: Move) {
        val oldIndex = analysisViewIndex
        if (oldIndex < analysisMoves.size) analysisMoves.subList(oldIndex, analysisMoves.size).clear()
        analysisMoves.add(move.toUci())
        analysisReplay = Chess.replayFrom(analysisBase, analysisMoves)
        analysisViewIndex = analysisReplay.positions.lastIndex
        // Always a single forward step from where the user was (oldIndex + 1 == the new
        // tip after truncation), so the fork animates like any other move.
        analysisPendingAnim = buildAnalysisStepAnim(oldIndex, analysisViewIndex)
        clearAnalysisSelection()
        recompute()
    }

    // Bidirectional version of the forward-only original — stepping/scrubbing backward
    // through the analysis branch's own history needs the reverse slide too (mirrors
    // buildStepAnim for the live/local games).
    private fun buildAnalysisStepAnim(oldIndex: Int, newIndex: Int): AnimatedMove? {
        val delta = newIndex - oldIndex
        if (delta != 1 && delta != -1) return null
        val step = analysisReplay.steps.getOrNull(minOf(oldIndex, newIndex)) ?: return null
        if (step.move.isDrop) return null
        if (isCaptureMove(step.before, step.move)) {
            if (delta == 1) {
                analysisAnimCounter += 1
                return captureSlideAnim(step.before, step.after, step.move, analysisAnimCounter)
            }
            if (step.before.variant == Variant.ATOMIC) return null
        }
        val slides = stepSlides(step, analysisReplay.positions[newIndex].board, forward = delta == 1)
        if (slides.isEmpty()) return null
        analysisAnimCounter += 1
        return AnimatedMove(slides = slides, id = analysisAnimCounter)
    }

    private fun clearAnalysisSelection() {
        analysisSelectedSquare = null
        analysisLegalDests = emptySet()
        analysisSelectedDrop = null
        analysisDropTargets = emptySet()
    }

    private fun clearAnalysisSelectionAndRecompute() {
        clearAnalysisSelection()
        recompute()
    }

    // ----- menu / confirmations -----

    // No-op in the analysis sandbox: every menu action targets the real Lichess game, so
    // the menu is hidden there (BoardScreen drops the icon entirely). Guarded here too so
    // the overlay can't be re-opened by any other path while analysis is active.
    fun openMenu() { if (analysisActive) return; menuOpen = true; recompute() }
    fun closeMenu() { menuOpen = false; recompute() }

    fun requestResign() { menuOpen = false; confirmation = Confirmation.RESIGN; recompute() }
    fun requestAbort() { menuOpen = false; confirmation = Confirmation.ABORT; recompute() }
    fun requestDraw() { menuOpen = false; confirmation = Confirmation.DRAW; recompute() }
    fun requestTakeback() { menuOpen = false; confirmation = Confirmation.TAKEBACK; recompute() }
    fun cancelConfirmation() { confirmation = null; recompute() }

    // This screen only ever backs a real, streamed Lichess (online) game — back should go
    // straight home with no confirmation (unlike the local pass-and-play screen, which
    // confirms because it would silently lose in-person progress). Analysis still takes
    // priority: back just leaves the sandbox and returns to the live game view.
    override fun onBackPressed(): Boolean {
        if (analysisActive) { exitAnalysis(); return true }
        return false
    }

    fun confirmResign() {
        confirmation = null
        recompute()
        viewModelScope.launch {
            val res = api.resignGame(gameId)
            if (res is LichessActionResult.Failure) {
                message = "Couldn't resign: ${res.error}"
                recompute()
            }
        }
    }

    fun confirmAbort() {
        confirmation = null
        recompute()
        viewModelScope.launch {
            val res = api.abortGame(gameId)
            if (res is LichessActionResult.Failure) {
                message = "Couldn't abort: ${res.error}"
                recompute()
            }
        }
    }

    fun confirmDraw() {
        confirmation = null
        recompute()
        viewModelScope.launch {
            when (val res = api.handleDraw(gameId, accept = true)) {
                LichessActionResult.Success -> { message = "Draw offer sent"; recompute() }
                is LichessActionResult.Failure -> { message = "Couldn't offer draw: ${res.error}"; recompute() }
            }
        }
    }

    // ----- responding to an incoming draw offer -----

    fun acceptIncomingDraw() = respondToDraw(accept = true, failNote = "Couldn't accept draw")
    fun declineIncomingDraw() = respondToDraw(accept = false, failNote = "Couldn't decline draw")

    private fun respondToDraw(accept: Boolean, failNote: String) {
        if (drawResponsePending) return
        drawResponsePending = true
        recompute() // hides the prompt immediately
        viewModelScope.launch {
            val res = api.handleDraw(gameId, accept = accept)
            if (res is LichessActionResult.Failure) {
                drawResponsePending = false
                message = "$failNote: ${res.error}"
                recompute()
            }
            // On success the stream reflects the resolution (draw -> terminal, or offer cleared).
        }
    }

    fun confirmTakeback() {
        confirmation = null
        recompute()
        viewModelScope.launch {
            when (val res = api.takeback(gameId, accept = true)) {
                LichessActionResult.Success -> { message = "Takeback requested"; recompute() }
                is LichessActionResult.Failure -> { message = "Couldn't request takeback: ${res.error}"; recompute() }
            }
        }
    }

    // ----- responding to an incoming takeback offer -----

    fun acceptIncomingTakeback() = respondToTakeback(accept = true, failNote = "Couldn't accept takeback")
    fun declineIncomingTakeback() = respondToTakeback(accept = false, failNote = "Couldn't decline takeback")

    private fun respondToTakeback(accept: Boolean, failNote: String) {
        if (takebackResponsePending) return
        takebackResponsePending = true
        recompute() // hides the prompt immediately
        viewModelScope.launch {
            val res = api.takeback(gameId, accept = accept)
            if (res is LichessActionResult.Failure) {
                takebackResponsePending = false
                message = "$failNote: ${res.error}"
                recompute()
            }
            // On success the stream reflects the resolution (move list shrinks, or offer cleared).
        }
    }

    // ----- PGN export -----
    // v1: PGN export disabled — may re-add
    // fun copyPgn() { menuOpen = false; recompute(); fetchPgn(PgnDelivery.CLIPBOARD) }
    // fun sharePgn() { menuOpen = false; recompute(); fetchPgn(PgnDelivery.SHARE) }
    //
    // private fun fetchPgn(delivery: PgnDelivery) {
    //     viewModelScope.launch {
    //         try {
    //             _pgnEvent.value = PgnEvent(api.exportGamePgn(gameId), delivery)
    //         } catch (e: Exception) {
    //             message = "Couldn't fetch PGN"
    //             recompute()
    //         }
    //     }
    // }
    //
    // /** UI reports how it delivered the PGN. [note] (if any) is surfaced to the user. */
    // fun onPgnDelivered(note: String?) {
    //     _pgnEvent.value = null
    //     if (note != null) { message = note; recompute() }
    // }

    // ----- messages -----

    fun dismissMessage() { message = null; recompute() }

    // ----- helpers -----

    private fun clearSelection() {
        selectedSquare = null
        legalDests = emptySet()
        selectedDrop = null
        dropTargets = emptySet()
    }

    private fun clearSelectionAndRecompute() {
        clearSelection()
        recompute()
    }

    private fun isTerminal(): Boolean {
        val engine = Chess.status(replay.finalPosition)
        return engine.isTerminal || streamStatus.lowercase() !in LIVE_STATUSES
    }

    private fun recompute() {
        if (analysisActive) {
            recomputeAnalysis()
            return
        }
        val positions = replay.positions
        viewIndex = viewIndex.coerceIn(0, positions.lastIndex)
        val latest = replay.finalPosition

        val displayPosition = pendingMove?.let { safeApply(latest, it) } ?: positions[viewIndex]
        val terminal = isTerminal()
        val isMyTurn = !terminal && latest.sideToMove == myColor

        // Last-move highlight follows whatever produced the displayed position.
        var lastFrom: Int? = null
        var lastTo: Int? = null
        when {
            pendingMove != null -> {
                lastFrom = pendingMove!!.from
                lastTo = pendingMove!!.to
            }
            viewIndex > 0 -> {
                val step = replay.steps[viewIndex - 1]
                lastFrom = step.move.from
                lastTo = step.move.to
            }
            // Seed-only state (no move list yet): use the last move passed by the home
            // row so the highlight doesn't flash in when the stream arrives.
            replay.steps.isEmpty() -> {
                lastFrom = seededLastFrom
                lastTo = seededLastTo
            }
        }

        // Don't reveal check/checkmate for a move the user hasn't confirmed yet.
        val moveUnconfirmed = pendingMove != null && !awaitingServer
        val displayStatus = Chess.status(displayPosition)
        val isCheck = !moveUnconfirmed && displayStatus is GameStatus.Check
        val isCheckmate = !moveUnconfirmed && displayStatus is GameStatus.Checkmate
        val checkedKing = if (isCheck) {
            displayPosition.kingSquare(displayPosition.sideToMove).takeIf { it >= 0 }
        } else {
            null
        }
        val checkmateKing = if (isCheckmate) {
            displayPosition.kingSquare(displayPosition.sideToMove).takeIf { it >= 0 }
        } else {
            null
        }

        val subtitle = if (terminal) {
            resultSubtitle(latest)
        } else {
            buildString {
                // Lead with the variant name for non-standard games, before the turn.
                if (variant != Variant.STANDARD) append("${variant.displayName} · ")
                append(if (isMyTurn) "your move" else "their move")
                if (opponentOfferedDraw) append(" · draw offered")
            }
        }

        val mode = if (pendingMove != null && !awaitingServer) BottomMode.PENDING else BottomMode.BROWSE

        // Lichess rejects draw offers before both players have moved (>= 2 plies).
        val canOfferDraw = !terminal && replay.steps.size >= 2
        // Lichess only allows aborting before both players have moved (< 2 plies).
        val canAbort = !terminal && replay.steps.size < 2
        val incomingDrawOffer = opponentOfferedDraw && !drawResponsePending && !terminal
        // At least one move must have been played to take one back; the server enforces
        // the finer-grained "it must be your opponent's move you're taking back" rule.
        val canRequestTakeback = !terminal && replay.steps.isNotEmpty()
        val incomingTakebackOffer = opponentOfferedTakeback && !takebackResponsePending && !terminal

        // Compare against the variant's canonical starting complement (not
        // replay.positions.first()) so material is stable from the very first seeded
        // frame: during the seed-only state the replay's "first" position IS the
        // current position, which would otherwise read as zero captures and then
        // flash to the real count when the move list streams in. Every non-fromPosition
        // game has identical starting piece counts to this reference, so no flash.
        val material = computeMaterial(referenceStart(), displayPosition, variant, myColor)

        _uiState.value = BoardUiState(
            opponentName = opponentName,
            opponentUsername = opponentUsernameRaw,
            subtitle = subtitle,
            board = displayPosition.board,
            myColor = myColor,
            flipped = myColor == Color.BLACK,
            selectedSquare = selectedSquare,
            legalDestinations = if (showLegalMoves) legalDests else emptySet(),
            lastMoveFrom = lastFrom,
            lastMoveTo = lastTo,
            checkedKingSquare = checkedKing,
            checkmateKingSquare = checkmateKing,
            promotionActive = pendingPromotion != null,
            mode = mode,
            canStepBack = viewIndex > 0,
            canStepForward = viewIndex < positions.lastIndex,
            terminal = terminal,
            menuOpen = menuOpen,
            confirmation = confirmation,
            canOfferDraw = canOfferDraw,
            canAbort = canAbort,
            incomingDrawOffer = incomingDrawOffer,
            canRequestTakeback = canRequestTakeback,
            incomingTakebackOffer = incomingTakebackOffer,
            unsupportedVariant = unsupportedVariant,
            variant = variant,
            myPocket = displayPosition.pocket.forColor(myColor),
            opponentPocket = displayPosition.pocket.forColor(myColor.opposite),
            selectedDrop = selectedDrop,
            dropTargets = if (showLegalMoves) dropTargets else emptySet(),
            goalSquares = goalSquares(),
            checkCounts = threeCheckCounts(positions, viewIndex, variant),
            myCaptured = material.myCaptured,
            opponentCaptured = material.opponentCaptured,
            myAdvantage = material.myAdvantage,
            opponentAdvantage = material.opponentAdvantage,
            viewFraction = if (positions.size > 1) viewIndex.toFloat() / positions.lastIndex else 1f,
            totalPlies = positions.lastIndex,
            animatingMove = pendingAnim,
            message = message,
            chatMessages = chatMessages.toList(),
            chatOpen = chatOpen,
            unreadChatCount = unreadChatCount,
        )
        // NOTE: pendingAnim is intentionally NOT nulled here — it persists so unrelated
        // recomputes re-emit the same id (a UI no-op), rather than flipping it to null and
        // resetting an in-flight slide. See the pendingAnim declaration for the full why.
    }

    // Renders the analysis sandbox: a completely local, engine-only branch (see
    // `analysisReplay`), shown at `analysisViewIndex` — the user can step/scrub back
    // through the moves THEY'VE played in the sandbox (BottomMode stays BROWSE, same as
    // the live/local games), and play a different move from any of them, which forks the
    // line there (see applyAnalysisMove). The live game's `subtitle`/draw/takeback/abort
    // affordances are all irrelevant here (they'd operate on the real Lichess game), so
    // they're zeroed/hidden — as is the action menu itself (BoardScreen hides its icon
    // while `analysisActive`); `analysisActive = true` is also what tells BoardScreen to
    // swap the top-bar title.
    private fun recomputeAnalysis() {
        val positions = analysisReplay.positions
        val idx = analysisViewIndex.coerceIn(0, positions.lastIndex)
        val pos = positions[idx]

        val status = Chess.status(pos)
        val isCheck = status is GameStatus.Check
        val isCheckmate = status is GameStatus.Checkmate
        val checkedKing = if (isCheck) pos.kingSquare(pos.sideToMove).takeIf { it >= 0 } else null
        val checkmateKing = if (isCheckmate) pos.kingSquare(pos.sideToMove).takeIf { it >= 0 } else null

        var lastFrom: Int? = null
        var lastTo: Int? = null
        if (idx > 0) {
            val step = analysisReplay.steps[idx - 1]
            lastFrom = step.move.from
            lastTo = step.move.to
        } else {
            // Sitting on the snapshot the sandbox branched from (on entry, after a
            // long-press reset, or having stepped all the way back): show the REAL
            // game's last move, the highlight the live board was showing when analysis
            // was entered. Consistent with the codebase's backward-step convention —
            // stepping back out of a move re-reveals the indicators on the move before it.
            lastFrom = analysisBaseLastFrom
            lastTo = analysisBaseLastTo
        }

        // Material stays relative to the FIXED bottom side (myColor) exactly like the
        // live board — the board's orientation doesn't change in analysis, only who is
        // allowed to move (either side, via `pos.sideToMove` in the tap handlers above).
        val material = computeMaterial(referenceStart(), pos, analysisReplay.initial.variant, myColor)

        _uiState.value = BoardUiState(
            opponentName = opponentName,
            opponentUsername = opponentUsernameRaw,
            subtitle = "",
            board = pos.board,
            myColor = myColor,
            flipped = myColor == Color.BLACK,
            moverColor = pos.sideToMove,
            selectedSquare = analysisSelectedSquare,
            legalDestinations = if (showLegalMoves) analysisLegalDests else emptySet(),
            lastMoveFrom = lastFrom,
            lastMoveTo = lastTo,
            checkedKingSquare = checkedKing,
            checkmateKingSquare = checkmateKing,
            promotionActive = analysisPendingPromotion != null,
            mode = BottomMode.BROWSE,
            canStepBack = idx > 0,
            canStepForward = idx < positions.lastIndex,
            terminal = status.isTerminal,
            // The action menu is meaningless in the sandbox (resign/draw/takeback/abort/
            // chat all act on the real Lichess game), so it's force-closed here as well as
            // being unreachable — openMenu() no-ops and BoardScreen hides the icon.
            menuOpen = false,
            confirmation = null,
            canOfferDraw = false,
            canAbort = false,
            incomingDrawOffer = false,
            canRequestTakeback = false,
            incomingTakebackOffer = false,
            unsupportedVariant = null,
            variant = analysisReplay.initial.variant,
            myPocket = pos.pocket.forColor(myColor),
            opponentPocket = pos.pocket.forColor(myColor.opposite),
            // Either side may move in the sandbox, so the tappable bar follows the side to
            // move rather than always being mine (which would drop the wrong colour).
            opponentPocketTappable = pos.sideToMove != myColor,
            selectedDrop = analysisSelectedDrop,
            dropTargets = if (showLegalMoves) analysisDropTargets else emptySet(),
            goalSquares = goalSquaresFor(analysisReplay.initial.variant),
            checkCounts = threeCheckCounts(positions, idx, analysisReplay.initial.variant),
            myCaptured = material.myCaptured,
            opponentCaptured = material.opponentCaptured,
            myAdvantage = material.myAdvantage,
            opponentAdvantage = material.opponentAdvantage,
            viewFraction = if (positions.size > 1) idx.toFloat() / positions.lastIndex else 1f,
            totalPlies = positions.lastIndex,
            animatingMove = analysisPendingAnim,
            message = null,
            analysisActive = true,
            chatMessages = chatMessages.toList(),
            chatOpen = chatOpen,
            unreadChatCount = unreadChatCount,
        )
        // Analysis has no background stream re-triggering recomputes, so — unlike the
        // live game's pendingAnim — it's safe to null this immediately (one-shot).
        analysisPendingAnim = null
    }

    // The variant's canonical starting position, used as the stable reference for the
    // material count (see the call site). Cheap to build; falls back to the standard
    // start if the variant's start FEN somehow fails to parse.
    private fun referenceStart(): Position =
        runCatching { variant.startFen?.let { Position.fromFen(it, variant) } ?: Chess.startPosition }
            .getOrDefault(Chess.startPosition)

    // Squares the active variant highlights as its goal — see [goalSquaresFor].
    private fun goalSquares(): Set<Int> = goalSquaresFor(variant)

    private fun safeApply(position: Position, move: Move): Position =
        try {
            Chess.applyMove(position, move)
        } catch (_: Exception) {
            position
        }

    private fun resultSubtitle(latest: Position): String {
        val engine = Chess.status(latest)
        val s = streamStatus.lowercase()
        // The winner is authoritative from the stream (the same source the review screen
        // trusts via game.winner). Fall back to the engine only for a genuine decisive
        // result: a standard checkmate (loser = side to move), or a variant that ends
        // with a king removed (Atomic explosion) — which the standard evaluator otherwise
        // misreads as stalemate, so we must NEVER infer a draw from that. This keeps the
        // board's game-over text consistent with the history/review screen.
        val winnerColor: Color? = when {
            streamWinner.equals("white", ignoreCase = true) -> Color.WHITE
            streamWinner.equals("black", ignoreCase = true) -> Color.BLACK
            engine is GameStatus.Checkmate -> latest.sideToMove.opposite
            latest.kingSquare(Color.WHITE) < 0 -> Color.BLACK
            latest.kingSquare(Color.BLACK) < 0 -> Color.WHITE
            else -> null
        }
        if (winnerColor != null) {
            val outcome = if (winnerColor == myColor) "you won" else "you lost"
            return when {
                s == "mate" || engine is GameStatus.Checkmate -> "checkmate · $outcome"
                s == "resign" -> "resigned · $outcome"
                s == "outoftime" || s == "timeout" -> "time out · $outcome"
                // variantEnd wins (atomic king explosion, three-check, KotH, racing kings,
                // antichess, …) have no single mate/resign word — state the outcome plainly.
                else -> outcome
            }
        }
        // No winner → drawn / aborted / not yet resolved.
        return when {
            s == "stalemate" || engine is GameStatus.Stalemate -> "stalemate · draw"
            s == "draw" || engine is GameStatus.Draw -> "draw"
            s == "aborted" -> "game aborted"
            else -> "game over"
        }
    }

    private companion object {
        // Stream statuses that mean the game is still in progress.
        val LIVE_STATUSES = setOf("", "started", "created")

        // Every variant the engine now handles. Anything outside this set (an
        // unknown/future Lichess variant) falls back to the "not supported" screen.
        // "fromposition" is standard rules from a custom FEN, so it plays normally.
        val KNOWN_VARIANTS = setOf(
            "standard", "fromposition", "chess960", "crazyhouse", "atomic",
            "kingofthehill", "threecheck", "antichess", "racingkings", "horde",
        )
    }
}
