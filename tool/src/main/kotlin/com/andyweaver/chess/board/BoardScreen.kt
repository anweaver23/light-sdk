package com.andyweaver.chess.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
// v1: PGN export disabled — may re-add
// import android.content.ClipData
// import androidx.compose.ui.platform.ClipEntry
// import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt
import com.andyweaver.chess.engine.Color as EngineColor
import com.andyweaver.chess.engine.Piece
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Square
import com.andyweaver.chess.engine.Variant
import com.andyweaver.chess.R
import com.andyweaver.chess.lichess.LichessApi
import com.andyweaver.chess.settings.ChessSettings
import com.andyweaver.chess.settings.MoveStepSpeed
import com.andyweaver.chess.ui.ChessTheme
import com.andyweaver.chess.ui.holdRepeat
import com.andyweaver.chess.ui.tapHaptic
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.scrollBarGutterUnits

private val BOARD_DARK = Color(0xFF1B1B1B)
private val BOARD_LIGHT = Color(0xFF5B5B5B)

// Every indicator mark (dots, legal-move ring, last-move box, selection box) shares
// this shade; the legal-move dot additionally gets a thin contact outline.
private val MARK_SHADE = Color(0xFFB7B7B7)
private val DOT_OUTLINE = Color(0x8C000000)

// Monochrome only — no colour accents. Check and the variant goal-square outline both
// reuse MARK_SHADE via a dashed border (see [DashedRegionBorder]); checkmate uses no
// border at all (see CHECKMATE_KING_ROTATION).
private const val DASH_STROKE_WIDTH_DP = 1f
private const val DASH_ON_DP = 6f
private const val DASH_GAP_DP = 4f

// The check/checkmate border "marches" continuously (dash phase cycles) to read as
// live/urgent — one full dash+gap cycle per this duration. The goal-square border is
// stationary (phase 0) since it just marks fixed squares, not an ongoing threat.
private const val CHECK_MARCH_MS = 600

// Checkmate gets BOTH the marching border (same as check) AND the king turned onto its
// side. The rotation animates at a normal move slide's pace (MOVE_ANIM_MS), just a
// rotation instead of a translation.
private const val CHECKMATE_KING_ROTATION = 90f

// Skip-to-start/end bar height, in grid units. The nav arrows render inside a 2f
// box but the visible glyph is padded within it, so a full-2f bar looks taller
// than the arrows. This is tuned to match the arrow's visible height — tweak here.
private const val SKIP_BAR_HEIGHT_UNITS = 1.4f

// Fraction of a square a piece glyph occupies (Fit-scaled, centred). Tune to taste.
private const val PIECE_SCALE = 0.82f

// Duration of a piece slide when stepping/scrubbing between positions (ms).
private const val MOVE_ANIM_MS = 180

// Extra pause before an "arrival" slide starts (opening a live game/review on its last
// move) — long enough to register as a deliberate beat, not sluggish. Internal (not
// private) so BoardViewModel/ReviewViewModel, which build the arrival AnimatedMoves,
// can set it via AnimatedMove.copy(startDelayMs = ARRIVAL_ANIM_DELAY_MS).
internal const val ARRIVAL_ANIM_DELAY_MS = 350

// Move scrubbing advances a FIXED number of moves per unit of drag, INDEPENDENT of the
// game's length — so a 200-move game scrubs at the same feel as a 20-move one. (The old
// mapping put the whole game under one bar-width swipe, which got unusably twitchy in long
// games: a tiny finger movement jumped many moves.)
//
// Calibration, as one speed knob plus a reference span:
//   • a drag of SCRUB_SWEEP_FRACTION of the track's length advances SCRUB_SWEEP_PLIES moves.
// That fixes the rate (moves per pixel). A game longer than SCRUB_SWEEP_PLIES just needs
// more than one sweep to cross; a shorter one, less. Tune SCRUB_SWEEP_FRACTION for overall
// speed (higher = slower/finer) — Andy's starting point is 0.75.
private const val SCRUB_SWEEP_FRACTION = 0.75f
private const val SCRUB_SWEEP_PLIES = 40f

// ---- The vertical move-scrub bar (MoveScrubBar) --------------------------------------
// Drawn in the gutter beside the board, so it costs the board no space: the board is a
// fixed square centred by two equal-weight gutters (see CenteredBoard), and this occupies
// the right one. Andy's call over the alternative of scrubbing on the bottom bar, which
// fought the browse arrows' press-and-hold repeat for the same finger.

/** Width of the drawn track. The TOUCHABLE width is the whole gutter — see [MoveScrubBar]. */
private val SCRUB_TRACK_WIDTH = 3.dp

/** Gap between the board's edge and the track. */
private const val SCRUB_TRACK_GAP_UNITS = 0.4f

/** Height of the thumb marking the current position. */
private const val SCRUB_THUMB_HEIGHT_UNITS = 1.6f

/** Track brightness. The thumb draws at full [MARK_SHADE]; the track sits behind it. */
private const val SCRUB_TRACK_ALPHA = 0.35f

// Chat: the small text size shared by a message's sender name and by Lichess's own
// system notes, both of which are also drawn dimmed (`lighten`). The message BODY
// stays at Copy — only the surrounding labels shrink.
private val CHAT_NAME_VARIANT = LightTextVariant.Detail

// Chat feed rhythm, in grid units. A single Arrangement.spacedBy can't express this —
// the gap depends on what the item ABOVE was — so each item draws its own top Spacer
// (see chatGapUnits). All four are pure spacing; tune freely.
//   • different speakers  — the normal beat between two people's messages
//   • same speaker        — consecutive messages from one person read as one block
//                           (the repeated sender name is also suppressed, see below)
//   • around system       — breathing room above/below a run of Lichess's own notes
//   • between system      — inside such a run, tight so it reads as one block
private const val CHAT_GAP_NEW_SPEAKER_UNITS = 1.75f
private const val CHAT_GAP_SAME_SPEAKER_UNITS = 0.45f
private const val CHAT_GAP_AROUND_SYSTEM_UNITS = 2.25f
private const val CHAT_GAP_BETWEEN_SYSTEM_UNITS = 0.9f

// Fraction of the text column a single chat message may occupy before it wraps. Tunable:
// lower reads as more obviously "one side", higher wastes less line. 0.8 leaves roughly a
// fifth of the width clear on the opposite side, which is enough to read the alignment at a
// glance without making longer messages ragged.
private const val CHAT_MESSAGE_MAX_WIDTH_FRACTION = 0.8f

/**
 * The live board screen for one Lichess correspondence game.
 *
 * Constructed by the home screen when a game row is tapped:
 * ```
 * navigateTo({ sa -> BoardScreen(sa, game.gameId, token, game.color) })
 * ```
 * where `game.color` is "white" or "black".
 */
class BoardScreen(
    sealedActivity: SealedLightActivity,
    private val gameId: String,
    private val token: String,
    private val myColorName: String,
    // Chrome only: the opponent's display name from the home row, so the top bar reads
    // correctly while the board itself waits for the authoritative game state. The BOARD
    // is never seeded — see BoardUiState.boardReady.
    private val seededOpponentName: String? = null,
    private val seededVariant: Variant = Variant.STANDARD,
) : LightScreen<Unit, BoardViewModel>(sealedActivity) {

    override val viewModelClass: Class<BoardViewModel>
        get() = BoardViewModel::class.java

    override fun createViewModel(): BoardViewModel {
        val color = if (myColorName.equals("black", ignoreCase = true)) {
            EngineColor.BLACK
        } else {
            EngineColor.WHITE
        }
        return BoardViewModel(
            api = LichessApi(token),
            settings = ChessSettings(lightContext.dataStore),
            gameId = gameId,
            myColor = color,
            seededOpponentName = seededOpponentName,
            seededVariant = seededVariant,
        )
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        // v1: PGN export disabled — may re-add
        // val pgnEvent by viewModel.pgnEvent.collectAsState()
        //
        // val clipboard = LocalClipboard.current
        //
        // // PGN delivery happens in the UI layer via the clipboard. LightOS does not
        // // permit launching a share/email intent (startActivity is disallowed by the
        // // SDK), so "Copy PGN" writes the PGN to the clipboard for the user to paste
        // // wherever they like. Uses the modern Clipboard API (suspend setClipEntry);
        // // note the Android 13+ system "copied" confirmation is shown by the OS and
        // // can't be suppressed by the app.
        // LaunchedEffect(pgnEvent) {
        //     val event = pgnEvent ?: return@LaunchedEffect
        //     clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("PGN", event.pgn)))
        //     viewModel.onPgnDelivered("PGN copied to clipboard")
        // }

        ChessTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // An unresolved draw/takeback offer takes the top bar over entirely,
                    // leaving the board and the browse bar below it live so the position can
                    // still be stepped through while deciding. A draw offer wins if both are
                    // somehow outstanding; another overlay owning the screen defers the
                    // prompt, which resurfaces as soon as that closes.
                    val offerSuppressed = state.menuOpen ||
                        state.confirmation != null ||
                        state.promotionActive
                    val drawOffer = state.incomingDrawOffer && !offerSuppressed
                    val takebackOffer = state.incomingTakebackOffer &&
                        !state.incomingDrawOffer &&
                        !offerSuppressed
                    // A threefold claim ranks below both real offers: those are the
                    // opponent waiting on an answer, this is only an opportunity.
                    val claimDraw = state.claimDrawAvailable &&
                        !state.incomingDrawOffer &&
                        !state.incomingTakebackOffer &&
                        !offerSuppressed
                    val offerActive = drawOffer || takebackOffer || claimDraw

                    // Hand-rolled rightButton instead of LightTopBar's own slot: that slot
                    // renders via an SDK-internal button view that takes no custom Modifier,
                    // so it can't carry the unread-count badge (see HomeScreen's manual
                    // refresh icon for the same workaround), nor a pair of buttons. This Box
                    // reproduces LightTopBar's own rightButton position (TopEnd).
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (offerActive) {
                            // An unresolved offer replaces the whole top bar (no back button —
                            // it has to be answered; BoardViewModel.onBackPressed consumes the
                            // hardware press the same way) with one plain row reusing the SDK
                            // bar's own height/padding/text-size constants so it reads as the
                            // same top bar, just with different content.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3f.gridUnitsAsDp())
                                    .padding(start = 2f.gridUnitsAsDp(), top = 0.5f.gridUnitsAsDp(), end = 2f.gridUnitsAsDp()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LightText(
                                    text = when {
                                        drawOffer -> "ACCEPT DRAW"
                                        takebackOffer -> "ACCEPT TAKEBACK"
                                        else -> "CLAIM DRAW"
                                    },
                                    variant = LightTextVariant.Fine,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                LightIcon(
                                    icon = LightIcons.ACCEPT,
                                    contentDescription = when {
                                        drawOffer -> "Accept draw"
                                        takebackOffer -> "Accept takeback"
                                        else -> "Claim draw"
                                    },
                                    modifier = Modifier.lightClickable(
                                        onClick = {
                                            when {
                                                drawOffer -> viewModel.acceptIncomingDraw()
                                                takebackOffer -> viewModel.acceptIncomingTakeback()
                                                else -> viewModel.confirmClaimDraw()
                                            }
                                        },
                                    ),
                                )
                                Spacer(modifier = Modifier.width(2f.gridUnitsAsDp()))
                                LightIcon(
                                    icon = LightIcons.CLOSE,
                                    contentDescription = when {
                                        drawOffer -> "Decline draw"
                                        takebackOffer -> "Decline takeback"
                                        else -> "Dismiss draw claim"
                                    },
                                    modifier = Modifier.lightClickable(
                                        onClick = {
                                            when {
                                                drawOffer -> viewModel.declineIncomingDraw()
                                                takebackOffer -> viewModel.declineIncomingTakeback()
                                                // Permanent for this game — see
                                                // ChessSettings.declinedDrawClaim.
                                                else -> viewModel.dismissClaimDraw()
                                            }
                                        },
                                    ),
                                )
                            }
                        } else {
                            LightTopBar(
                                leftButton = LightBarButton.LightIcon(
                                    icon = LightIcons.BACK,
                                    onClick = { goBack() },
                                    contentDescription = "Back",
                                ),
                                // Analysis sandbox: the title is replaced with the literal
                                // "Analysis" (no opponent name/subtitle) so it's unmistakable
                                // that moves here are local-only and not being sent to Lichess.
                                center = if (state.analysisActive) {
                                    LightTopBarCenter.Text("Analysis")
                                } else {
                                    LightTopBarCenter.TwoLineDetail(
                                        line1 = state.opponentName,
                                        line2 = state.subtitle,
                                    )
                                },
                            )
                            // The whole control (icon AND its unread badge) is dropped in the
                            // analysis sandbox: every action behind it — resign, draw, abort,
                            // takeback, chat — targets the real Lichess game and is meaningless
                            // on a local branch. Back-press exits analysis, which brings it
                            // straight back (unread counts keep accruing underneath). It is
                            // likewise withheld until the board is ready: every one of those
                            // actions (and whether it is even offered) depends on game state we
                            // don't have yet.
                            if (!state.analysisActive && state.boardReady) {
                                val hasUnreadChat = state.unreadChatCount > 0
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .height(3f.gridUnitsAsDp())
                                        .padding(horizontal = 1f.gridUnitsAsDp()),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    // Unread messages take priority: the menu glyph itself is
                                    // replaced by a "mark_chat_unread" icon (rather than a badge on
                                    // top of it), and tapping it opens chat directly instead of the
                                    // action menu (chat is still reachable from the menu's "Chat"
                                    // row once there's nothing unread, which reverts the icon back
                                    // to the plain menu ellipses).
                                    if (hasUnreadChat) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_chat_unread_white),
                                            contentDescription = "Unread chat messages",
                                            tint = LightThemeTokens.colors.content,
                                            modifier = Modifier
                                                .size(2f.gridUnitsAsDp())
                                                .lightClickable(onClick = { viewModel.openChat() }),
                                        )
                                    } else {
                                        LightIcon(
                                            icon = LightIcons.ELLIPSES,
                                            contentDescription = "Menu",
                                            modifier = Modifier.lightClickable(onClick = { viewModel.openMenu() }),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!state.boardReady) {
                        // Nothing is drawn until the stream's authoritative game state has
                        // landed. Entering on a guessed position — the home row's post-move
                        // FEN — is what made the arrival animation of a capture play wrong
                        // and then play again once the real move list arrived (a post-move
                        // FEN cannot say what was captured). The wait is one round trip; the
                        // top bar and back button stay live throughout.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 1.5f.gridUnitsAsDp()),
                            contentAlignment = Alignment.Center,
                        ) {
                            LightText(text = "Loading…", variant = LightTextVariant.Copy)
                        }
                    } else if (state.unsupportedVariant != null) {
                        // Variant we can't render faithfully yet — say so plainly
                        // rather than showing a wrong/desynced board. Back still works.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 1.5f.gridUnitsAsDp()),
                            contentAlignment = Alignment.Center,
                        ) {
                            LightText(
                                text = "${state.unsupportedVariant} games aren't supported yet.",
                                variant = LightTextVariant.Copy,
                                align = TextAlign.Center,
                            )
                        }
                    } else {
                        if (state.variant == Variant.CRAZYHOUSE) {
                            // Opponent's reserves above the board, hugged tight to the board
                            // so it can grow toward the bottom bar. Read-only during a live
                            // game; in analysis it becomes tappable on the opponent's turn
                            // (the bars never swap position — only which one is interactive).
                            TopPocketBar(
                                pocket = state.opponentPocket,
                                color = state.myColor.opposite,
                                verticalPadUnits = 0.15f,
                                selectedDrop = if (state.opponentPocketTappable) state.selectedDrop else null,
                                onTap = if (state.opponentPocketTappable) {
                                    { type -> viewModel.onPocketTap(type, state.myColor.opposite) }
                                } else {
                                    null
                                },
                            )
                            // CenteredBoard rather than a bare centred Box: its own side
                            // padding is the same 0.5 units this used, and it exposes the
                            // gutters the scrub bar lives in.
                            CenteredBoard(
                                reservedUnits = 0f,
                                scrubBar = scrubBarSlot(state) { viewModel.seekToFraction(it) },
                            ) {
                                ChessBoard(
                                    state = state,
                                    onSquareTap = { square, animate -> viewModel.onSquareTap(square, animate) },
                                    onLongPress = { viewModel.onBoardLongPress() },
                                )
                            }
                        } else {
                            // Opponent's captured pieces align to the board's left edge (the
                            // board is centered, so [inset] is the gap to that edge).
                            // Horde's collapsed pawn badge is a pocket-sized glyph, so the
                            // bank needs its taller reservation or the badge is clipped.
                            val topBankUnits =
                                if (collapsesHordePawns(state.variant, state.materialBottom)) {
                                    HORDE_BANK_HEIGHT_UNITS
                                } else {
                                    2f
                                }
                            CenteredBoard(
                                reservedUnits = topBankUnits,
                                topBank = { inset ->
                                    MaterialRow(
                                        captured = state.opponentCaptured,
                                        capturedColor = state.materialBottom,
                                        advantage = state.opponentAdvantage,
                                        variant = state.variant,
                                        heightUnits = topBankUnits,
                                        startPad = inset,
                                    )
                                },
                                scrubBar = scrubBarSlot(state) { viewModel.seekToFraction(it) },
                            ) {
                                ChessBoard(
                                    state = state,
                                    onSquareTap = { square, animate -> viewModel.onSquareTap(square, animate) },
                                    onLongPress = { viewModel.onBoardLongPress() },
                                )
                            }
                        }
                        // My material/reserves live in the bottom bar next to the browse
                        // arrows (both variants) — see BottomControls. This frees the row
                        // below the board so the board itself can grow.
                        BottomControls(state = state, viewModel = viewModel)
                    }
                }

                if (state.promotionActive) {
                    // In analysis either side may move, so the picker must use the
                    // ACTUAL mover's color (moverColor), not the fixed myColor.
                    PromotionOverlay(
                        myColor = state.moverColor ?: state.myColor,
                        variant = state.variant,
                        viewModel = viewModel,
                    )
                }

                if (state.menuOpen) {
                    ActionMenuOverlay(
                        showGameActions = !state.terminal,
                        canOfferDraw = state.canOfferDraw,
                        claimDrawAvailable = state.claimDrawAvailable,
                        canAbort = state.canAbort,
                        canRequestTakeback = state.canRequestTakeback,
                        showAnalysisOption = !state.analysisActive,
                        viewModel = viewModel,
                    )
                }

                if (state.chatOpen) {
                    ChatOverlay(state = state, viewModel = viewModel)
                }

                state.confirmation?.let { confirmation ->
                    ConfirmationOverlay(confirmation = confirmation, viewModel = viewModel)
                }

                state.message?.let { message ->
                    LightFullscreenModal(message = message, onClose = { viewModel.dismissMessage() })
                }
            }
        }
    }
}

@Composable
private fun BottomControls(state: BoardUiState, viewModel: BoardViewModel) {
    when (state.mode) {
        BottomMode.BROWSE -> {
            if (state.variant == Variant.CRAZYHOUSE) {
                // Crazyhouse: the bottom bar carries the player's reserves, flanked by
                // back/forward arrows at the edges. Skip-to-start/end are dropped to make
                // room for the (tappable) inventory.
                CrazyhousePocketBar(
                    state = state,
                    // Mine is the interactive bar except in analysis on the opponent's turn,
                    // when the top bar takes over — see TopPocketBar's call site above.
                    onTap = if (state.opponentPocketTappable) {
                        null
                    } else {
                        { type -> viewModel.onPocketTap(type, state.myColor) }
                    },
                    onBack = { viewModel.stepBack() },
                    onForward = { viewModel.stepForward() },
                )
            } else {
                // Standard: the bottom bar carries my captured pieces (left-aligned)
                // between back/forward arrows — mirroring the Crazyhouse layout. No
                // skip-to-start/end during play; those are review-only.
                MaterialBottomBar(
                    state = state,
                    onBack = { viewModel.stepBack() },
                    onForward = { viewModel.stepForward() },
                )
            }
        }

        BottomMode.PENDING -> {
            PendingBar(
                onConfirm = { viewModel.confirmPendingMove() },
                onCancel = { viewModel.cancelPendingMove() },
            )
        }
    }
}

/**
 * The CONFIRM/cancel bar shown while a move is staged. Hand-rolled instead of
 * [LightBottomBar] (which this reproduces for `[null, Text("CONFIRM"), LightIcon(CLOSE)]`)
 * so its footprint exactly matches the browse-mode bars ([MaterialBottomBar] /
 * [CrazyhousePocketBar]) — `LightBottomBar` adds a 1-grid-unit top margin on top of its
 * 4-unit height, so swapping between it and the browse bars visibly shrank/grew the
 * board each time a move was staged/cancelled.
 */
@Composable
private fun PendingBar(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(icon = LightIcons.SPACER, contentDescription = null)
        Box(
            modifier = Modifier.lightClickable(onClick = onConfirm),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = "CONFIRM",
                variant = LightTextVariant.Fine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LightIcon(
            icon = LightIcons.CLOSE,
            contentDescription = "Cancel move",
            modifier = Modifier.lightClickable(onClick = onCancel),
        )
    }
}

// Browse controls that grey out (and disable) a control at the ends of the move
// history. Four controls left-to-right: skip-to-start, back, forward, skip-to-end.
// The two skip controls reuse the same arrow glyphs with a thin vertical bar tight
// against the arrow's point. Mirrors LightBottomBar's 2-item metrics (height, top
// margin, padding); the four controls are evenly distributed across the width.
@Composable
internal fun BrowseBar(
    canStepBack: Boolean,
    canStepForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSkipStart: () -> Unit,
    onSkipEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Skip-to-start: a vertical bar immediately LEFT of a BACK arrow.
        SkipControl(
            barSide = BarSide.LEADING,
            icon = LightIcons.BACK,
            contentDescription = "First move",
            enabled = canStepBack,
            onClick = onSkipStart,
        )
        NavArrow(LightIcons.BACK, "Previous move", canStepBack, onClick = onBack)
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", canStepForward, onClick = onForward)
        // Skip-to-end: an ARROW_RIGHT arrow with a vertical bar immediately to its RIGHT.
        SkipControl(
            barSide = BarSide.TRAILING,
            icon = LightIcons.ARROW_RIGHT,
            contentDescription = "Last move",
            enabled = canStepForward,
            onClick = onSkipEnd,
        )
    }
}

/**
 * Standard review bottom bar: my captured pieces fanned left among all four browse
 * controls (skip-start, back … forward, skip-end) — the review counterpart to
 * [MaterialBottomBar], with the two extra skip arrows.
 */
@Composable
internal fun MaterialReviewBottomBar(
    state: BoardUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSkipStart: () -> Unit,
    onSkipEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipControl(BarSide.LEADING, LightIcons.BACK, "First move", state.canStepBack, onSkipStart)
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack, state.moveStepIntervalMs, onBack)
        Row(
            modifier = Modifier
                .weight(1f),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaterialFan(
                captured = state.myCaptured,
                capturedColor = state.materialBottom.opposite,
                advantage = state.myAdvantage,
                cellUnits = MATERIAL_CELL_UNITS,
                variant = state.variant,
            )
        }
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward, state.moveStepIntervalMs, onForward)
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        SkipControl(BarSide.TRAILING, LightIcons.ARROW_RIGHT, "Last move", state.canStepForward, onSkipEnd)
    }
}

/**
 * Crazyhouse review bottom bar: the player's reserves centered among all four browse
 * controls (skip-start, back … forward, skip-end). Read-only (no drops in review), and
 * the pocket pieces pack tight so they fit alongside the extra skip arrows.
 */
@Composable
internal fun CrazyhouseReviewBottomBar(
    state: BoardUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSkipStart: () -> Unit,
    onSkipEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipControl(BarSide.LEADING, LightIcons.BACK, "First move", state.canStepBack, onSkipStart)
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack, state.moveStepIntervalMs, onBack)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            POCKET_ORDER.filter { (state.myPocket[it] ?: 0) > 0 }.forEach { type ->
                PocketPiece(
                    type = type,
                    color = state.myColor,
                    count = state.myPocket[type] ?: 0,
                    cell = MY_POCKET_CELL_UNITS.gridUnitsAsDp(),
                    selected = false,
                    onTap = null,
                    horizontalPadUnits = 0.0f,
                )
            }
        }
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward, state.moveStepIntervalMs, onForward)
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        SkipControl(BarSide.TRAILING, LightIcons.ARROW_RIGHT, "Last move", state.canStepForward, onSkipEnd)
    }
}

/**
 * The vertical move-scrub bar: a dim full-height track with a thumb marking the position
 * currently on the board. Touch anywhere along it and drag to move through the game; no
 * long-press arming, because unlike the bottom bar it is a target of its own with nothing
 * else competing for the finger.
 *
 * This REPLACED a long-press-then-drag scrub on the bottom bars. That could not be made to
 * work: the same long press armed both the scrub and the arrows' press-and-hold repeat, so
 * holding an arrow to step and letting the finger drift even slightly abandoned the repeat
 * and started scrubbing (see Modifier.holdRepeat, which bails out on slop by design).
 *
 * The drag is RELATIVE, at the same fixed moves-per-pixel rate the old gesture used
 * ([SCRUB_SWEEP_FRACTION]/[SCRUB_SWEEP_PLIES]) — a drag of a given distance covers the same
 * number of moves in a 200-move game as in a 20-move one. It deliberately does NOT jump to
 * an absolute position under the finger: that makes the sensitivity depend on game length,
 * which is exactly what the fixed-rate mapping was introduced to fix.
 *
 * Sized by the caller to the board's own height, so the track spans exactly the board.
 * Renders nothing when there is nothing to scrub (a game with no moves yet).
 */
/**
 * The [MoveScrubBar] slot for [CenteredBoard], or null when the "Scrub bar" setting is off.
 *
 * Nullability matters beyond just skipping the draw: [CenteredBoard] uses it to decide which
 * gutter the review clocks go in, so "off" has to be distinguishable from "on but empty".
 * Deliberately keyed on the SETTING ONLY — not on whether the game has any moves yet — so
 * the clocks can't hop from one side to the other when the first move lands. An enabled bar
 * with nothing to scrub simply draws nothing.
 */
internal fun scrubBarSlot(
    state: BoardUiState,
    onSeek: (Float) -> Unit,
): (@Composable () -> Unit)? =
    if (state.scrubBarEnabled) {
        { MoveScrubBar(state, onSeek) }
    } else {
        null
    }

@Composable
internal fun MoveScrubBar(
    state: BoardUiState,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.scrubBarEnabled || state.totalPlies <= 0) return

    val currentFraction by rememberUpdatedState(state.viewFraction)
    val totalPlies by rememberUpdatedState(state.totalPlies)
    val currentSeek by rememberUpdatedState(onSeek)
    val thumbHeight = SCRUB_THUMB_HEIGHT_UNITS.gridUnitsAsDp()

    BoxWithConstraints(
        // The whole gutter is touchable even though only a thin track is drawn — a 3dp
        // hit target would be unusable. Height comes from the caller (the board's edge).
        modifier = modifier
            // fillMaxSize, not just height: the drawn track is 3dp wide, which would be an
            // unusable hit target, so the recognizer takes the whole gutter.
            .fillMaxSize()
            .pointerInput(Unit) {
                val length = size.height.toFloat()
                if (length <= 0f) return@pointerInput

                var startY = 0f
                var startFraction = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        startY = offset.y
                        startFraction = currentFraction
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val plies = totalPlies
                        if (plies > 0) {
                            // Downward drag advances the game: the thumb tracks the finger,
                            // and later moves sit lower on the track.
                            val deltaY = change.position.y - startY
                            val movesMoved = deltaY * SCRUB_SWEEP_PLIES / (length * SCRUB_SWEEP_FRACTION)
                            currentSeek((startFraction + movesMoved / plies).coerceIn(0f, 1f))
                        }
                    },
                )
            },
    ) {
        val travel = maxHeight - thumbHeight
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = SCRUB_TRACK_GAP_UNITS.gridUnitsAsDp())
                .width(SCRUB_TRACK_WIDTH)
                .fillMaxHeight()
                .background(MARK_SHADE.copy(alpha = SCRUB_TRACK_ALPHA)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = SCRUB_TRACK_GAP_UNITS.gridUnitsAsDp())
                .offset(y = travel * state.viewFraction.coerceIn(0f, 1f))
                .width(SCRUB_TRACK_WIDTH)
                .height(thumbHeight)
                .background(MARK_SHADE),
        )
    }
}

/** Which side of the arrow the skip bar sits on. */
private enum class BarSide { LEADING, TRAILING }

@Composable
private fun SkipControl(
    barSide: BarSide,
    icon: LightIconConfiguration,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bar: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(SKIP_BAR_HEIGHT_UNITS.gridUnitsAsDp())
                .background(LightThemeTokens.colors.content),
        )
    }
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .then(if (enabled) Modifier.lightClickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (barSide == BarSide.LEADING) bar()
        LightIcon(icon = icon, contentDescription = contentDescription)
        if (barSide == BarSide.TRAILING) bar()
    }
}

/**
 * A browse arrow: tap to step one move, press and hold to keep stepping every
 * [repeatIntervalMs] (the "Move step speed" setting, via [BoardUiState.moveStepIntervalMs]).
 */
@Composable
internal fun NavArrow(
    icon: LightIconConfiguration,
    contentDescription: String,
    enabled: Boolean,
    repeatIntervalMs: Long = MoveStepSpeed.DEFAULT.intervalMs,
    onClick: () -> Unit,
) {
    // Holding the arrow keeps stepping. The lift that ends a repeat run would otherwise
    // also register as a click and step one extra move, so swallow exactly that one.
    var repeated by remember { mutableStateOf(false) }
    LightIcon(
        icon = icon,
        contentDescription = contentDescription,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .holdRepeat(
                enabled = enabled,
                intervalMs = repeatIntervalMs,
                onRepeatStarted = { repeated = true },
                onRepeat = onClick,
            )
            .then(
                if (enabled) {
                    Modifier.lightClickable {
                        if (repeated) repeated = false else onClick()
                    }
                } else {
                    Modifier
                },
            ),
    )
}

/**
 * Lays out the board centered in the remaining vertical space with an optional bank
 * above and below. The board is square and usually height-limited, so it sits inset
 * from the screen's side edges; [topBank]/[bottomBank] receive that horizontal [inset]
 * (screen edge → board edge) so a left-aligned bank can start flush with the board.
 * [reservedUnits] is the banks' combined height, kept clear when sizing the board.
 */
@Composable
internal fun ColumnScope.CenteredBoard(
    reservedUnits: Float,
    topBank: @Composable (inset: Dp) -> Unit = {},
    bottomBank: @Composable (inset: Dp) -> Unit = {},
    // Optional review clocks: shown rotated 90° in the inset beside the board —
    // opponent's near the top, mine near the bottom — without shifting the centered board.
    topRightLabel: String? = null,
    bottomRightLabel: String? = null,
    // The move-scrub bar (see [MoveScrubBar]), sized to the board's height. It takes the
    // RIGHT gutter, which is also where the clocks would go, so when it's present the
    // clocks move to the LEFT gutter rather than being drawn over. Both gutters are the
    // same width, so the clocks read identically either side; the board never moves.
    scrubBar: (@Composable () -> Unit)? = null,
    board: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        val hPad = 0.5f.gridUnitsAsDp()
        val edge = minOf(maxWidth - hPad * 2, maxHeight - reservedUnits.gridUnitsAsDp())
        val inset = (maxWidth - edge) / 2
        Column(modifier = Modifier.fillMaxSize()) {
            topBank(inset)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // Equal-weight side gutters keep the fixed-size board centered; the
                // gutters also carry the rotated clocks and the scrub bar, flush to the
                // board's edge. Both gutter Boxes are `height(edge)`, so anything in them
                // spans exactly the board.
                val clocksOnLeft = scrubBar != null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f).height(edge)) {
                        if (clocksOnLeft) {
                            // Mirrored: flush to the board's edge means End on this side.
                            topRightLabel?.let {
                                RotatedClock(it, Modifier.align(Alignment.TopEnd).padding(end = 0.2f.gridUnitsAsDp(), top = 1f.gridUnitsAsDp()))
                            }
                            bottomRightLabel?.let {
                                RotatedClock(it, Modifier.align(Alignment.BottomEnd).padding(end = 0.2f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()))
                            }
                        }
                    }
                    Box(modifier = Modifier.size(edge)) { board() }
                    Box(modifier = Modifier.weight(1f).height(edge)) {
                        scrubBar?.invoke()
                        if (!clocksOnLeft) {
                            topRightLabel?.let {
                                RotatedClock(it, Modifier.align(Alignment.TopStart).padding(start = 0.2f.gridUnitsAsDp(), top = 1f.gridUnitsAsDp()))
                            }
                            bottomRightLabel?.let {
                                RotatedClock(it, Modifier.align(Alignment.BottomStart).padding(start = 0.2f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()))
                            }
                        }
                    }
                }
            }
            bottomBank(inset)
        }
    }
}

/** A review clock rendered as a vertical strip (rotated 90°) for the board's right edge. */
@Composable
private fun RotatedClock(text: String, modifier: Modifier) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        maxLines = 1,
        modifier = modifier.rotate(90f),
    )
}

@Composable
internal fun ChessBoard(
    state: BoardUiState,
    // Null for a READ-ONLY board (ReviewScreen): the squares then get no tap recognizer
    // and — the reason this is nullable rather than a `{}` no-op — no tap haptic either,
    // since a haptic on a tap that can never do anything reads as a broken control.
    onSquareTap: ((Int, Boolean) -> Unit)?,
    // Long-press anywhere on the board enters the analysis sandbox — or, if it's already
    // open, resets it back to the snapshot (see BoardViewModel.onBoardLongPress; the
    // ActionMenuOverlay "Analysis" row is the other entry point).
    //
    // NULLABLE, not a `{}` default, because it is what decides whether the squares get a
    // gesture recognizer at all. The review board passes a real handler while passing
    // onSquareTap = null (it's read-only until analysis opens), and a `{}` default would
    // have left no way to tell "offers analysis" from "offers nothing" — which is exactly
    // why long-press-to-analysis silently did nothing in reviews.
    onLongPress: (() -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val edge: Dp = minOf(maxWidth, maxHeight)
        val squareSize = edge / 8

        // Drive the one-shot slide for the current transition. Keying the Animatable on the
        // move's id gives each new move a fresh Animatable initialised to 0f DURING
        // composition — so the very first frame already hides the destination square and
        // draws the overlay at the origin. (Snapping to 0f inside the LaunchedEffect instead
        // ran a frame late, briefly flashing the moved piece on its destination before the
        // slide began.) It rests at 1f (done) between animations.
        val anim = state.animatingMove
        val progress = remember(anim?.id) { Animatable(if (anim != null) 0f else 1f) }
        LaunchedEffect(anim?.id) {
            if (anim != null) {
                progress.animateTo(
                    1f,
                    animationSpec = tween(MOVE_ANIM_MS, delayMillis = anim.startDelayMs),
                )
            }
        }
        val sliding = anim != null && progress.value < 1f
        // Capture slide: while the capturer slides in, render the PRE-move board so the
        // captured piece stays visible (on the target, or behind it for en passant; and for
        // Atomic, every soon-to-explode piece too). The origin is already lifted off it (the
        // overlay draws the mover) and the target keeps its captured piece until the slide
        // settles, so nothing needs hiding. Any other slide hides its landing square(s) —
        // the overlay draws them from the destination board.
        val captureSliding = sliding && anim.preMoveBoard != null
        val boardToRender = if (captureSliding) anim.preMoveBoard else state.board
        val slideHidden: Set<Int> = when {
            !sliding -> emptySet()
            captureSliding -> emptySet()
            else -> anim.slides.mapTo(HashSet()) { it.endSquare }
        }

        // ---- Drag-and-drop (opt-in; BoardUiState.dragEnabled, default false) ----------
        //
        // A drag is NOT a second move path. It issues exactly the two `onSquareTap` calls
        // the user would otherwise make — the origin on pick-up, the destination on
        // release — so legality, legal-move highlighting, the promotion picker, the
        // CONFIRM staging step, Crazyhouse drops, analysis mode and every read-only /
        // not-your-turn guard come along unchanged from the tap path, with no view-model
        // involvement at all. Releasing on the origin, or on an illegal square, therefore
        // behaves exactly as that second tap does today (deselect / reselect / nothing).
        //
        // WHERE the recognizer lives: ONE detectDragGestures on the whole board Box, not
        // one per SquareCell. A drag inherently crosses cell boundaries, so a per-cell
        // recognizer would need the pointer handed between siblings; and the
        // offset -> square conversion a board-level recognizer needs is the same maths
        // required to resolve the drop target anyway.
        //
        // WHY it coexists with SquareCell's combinedClickable (tap + long-press-to-analysis)
        // and with Modifier.tapHaptic:
        //  * detectDragGestures takes the down with requireUnconsumed = false (the child's
        //    tap detector consumes it first, on the Main pass) and then engages only after
        //    touch slop. A STATIONARY press is never touched by this recognizer at all, so
        //    a plain tap and a stationary long press behave byte-for-byte as before.
        //  * Once slop is crossed, onDrag consumes every move change. The child's
        //    waitForUpOrCancellation re-checks consumption on the Final pass, which runs
        //    parent -> child, so it cancels its pending tap: a drag cannot also fire a tap
        //    on release.
        //  * A long press that matures FIRST wins outright — detectTapGestures consumes
        //    everything until up, which cancels this recognizer's slop detection, so
        //    press-hold-then-move still enters analysis rather than dragging.
        //  * tapHaptic is a non-consuming awaitFirstDown observer, so it is unaffected in
        //    either direction (and a drag still gives the same pick-up haptic as a tap).
        // Nothing here touches the gutter, so the scrub bar is untouched.
        val density = LocalDensity.current
        val squarePx = with(density) { squareSize.toPx() }
        val dragActive = state.dragEnabled && onSquareTap != null
        // Origin square of the in-flight drag (null = not dragging) and the finger's
        // current position, in board-local px.
        var dragFrom by remember { mutableStateOf<Int?>(null) }
        var dragPos by remember { mutableStateOf(Offset.Zero) }
        // Where the finger actually LANDED, which is NOT what detectDragGestures reports.
        // Its onDragStart fires with the position at the moment touch slop was exceeded
        // (`onDragStart.invoke(drag.position)`), i.e. already ~25px along the swipe. A
        // square is only ~100px, so picking up a piece near a square edge and dragging
        // toward its neighbour resolved the ORIGIN to that neighbour — grabbing the wrong
        // piece, or an empty square, which makes the whole drag inert. That is the
        // "sometimes hard to grab the piece" symptom: the grab box was effectively the
        // square offset a quarter-square in the drag's own direction.
        //
        // Recorded here by a passive observer that consumes nothing (the same construction
        // Modifier.tapHaptic uses), so it coexists with the drag recognizer and the
        // squares' combinedClickable instead of competing for the gesture. The grab box is
        // then exactly the square under the finger — no enlargement needed.
        var downPos by remember { mutableStateOf(Offset.Zero) }
        val currentState by rememberUpdatedState(state)
        val currentTap by rememberUpdatedState(onSquareTap)

        val dragModifier = if (!dragActive) {
            Modifier
        } else {
            // Keyed on the square size only: `state.flipped` and the tap callback are read
            // through rememberUpdatedState so an in-person board flipping mid-game (or a
            // fresh method reference each recomposition) doesn't restart the recognizer.
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    downPos = awaitFirstDown(requireUnconsumed = false).position
                }
            }.pointerInput(squarePx) {
                detectDragGestures(
                    onDragStart = {
                        val s = currentState
                        // downPos, not the callback's offset — see above.
                        val from = squareAtOffset(downPos, squarePx, s.flipped)
                        val piece = from?.let { s.board.getOrNull(it) }
                        if (from == null || piece == null) {
                            // Nothing to pick up. The gesture still runs (and still
                            // consumes, cancelling the underlying tap) but performs no
                            // taps — a swipe starting on an empty square is inert rather
                            // than clearing the selection mid-flight.
                            dragFrom = null
                        } else {
                            dragFrom = from
                            dragPos = downPos
                            // The pick-up tap — skipped when this square is ALREADY the
                            // selection, because tapping an already-selected square
                            // deselects it (BoardViewModel.onSquareTap) and the first of
                            // the two taps has effectively already happened.
                            if (s.selectedSquare != from) currentTap?.invoke(from, true)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragPos = change.position
                    },
                    onDragEnd = {
                        // Nothing was picked up (drag began off a piece) — no second tap.
                        if (dragFrom == null) return@detectDragGestures
                        dragFrom = null
                        // Released off the board edge = cancel (the pick-up selection
                        // stays, exactly as a single tap would leave it). Otherwise this
                        // is the second tap; the view model already knows the origin from
                        // the pick-up tap, so only the destination is needed.
                        squareAtOffset(dragPos, squarePx, currentState.flipped)
                            ?.let { to -> currentTap?.invoke(to, false) }
                    },
                    onDragCancel = { dragFrom = null },
                )
            }
        }

        val draggingFrom = if (dragActive) dragFrom else null
        // The dragged piece is drawn by the follow-the-finger overlay below, so hide it
        // from its origin square — same mechanism the move-slide overlay uses.
        val hidden: Set<Int> =
            if (draggingFrom != null) slideHidden + draggingFrom else slideHidden

        Box(modifier = Modifier.size(edge).then(dragModifier)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Row 0 is the top of the screen; map (row, col) -> engine square per
                // the user's orientation (their back rank at the bottom).
                for (row in 0..7) {
                    Row {
                        for (col in 0..7) {
                            val square = squareAt(row, col, state.flipped)
                            SquareCell(
                                square = square,
                                squareSize = squareSize,
                                state = state,
                                board = boardToRender,
                                onSquareTap = onSquareTap,
                                onLongPress = onLongPress,
                                hidePiece = square in hidden,
                                moveSettled = !sliding,
                            )
                        }
                    }
                }
            }
            if (sliding) {
                val p = progress.value
                // One overlay per slide — castling animates the king and rook together.
                anim.slides.forEach { s ->
                    val (sr, sc) = squareToRowCol(s.startSquare, state.flipped)
                    val (er, ec) = squareToRowCol(s.endSquare, state.flipped)
                    Box(
                        modifier = Modifier
                            .offset(
                                x = squareSize * (sc + (ec - sc) * p),
                                y = squareSize * (sr + (er - sr) * p),
                            )
                            .size(squareSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The in-person game rotates pieces (across-table, or the whole
                        // board as a rigid unit) — resolved by the shared [pieceRotation]
                        // so a piece mid-slide matches the static board it lands on.
                        PieceGlyph(
                            piece = s.piece,
                            squareSize = squareSize,
                            rotationDegrees = state.pieceRotation(s.piece),
                        )
                    }
                }
            }

            // The dragged piece follows the finger, centred under it. Same structure as
            // the move-slide overlay above (offset Box + PieceGlyph), with its origin
            // square in `hidden` so the piece isn't drawn twice. The offset is read in the
            // layout-phase lambda so a drag move re-lays-out without recomposing.
            if (draggingFrom != null) {
                boardToRender.getOrNull(draggingFrom)?.let { dragged ->
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (dragPos.x - squarePx / 2f).roundToInt(),
                                    (dragPos.y - squarePx / 2f).roundToInt(),
                                )
                            }
                            .size(squareSize),
                        contentAlignment = Alignment.Center,
                    ) {
                        PieceGlyph(
                            piece = dragged,
                            squareSize = squareSize,
                            rotationDegrees = state.pieceRotation(dragged),
                        )
                    }
                }
            }

            // A dashed border whose dashes continuously "march" around the king's square,
            // for BOTH check and checkmate (checkmate additionally turns the king sideways
            // — see SquareCell). Check and checkmate are mutually exclusive, so at most one
            // of these squares is set. The phase animates a 0..1 fraction (LinearEasing,
            // Restart); DashedRegionBorder scales it by the full dash+gap PERIOD so the
            // wrap from 1 back to 0 lines up exactly and the ants never visibly jump.
            //
            // Held back until the move slide has SETTLED (!sliding), so on the move that
            // delivers the check/mate the border appears only once the piece has landed —
            // not mid-flight. NB: gate on !sliding, NOT `anim == null`: animatingMove
            // persists across recomputes (it rests at progress 1f between moves), so it is
            // rarely null; `sliding` is the true "a slide is in progress right now" signal.
            val kingSquare = state.checkedKingSquare ?: state.checkmateKingSquare

            if (kingSquare != null && !sliding) {
                val (r, c) = squareToRowCol(kingSquare, state.flipped)
                val marchTransition = rememberInfiniteTransition(label = "check-march")
                val phaseFraction by marchTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(CHECK_MARCH_MS, easing = LinearEasing)),
                    label = "check-march-phase",
                )

                DashedRegionBorder(
                    modifier = Modifier
                        .offset(x = squareSize * c, y = squareSize * r)
                        .size(squareSize),
                    phaseFraction = phaseFraction,
                )
            }

            // Variant goal squares (KotH centre, Racing Kings rank 8): ONE combined,
            // stationary dashed border around the whole highlighted region — not one per
            // square. The highlighted squares always form a single axis-aligned rect (a
            // 2x2 block or a full rank), so their bounding box IS that region.
            if (state.goalSquares.isNotEmpty()) {
                val cells = state.goalSquares.map { squareToRowCol(it, state.flipped) }
                val minRow = cells.minOf { it.first }
                val maxRow = cells.maxOf { it.first }
                val minCol = cells.minOf { it.second }
                val maxCol = cells.maxOf { it.second }
                DashedRegionBorder(
                    modifier = Modifier
                        .offset(x = squareSize * minCol, y = squareSize * minRow)
                        .size(
                            width = squareSize * (maxCol - minCol + 1),
                            height = squareSize * (maxRow - minRow + 1),
                        ),
                    phaseFraction = 0f,
                )
            }
        }
    }
}

/**
 * A dashed rectangular stroke over the region [modifier] sizes/positions — used for the
 * check/checkmate king square (animated, marching ants) and the combined variant
 * goal-square region (stationary). Monochrome ([MARK_SHADE]) only.
 *
 * [phaseFraction] is a 0..1 value; it's scaled here by the full dash+gap period (in px)
 * so a caller animating it 0→1 on repeat produces a seamless march (fraction 1 lands
 * exactly one period along, visually identical to fraction 0). Pass 0f for a static border.
 */
@Composable
private fun DashedRegionBorder(modifier: Modifier, phaseFraction: Float = 0f) {
    Canvas(modifier = modifier) {
        val strokeWidthPx = DASH_STROKE_WIDTH_DP.dp.toPx()
        val onPx = DASH_ON_DP.dp.toPx()
        val gapPx = DASH_GAP_DP.dp.toPx()
        drawRect(
            color = MARK_SHADE,
            topLeft = Offset(strokeWidthPx / 2, strokeWidthPx / 2),
            size = Size(size.width - strokeWidthPx, size.height - strokeWidthPx),
            style = Stroke(
                width = strokeWidthPx,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(onPx, gapPx),
                    phase = phaseFraction * (onPx + gapPx),
                ),
            ),
        )
    }
}

/**
 * Map a board-local pixel [offset] to an engine square, or null if it falls outside the
 * 8x8 grid (a drag released past the board's edge). [squarePx] is one square's edge in px.
 */
private fun squareAtOffset(offset: Offset, squarePx: Float, flipped: Boolean): Int? {
    if (squarePx <= 0f) return null
    val col = floor(offset.x / squarePx).toInt()
    val row = floor(offset.y / squarePx).toInt()
    if (row !in 0..7 || col !in 0..7) return null
    return squareAt(row, col, flipped)
}

// Map an engine square (0..63) to its (row, col) grid cell for the current orientation
// — the inverse of [squareAt]. Row 0 is the top of the screen.
private fun squareToRowCol(square: Int, flipped: Boolean): Pair<Float, Float> {
    val file = Square.file(square)
    val rank = Square.rank(square)
    return if (!flipped) {
        (7 - rank).toFloat() to file.toFloat()
    } else {
        rank.toFloat() to (7 - file).toFloat()
    }
}

// Queen-first display order for pockets (most valuable first); pawns last.
internal val POCKET_ORDER = listOf(
    PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT, PieceType.PAWN,
)

// Pocket piece cell sizes (grid units). The opponent's read-only row above the
// board uses the same glyph size as the player's bar, just with less vertical space.
internal const val MY_POCKET_CELL_UNITS = 2.4f
private const val OPP_POCKET_CELL_UNITS = MY_POCKET_CELL_UNITS
// Captured-piece glyph size, used everywhere material is shown (standard play + both
// review layouts) and for the shrunk Crazyhouse review pockets, so all banks match.
internal const val MATERIAL_CELL_UNITS = 1.6f
// Captured pieces of the SAME type fan like a hand of cards; DIFFERENT types form
// separate groups. Two independent density knobs, each a center-to-center horizontal
// ADVANCE (grid units) — smaller = tighter/more overlap — both tunable without touching
// the glyph size (height):
//   • within a group   → MATERIAL_PIECE_WIDTH_UNITS (the per-piece "fan increment")
//   • between groups    → MATERIAL_GROUP_GAP_UNITS  (lower this to pull groups together)
private const val MATERIAL_PIECE_WIDTH_UNITS = 0.48f
private const val MATERIAL_GROUP_GAP_UNITS = 1.2f
// Count-badge diameter (grid units), pinned to the gameplay pocket size so it reads
// the same whether the piece glyph is at pocket size (play) or material size (review).
private const val POCKET_BADGE_DIAMETER_UNITS = MY_POCKET_CELL_UNITS * 0.5f
private const val MATERIAL_HALO_SCALE = 1.2f


// Horde collapses its captured pawns into a single Crazyhouse-style reserve glyph, so it
// uses the pocket cell size, and the bank it lives in has to reserve a little more height
// than the fanned bar's 2 units.
internal const val HORDE_BANK_HEIGHT_UNITS = 2.8f

/**
 * Should this side's captures collapse to one pawn glyph with a count badge?
 *
 * Only in Horde, and only for the side capturing FROM the horde — the horde is 36 pawns,
 * so fanning them out is a wall of identical glyphs that says nothing. White IS the horde,
 * so white captured pieces are exactly that side's haul; the black player's own pieces
 * (captured BY the horde) are a normal mixed set and keep the fan. Keying off the captured
 * colour rather than the viewer means this reads the same from either seat.
 */
internal fun collapsesHordePawns(variant: Variant, capturedColor: EngineColor): Boolean =
    variant == Variant.HORDE && capturedColor == EngineColor.WHITE

/**
 * A material row hugging one board edge (non-Crazyhouse): the pieces one side has
 * captured (drawn in [capturedColor]), same types fanned/overlapped like a hand of
 * cards, followed by that side's "+N" point lead, if any. Left-aligned to the board's
 * edge. Always occupies its height so the board doesn't shift when captures appear.
 * [cellUnits] sets the glyph size — review uses the small [MATERIAL_CELL_UNITS].
 */
@Composable
internal fun MaterialRow(
    captured: List<PieceType>,
    capturedColor: EngineColor,
    advantage: Int,
    variant: Variant = Variant.STANDARD,
    cellUnits: Float = MATERIAL_CELL_UNITS,
    heightUnits: Float = 2f,
    startPad: Dp = Dp.Unspecified,
) {
    // Default the left pad to the board's own 0.5u edge inset; callers that know the
    // centered board's true left edge pass it in so the fan starts flush with the board.
    val start = if (startPad == Dp.Unspecified) 0.5f.gridUnitsAsDp() else startPad
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightUnits.gridUnitsAsDp())
            .padding(start = start, end = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaterialFan(
            captured = captured,
            capturedColor = capturedColor,
            advantage = advantage,
            cellUnits = cellUnits,
            variant = variant,
        )
    }
}

/**
 * The captured-piece fan + "+N" lead, emitted directly into the caller's [Row] so it
 * can live either in a standalone [MaterialRow] or inline in the bottom bar next to the
 * browse arrows. Must be called from a horizontal layout scope.
 */
@Composable
internal fun MaterialFan(
    captured: List<PieceType>,
    capturedColor: EngineColor,
    advantage: Int,
    cellUnits: Float,
    variant: Variant = Variant.STANDARD,
) {
    val cell = cellUnits.gridUnitsAsDp()
    // In Horde the pawns collapse into one badged glyph, but anything else this side
    // captured still fans normally — horde pawns CAN promote, so a queen or rook can
    // legitimately show up here and must not be miscounted as a pawn.
    val collapsePawns = collapsesHordePawns(variant, capturedColor)
    val pawnCount = if (collapsePawns) captured.count { it == PieceType.PAWN } else 0
    val fanned = if (collapsePawns) captured.filter { it != PieceType.PAWN } else captured
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (pawnCount > 0) {
            PocketPiece(
                type = PieceType.PAWN,
                color = capturedColor,
                count = pawnCount,
                cell = MY_POCKET_CELL_UNITS.gridUnitsAsDp(),
                selected = false,
                onTap = null,
                horizontalPadUnits = 0f,
            )
        }
        // Groups advance by MATERIAL_GROUP_GAP_UNITS (center-to-center); the negative gap
        // vs the cell width overlaps adjacent group boxes so groups can be pulled tight.
        Row(horizontalArrangement = Arrangement.spacedBy(MATERIAL_GROUP_GAP_UNITS.gridUnitsAsDp() - cell)) {
            // The list is ordered by type, so distinct() gives the groups in display order.
            fanned.distinct().forEach { type ->
                CapturedStack(type = type, color = capturedColor, count = fanned.count { it == type }, cell = cell)
            }
        }
        if (advantage > 0) {
            // Keep the lead on one line — in the bottom bar it's tight against the forward
            // arrow, and a wrapped "+31" reads as broken.
            LightText(
                text = "+$advantage",
                variant = LightTextVariant.Detail,
                maxLines = 1,
                modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}

/**
 * One captured piece type shown [count] times, overlapping like a hand of cards. Each
 * piece carries a background-coloured halo (the same silhouette tinted to the screen
 * background, slightly enlarged) so where it overlaps the piece behind it there's a
 * faint separation and each remains legible.
 */
@Composable
private fun CapturedStack(type: PieceType, color: EngineColor, count: Int, cell: Dp) {
    val piece = Piece(color, type)
    // The glyph box is `cell` wide; a negative gap of (pitch - cell) advances each piece
    // by only `pitch`, so the horizontal density is controlled without touching the size.
    val gap = MATERIAL_PIECE_WIDTH_UNITS.gridUnitsAsDp() - cell
    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(count) {
            Box(modifier = Modifier.size(cell), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(pieceDrawable(piece)),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(LightThemeTokens.colors.background),
                    modifier = Modifier.size(cell * PIECE_SCALE * MATERIAL_HALO_SCALE),
                )
                BankPieceGlyph(piece = piece, squareSize = cell)
            }
        }
    }
}

/**
 * The centered row of the OPPONENT's reserves shown above the board (at
 * [OPP_POCKET_CELL_UNITS] during play, [MATERIAL_CELL_UNITS] in review). Read-only by
 * default — [onTap] is only ever non-null in the analysis sandbox, where the user plays
 * both sides and this becomes the interactive bar on the opponent's turn (see
 * [BoardUiState.opponentPocketTappable]). The count badges stay a fixed size
 * ([POCKET_BADGE_DIAMETER_UNITS]) regardless of glyph size.
 */
@Composable
internal fun TopPocketBar(
    pocket: Map<PieceType, Int>,
    color: EngineColor,
    cellUnits: Float = OPP_POCKET_CELL_UNITS,
    verticalPadUnits: Float = 0.4f,
    badgeOffsetUnits: Float = 0f,
    selectedDrop: PieceType? = null,
    onTap: ((PieceType) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height((cellUnits + verticalPadUnits).gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        POCKET_ORDER.filter { (pocket[it] ?: 0) > 0 }.forEach { type ->
            PocketPiece(
                type = type,
                color = color,
                count = pocket[type] ?: 0,
                cell = cellUnits.gridUnitsAsDp(),
                selected = type == selectedDrop,
                onTap = onTap?.let { tap -> { tap(type) } },
                badgeOffsetUnits = badgeOffsetUnits,
            )
        }
    }
}

/**
 * Crazyhouse bottom bar: the player's reserves centered between a back arrow (left
 * edge) and a forward arrow (right edge). Tap a reserve piece to pick it up to drop.
 * [onTap] is null when this bar isn't the interactive one — in analysis on the
 * opponent's turn the top bar takes over (see [BoardUiState.opponentPocketTappable]).
 */
@Composable
internal fun CrazyhousePocketBar(
    state: BoardUiState,
    onTap: ((PieceType) -> Unit)?,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack, state.moveStepIntervalMs, onBack)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            POCKET_ORDER.filter { (state.myPocket[it] ?: 0) > 0 }.forEach { type ->
                PocketPiece(
                    type = type,
                    color = state.myColor,
                    count = state.myPocket[type] ?: 0,
                    cell = MY_POCKET_CELL_UNITS.gridUnitsAsDp(),
                    selected = onTap != null && type == state.selectedDrop,
                    onTap = onTap?.let { tap -> { tap(type) } },
                )
            }
        }
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward, state.moveStepIntervalMs, onForward)
    }
}

/**
 * The standard-material equivalent of [CrazyhousePocketBar] during live play: my
 * captured pieces fanned left (right after the back arrow), with back/forward arrows
 * at the edges. No skip-to-start/end here — those live on the review screen only.
 */
@Composable
internal fun MaterialBottomBar(
    state: BoardUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack, state.moveStepIntervalMs, onBack)
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 1f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaterialFan(
                captured = state.myCaptured,
                capturedColor = state.materialBottom.opposite,
                advantage = state.myAdvantage,
                cellUnits = MATERIAL_CELL_UNITS,
                variant = state.variant,
            )
        }
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward, state.moveStepIntervalMs, onForward)
    }
}

/**
 * One reserve piece with a count badge: the glyph, plus (when more than one is held)
 * a small circle in the background colour overlapping the piece's bottom-right corner
 * showing the count. Optionally tappable/selectable.
 */
@Composable
internal fun PocketPiece(
    type: PieceType,
    color: EngineColor,
    count: Int,
    cell: Dp,
    selected: Boolean,
    onTap: (() -> Unit)?,
    badgeOffsetUnits: Float = 0f,
    horizontalPadUnits: Float = 0.3f,
) {
    val badgeOffset = badgeOffsetUnits.gridUnitsAsDp()
    Box(
        modifier = Modifier
            .padding(horizontal = horizontalPadUnits.gridUnitsAsDp())
            .then(if (selected) Modifier.border(2.dp, MARK_SHADE) else Modifier)
            .then(if (onTap != null) Modifier.lightClickable { onTap() } else Modifier)
            .padding(2.dp),
    ) {
        Box(modifier = Modifier.size(cell), contentAlignment = Alignment.Center) {
            BankPieceGlyph(piece = Piece(color, type), squareSize = cell)
            if (count > 1) {
                // Badge is pinned to a fixed size (not derived from the glyph) so it
                // reads the same in review's smaller pockets as during play. The offset
                // nudges it toward the corner so it covers less of the (small) glyph.
                CountBadge(
                    count = count,
                    diameter = POCKET_BADGE_DIAMETER_UNITS.gridUnitsAsDp(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = badgeOffset, y = badgeOffset),
                )
            }
        }
    }
}

/** A small count badge: a background-coloured circle with the number, sized to overlap a piece corner. */
@Composable
private fun CountBadge(count: Int, diameter: Dp, modifier: Modifier) {
    val density = LocalDensity.current
    val fontSize = with(density) { (diameter * 0.7f).toSp() }
    Box(
        modifier = modifier
            .size(diameter)
            .background(LightThemeTokens.colors.background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$count",
            color = LightThemeTokens.colors.content,
            style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            maxLines = 1,
        )
    }
}

@Composable
private fun SquareCell(
    square: Int,
    squareSize: Dp,
    state: BoardUiState,
    // The board to draw the piece from — usually [state.board], but the pre-explosion
    // board while an Atomic capture slide is in flight (see [ChessBoard]).
    board: List<Piece?>,
    // Null on a read-only board — see [ChessBoard].
    onSquareTap: ((Int, Boolean) -> Unit)?,
    // Null when this board offers no analysis sandbox — see [ChessBoard].
    onLongPress: (() -> Unit)? = null,
    hidePiece: Boolean = false,
    // False while a move slide is in flight — the checkmate king only turns sideways once
    // the mating move has landed, so the rotation reads as a beat AFTER the move, not during.
    moveSettled: Boolean = true,
) {
    val background = if (Square.isLight(square)) BOARD_LIGHT else BOARD_DARK
    val isSelected = square == state.selectedSquare
    // Last-move marks hide while a piece is selected — both use the identical
    // edge-flush frame, so showing both at once would be visually ambiguous. This
    // also covers a picked-up Crazyhouse pocket piece (selectedDrop), which likewise
    // paints selection/drop marks the last-move frame would clash with.
    val hasSelection = state.selectedSquare != null || state.selectedDrop != null
    val isLastMove = !hasSelection &&
        (square == state.lastMoveFrom || square == state.lastMoveTo)
    val piece = board.getOrNull(square)

    Box(
        modifier = Modifier
            .size(squareSize)
            .background(background)
            // combinedClickable routes taps through Compose rather than the SDK's
            // lightClickable, so it brings no LP haptic of its own (only Compose's
            // built-in long-press one). tapHaptic adds the finger-down tap haptic back,
            // as a passive observer that doesn't compete for the gesture.
            .tapHaptic(enabled = onSquareTap != null)
            // A single gesture recognizer handles both: quick tap moves/selects as
            // before, long-press enters the analysis sandbox. Using one
            // combinedClickable (instead of a plain lightClickable plus a separate
            // pointerInput long-press detector) avoids two competing recognizers on
            // the same pointer input — the standard Compose way to layer tap +
            // long-press on the same target.
            //
            // Installed when EITHER taps or long-press is offered: a read-only review
            // board still needs the recognizer so long-press can open analysis, even
            // though its onClick is a no-op (and tapHaptic above stays off, so a tap
            // that does nothing still feels like nothing).
            .then(
                if (onSquareTap == null && onLongPress == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        interactionSource = null,
                        indication = null,
                        onLongClick = onLongPress,
                        onClick = { onSquareTap?.invoke(square, true) },
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected || isLastMove) {
            Box(Modifier.matchParentSize().border(squareSize * 0.04f, MARK_SHADE))
        }
        // The check/checkmate marching border is drawn at the ChessBoard level (one
        // overlay, not per-square), so nothing to draw here. Checkmate ALSO turns the
        // king sideways — that rotation is applied to the piece glyph just below.
        // hidePiece: this square is the landing square of an in-flight slide; the sliding
        // overlay draws the piece instead so it isn't shown twice.
        if (!hidePiece) {
            piece?.let {
                // Turns the checkmated king onto its side once the mating move has SETTLED
                // (moveSettled) — so the rotation plays as a beat after the piece lands, not
                // during its slide — and rotates back on undo (stepping out of the mate).
                // animateFloatAsState eases toward the current target at the normal slide
                // pace (MOVE_ANIM_MS). Gate on moveSettled, NOT animatingMove == null:
                // animatingMove persists between moves, so it's rarely null.
                val angle by animateFloatAsState(
                    targetValue = if (square == state.checkmateKingSquare && moveSettled) CHECKMATE_KING_ROTATION else 0f,
                    animationSpec = tween(MOVE_ANIM_MS),
                    label = "checkmate-king-angle",
                )
                // The in-person game's piece rotation: across-table (only the far side's
                // pieces turn) or rigid (the whole board turned, so every piece does) —
                // see [pieceRotation]. Zero everywhere else. ADDED to the checkmate
                // rotation, so a mated king still turns sideways relative to its reader.
                PieceGlyph(
                    piece = it,
                    squareSize = squareSize,
                    rotationDegrees = angle + state.pieceRotation(it),
                )
            }
        }
        if (square in state.legalDestinations || square in state.dropTargets) {
            // A drop always targets an empty square, so it reads as the non-capture marker.
            LegalMarker(isCapture = square in state.legalDestinations && piece != null, squareSize = squareSize)
        }
        // Three-check: a running tally on each king of how many times that side has been
        // checked (same count-badge treatment as the Crazyhouse pockets). Always shown
        // (including 0) so it reads as a persistent scoreboard for the variant.
        if (!hidePiece && state.variant == Variant.THREE_CHECK && piece?.type == PieceType.KING) {
            CountBadge(
                count = state.checkCounts[piece.color] ?: 0,
                diameter = squareSize * 0.4f,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        // Variant goal squares are drawn as a single combined dashed border at the
        // ChessBoard level, not per-square — nothing to draw here.
    }
}

@Composable
internal fun PieceGlyph(piece: Piece, squareSize: Dp, rotationDegrees: Float = 0f) {
    // Custom piece art (vector drawables): the white/black variants bake in a fill
    // plus a contrasting outline stroke, so a piece reads on a same-coloured square
    // without any runtime tint. Rendered Fit-inside a square box so it stays centred.
    // [rotationDegrees] is used only for the checkmated king (turned sideways).
    Image(
        painter = painterResource(pieceDrawable(piece)),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(squareSize * PIECE_SCALE).rotate(rotationDegrees),
    )
}

/** Same as [PieceGlyph], but for the material bank/pocket bars — see [pieceDrawableBank]. */
@Composable
private fun BankPieceGlyph(piece: Piece, squareSize: Dp) {
    Image(
        painter = painterResource(pieceDrawableBank(piece)),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(squareSize * PIECE_SCALE),
    )
}

/** Map a piece to its vector-drawable resource (color variant × type). */
private fun pieceDrawable(piece: Piece): Int {
    val white = piece.color == EngineColor.WHITE
    return when (piece.type) {
        PieceType.PAWN -> if (white) R.drawable.piece_white_pawn else R.drawable.piece_black_pawn
        PieceType.KNIGHT -> if (white) R.drawable.piece_white_knight else R.drawable.piece_black_knight
        PieceType.BISHOP -> if (white) R.drawable.piece_white_bishop else R.drawable.piece_black_bishop
        PieceType.ROOK -> if (white) R.drawable.piece_white_rook else R.drawable.piece_black_rook
        PieceType.QUEEN -> if (white) R.drawable.piece_white_queen else R.drawable.piece_black_queen
        PieceType.KING -> if (white) R.drawable.piece_white_king else R.drawable.piece_black_king
    }
}

/**
 * Same mapping as [pieceDrawable], but to the "_bank" drawable variants used by the
 * material row and Crazyhouse pocket bars — those sit on the plain (often true-black)
 * screen background rather than a board square, so they keep the lighter `#3A3A3A`
 * outline instead of the board pieces' pure-black one.
 */
private fun pieceDrawableBank(piece: Piece): Int {
    val white = piece.color == EngineColor.WHITE
    return when (piece.type) {
        PieceType.PAWN -> if (white) R.drawable.piece_white_pawn_bank else R.drawable.piece_black_pawn_bank
        PieceType.KNIGHT -> if (white) R.drawable.piece_white_knight_bank else R.drawable.piece_black_knight_bank
        PieceType.BISHOP -> if (white) R.drawable.piece_white_bishop_bank else R.drawable.piece_black_bishop_bank
        PieceType.ROOK -> if (white) R.drawable.piece_white_rook_bank else R.drawable.piece_black_rook_bank
        PieceType.QUEEN -> if (white) R.drawable.piece_white_queen_bank else R.drawable.piece_black_queen_bank
        PieceType.KING -> if (white) R.drawable.piece_white_king_bank else R.drawable.piece_black_king_bank
    }
}

@Composable
private fun LegalMarker(isCapture: Boolean, squareSize: Dp) {
    if (isCapture) {
        Box(modifier = Modifier.size(squareSize).border(squareSize * 0.04f, MARK_SHADE, CircleShape))
    } else {
        Box(
            modifier = Modifier
                .size(squareSize * 0.25f)
                .background(MARK_SHADE, CircleShape)
        )
    }
}

@Composable
private fun PromotionOverlay(myColor: EngineColor, variant: Variant, viewModel: BoardViewModel) {
    // Order the offered pieces by usefulness: queen first.
    val choices = variant.promotionChoices
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            center = LightTopBarCenter.Text("Promote to"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val cell = 6f.gridUnitsAsDp()
            // Four choices fill the width exactly at this cell size, so Antichess's fifth
            // (the king) has to wrap rather than shrink every target to fit.
            Column(
                verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                choices.chunked(if (choices.size > 4) 3 else 4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp())) {
                        row.forEach { type ->
                            Box(
                                modifier = Modifier
                                    .size(cell)
                                    .lightClickable { viewModel.choosePromotion(type) },
                                contentAlignment = Alignment.Center,
                            ) {
                                PieceGlyph(piece = Piece(myColor, type), squareSize = cell)
                            }
                        }
                    }
                }
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = { viewModel.cancelPromotion() },
                    contentDescription = "Cancel promotion",
                ),
            ),
        )
    }
}

/**
 * In-game chat: a simple message list (most-recent at the bottom) plus a "Message" action
 * that opens a full-screen [LightTextInputEditor] to compose and send one. The list is the
 * game's full player-room history — [BoardViewModel] fetches it from Lichess on open (the
 * board stream only ever delivers messages sent while it is connected), so nothing is lost
 * across restarts. Opening this panel marks everything in it read.
 */
@Composable
private fun ChatOverlay(state: BoardUiState, viewModel: BoardViewModel) {
    var composing by remember { mutableStateOf(false) }

    if (composing) {
        val textState = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()
        LightTextInputEditor(
            // Titled with who you're writing to. The RAW handle, not the top bar's
            // "name · rating" display form; falls back to that only in the brief
            // window before gameFull has landed.
            title = state.opponentUsername ?: state.opponentName,
            state = textState,
            onSubmit = {
                viewModel.sendChat(it.toString())
                textState.clearText()
                composing = false
            },
            onBack = { composing = false },
            keyboardOptionsFlow = keyboardOptions,
            submitLabel = "SEND",
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { viewModel.closeChat() },
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("Chat"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            // Most-recent-first: the list is oldest-first, so the newest message is at the
            // BOTTOM — but a scroll view opens at offset 0, i.e. on the OLDEST. Drive the
            // scroll position ourselves (LightScrollView takes a ScrollState) so the feed
            // opens on, and stays pinned to, the newest message.
            val scrollState = rememberScrollState()
            LaunchedEffect(state.chatMessages.size) {
                // maxValue is only correct once the new content has been laid out, so
                // observe it rather than reading it immediately: the first emission after
                // a message lands is the one that actually reaches the bottom.
                snapshotFlow { scrollState.maxValue }.collect { scrollState.scrollTo(it) }
            }
            // BoxWithConstraints must sit OUTSIDE the scroll view: inside a verticalScroll
            // the height constraint is infinite, so it could not report a viewport height.
            // The height it gives us lets the feed bottom-anchor (below) while still
            // scrolling normally once it outgrows the viewport.
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val viewportHeight = maxHeight
                // Cap how wide a single message may get. Left/right alignment is the ONLY cue
                // for who said what (there are no bubbles), and a message spanning the full
                // width destroys that cue entirely. Measured against the real text column —
                // maxWidth less the feed Column's horizontal padding and the scrollbar gutter
                // LightScrollView permanently reserves — so the fraction means what it says.
                // Read here rather than inside the scroll view's content lambda, where the
                // BoxWithConstraints receiver is no longer the implicit one.
                val chatMessageMaxWidth = (
                    maxWidth - (2f + scrollBarGutterUnits(LightScrollBarPosition.Outside))
                        .gridUnitsAsDp()
                    ) * CHAT_MESSAGE_MAX_WIDTH_FRACTION
                LightScrollView(
                    modifier = Modifier.fillMaxSize(),
                    scrollState = scrollState,
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(min = viewportHeight)
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                        // Short conversations sit just above the compose button rather than
                        // hanging off the top bar. (Spacer(weight)/Modifier.weight can't do
                        // this — there is no "remaining" height inside a scrollable column.)
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        if (state.chatMessages.isEmpty()) {
                            LightText(text = "No messages yet.", variant = LightTextVariant.Detail)
                        }
                        // LightScrollView permanently reserves a scrollbar gutter on the
                        // right (`padding(end = scrollBarGutterUnits(...))`, 2 grid units for
                        // the default Outside position — whether or not the bar is showing).
                        // With symmetric 1-unit padding that makes our content box sit LEFT
                        // of the screen's true centre by half the gutter, so a centred system
                        // line lands ~1 grid unit (40px at 1080) left of centre. Shift just
                        // that line back by half the gutter; Compose forbids negative padding,
                        // hence offset. Derived from the SDK's own value, not hardcoded.
                        // (Switching the whole view to LightScrollBarPosition.Inside would
                        // also centre it, but then the scrollbar overlays the right-aligned
                        // messages — worse, so only the system line moves.)
                        val systemCenteringOffset =
                            (scrollBarGutterUnits(LightScrollBarPosition.Outside) / 2f).gridUnitsAsDp()
                        state.chatMessages.forEachIndexed { index, msg ->
                            val previous = state.chatMessages.getOrNull(index - 1)
                            val gapUnits = chatGapUnits(previous, msg)
                            if (gapUnits > 0f) {
                                Spacer(modifier = Modifier.height(gapUnits.gridUnitsAsDp()))
                            }
                            if (msg.system) {
                                // Lichess's own announcements ("Takeback sent", …): no sender
                                // label at all, centred, and rendered in the same small dimmed
                                // treatment as a sender name so they read as a note rather than
                                // as someone's message.
                                LightText(
                                    text = msg.text,
                                    variant = CHAT_NAME_VARIANT,
                                    lighten = true,
                                    align = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset(x = systemCenteringOffset),
                                )
                            } else {
                                // Mine on the right, the opponent's on the left.
                                val mine = !msg.fromOpponent
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                                ) {
                                    // Consecutive messages from one person are one block: the
                                    // name is drawn once, at the top. Anything in between —
                                    // including a system note — breaks the run, so the name
                                    // reappears on the next message.
                                    if (!sameSpeaker(previous, msg)) {
                                        LightText(
                                            text = msg.username,
                                            variant = CHAT_NAME_VARIANT,
                                            lighten = true,
                                        )
                                    }
                                    // widthIn, NOT fillMaxWidth: a short message keeps its
                                    // intrinsic width (so the alignment above still places it),
                                    // and only a long one is capped — which is the whole point,
                                    // since a full-width message gives no visual cue as to
                                    // whose it is. Because a wrapped message DOES end up
                                    // exactly this wide, `align` then right-justifies my own
                                    // lines so the block reads as one right-hand column.
                                    LightText(
                                        text = msg.text,
                                        variant = LightTextVariant.Copy,
                                        modifier = Modifier.widthIn(max = chatMessageMaxWidth),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            LightBottomBar(
                items = listOf(
                    null,
                    LightBarButton.LightIcon(
                        icon = LightIcons.COMPOSE_MESSAGE,
                        onClick = { composing = true },
                        contentDescription = "New message",
                    ),
                ),
            )
        }
    }
}

/**
 * Space (grid units) above [current], decided by the item immediately before it — see the
 * CHAT_GAP_* constants. Null [previous] means it is the first item, which needs no gap.
 */
private fun chatGapUnits(previous: ChatMessage?, current: ChatMessage): Float = when {
    previous == null -> 0f
    previous.system && current.system -> CHAT_GAP_BETWEEN_SYSTEM_UNITS
    // Either entering or leaving a run of system notes.
    previous.system || current.system -> CHAT_GAP_AROUND_SYSTEM_UNITS
    sameSpeaker(previous, current) -> CHAT_GAP_SAME_SPEAKER_UNITS
    else -> CHAT_GAP_NEW_SPEAKER_UNITS
}

/**
 * True when both are real (non-system) messages from the same person — which both tightens
 * the gap and suppresses the repeated sender name.
 */
private fun sameSpeaker(previous: ChatMessage?, current: ChatMessage): Boolean =
    previous != null &&
        !previous.system &&
        !current.system &&
        previous.fromOpponent == current.fromOpponent &&
        previous.username == current.username

@Composable
private fun ActionMenuOverlay(
    showGameActions: Boolean,
    canOfferDraw: Boolean,
    claimDrawAvailable: Boolean,
    canAbort: Boolean,
    canRequestTakeback: Boolean,
    showAnalysisOption: Boolean,
    viewModel: BoardViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            center = LightTopBarCenter.Text("Menu"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Also reachable straight from the menu icon when there's an unread badge
            // or by long pressing anywhere on the chess board.
            MenuRow("Chat") { viewModel.openChat() }
            // Local sandbox — any legal move from here, never sent to Lichess. Hidden
            // while already active, back is the exit path).
            if (showAnalysisOption) MenuRow("Analysis") { viewModel.enterAnalysis() }
            if (showGameActions) {
                // Lichess requires at least one move played before a takeback makes sense.
                if (canRequestTakeback) MenuRow("Request takeback") { viewModel.requestTakeback() }
                // Lichess only allows a draw offer once both players have moved.
                // With a threefold on the board, `draw/yes` no longer OFFERS — lila ends
                // the game outright — so the row must not keep saying "Offer draw".
                if (claimDrawAvailable) {
                    MenuRow("Claim draw") { viewModel.requestClaimDraw() }
                } else if (canOfferDraw) {
                    MenuRow("Offer draw") { viewModel.requestDraw() }
                }
                MenuRow("Resign") { viewModel.requestResign() }
                // Lichess only allows aborting before both players have moved.
                if (canAbort) MenuRow("Abort game") { viewModel.requestAbort() }
            }
            // v1: PGN export disabled — may re-add
            // // SDK has no sanctioned email/share hook (raw intents are blocked), so PGN export is
            // // copy-to-clipboard only. Revisit if LightOS later exposes a share/forward capability.
            // MenuRow("Copy PGN") { viewModel.copyPgn() }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = { viewModel.closeMenu() },
                    contentDescription = "Close menu",
                ),
            ),
        )
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Subheading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
    )
}

@Composable
private fun ConfirmationOverlay(confirmation: Confirmation, viewModel: BoardViewModel) {
    val message = when (confirmation) {
        Confirmation.RESIGN -> "Resign this game?"
        Confirmation.ABORT -> "Abort this game?"
        Confirmation.DRAW -> "Offer a draw?"
        Confirmation.TAKEBACK -> "Request a takeback?"
        Confirmation.CLAIM_DRAW -> "Claim a draw by repetition?"
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
        LightBottomBar(
            items = listOf(
                null,
                LightBarButton.Text(
                    text = "CONFIRM",
                    onClick = {
                        when (confirmation) {
                            Confirmation.RESIGN -> viewModel.confirmResign()
                            Confirmation.ABORT -> viewModel.confirmAbort()
                            Confirmation.DRAW -> viewModel.confirmDraw()
                            Confirmation.TAKEBACK -> viewModel.confirmTakeback()
                            Confirmation.CLAIM_DRAW -> viewModel.confirmClaimDraw()
                        }
                    },
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = { viewModel.cancelConfirmation() },
                    contentDescription = "Cancel",
                ),
            ),
        )
    }
}

/**
 * Map a grid cell (row 0 = top, col 0 = left) to an engine square (0..63), oriented
 * so the user's back rank sits at the bottom. White: a1 at bottom-left. Black: a
 * full 180° rotation (the standard chess flip), which puts the black back rank at
 * the bottom. (The build note's "h1 bottom-left" for black is inconsistent with
 * "back rank at the bottom"; the primary rule is honored here, giving h8 at
 * bottom-left for black.)
 */
private fun squareAt(row: Int, col: Int, flipped: Boolean): Int =
    if (!flipped) {
        Square.of(col, 7 - row)
    } else {
        Square.of(7 - col, row)
    }
