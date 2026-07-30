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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.andyweaver.chess.engine.Color as EngineColor
import com.andyweaver.chess.engine.Piece
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Square
import com.andyweaver.chess.engine.Variant
import com.andyweaver.chess.R
import com.andyweaver.chess.lichess.LichessApi
import com.andyweaver.chess.settings.ChessSettings
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
//   • a drag of SCRUB_SWEEP_FRACTION of the bar's width advances SCRUB_SWEEP_PLIES moves.
// That fixes the rate (moves per pixel). A game longer than SCRUB_SWEEP_PLIES just needs
// more than one sweep to cross; a shorter one, less. Tune SCRUB_SWEEP_FRACTION for overall
// speed (higher = slower/finer) — Andy's starting point is 0.75.
private const val SCRUB_SWEEP_FRACTION = 0.75f
private const val SCRUB_SWEEP_PLIES = 40f

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
    private val currentFen: String? = null,
    private val seededOpponentName: String? = null,
    private val seededVariant: Variant = Variant.STANDARD,
    private val seededLastMove: String? = null,
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
            currentFen = currentFen,
            seededOpponentName = seededOpponentName,
            seededVariant = seededVariant,
            seededLastMove = seededLastMove,
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

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Hand-rolled rightButton instead of LightTopBar's own slot: that slot
                    // renders via an SDK-internal button view that takes no custom Modifier,
                    // so it can't carry the unread-count badge (see HomeScreen's manual
                    // refresh icon for the same workaround). This Box reproduces
                    // LightTopBar's own rightButton position (TopEnd).
                    Box(modifier = Modifier.fillMaxWidth()) {
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
                        // straight back (unread counts keep accruing underneath).
                        if (!state.analysisActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .height(3f.gridUnitsAsDp())
                                    .padding(horizontal = 1f.gridUnitsAsDp()),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Box {
                                    LightIcon(
                                        icon = LightIcons.ELLIPSES,
                                        contentDescription = "Menu",
                                        modifier = Modifier.lightClickable(onClick = {
                                            // Unread messages take priority: tapping the menu
                                            // icon opens chat directly rather than the action
                                            // menu (chat is still reachable from the menu's
                                            // "Chat" row when there's nothing unread).
                                            if (state.unreadChatCount > 0) viewModel.openChat() else viewModel.openMenu()
                                        }),
                                    )
                                    if (state.unreadChatCount > 0) {
                                        CountBadge(
                                            count = state.unreadChatCount,
                                            diameter = 1.4f.gridUnitsAsDp(),
                                            modifier = Modifier.align(Alignment.TopEnd),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.unsupportedVariant != null) {
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
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 0.5f.gridUnitsAsDp()),
                                contentAlignment = Alignment.Center,
                            ) {
                                ChessBoard(
                                    state = state,
                                    onSquareTap = viewModel::onSquareTap,
                                    onLongPress = { viewModel.onBoardLongPress() },
                                )
                            }
                        } else {
                            // Opponent's captured pieces align to the board's left edge (the
                            // board is centered, so [inset] is the gap to that edge).
                            CenteredBoard(
                                reservedUnits = 2f,
                                topBank = { inset ->
                                    MaterialRow(
                                        captured = state.opponentCaptured,
                                        capturedColor = state.myColor,
                                        advantage = state.opponentAdvantage,
                                        startPad = inset,
                                    )
                                },
                            ) {
                                ChessBoard(
                                    state = state,
                                    onSquareTap = viewModel::onSquareTap,
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
                    PromotionOverlay(myColor = state.moverColor ?: state.myColor, viewModel = viewModel)
                }

                if (state.menuOpen) {
                    ActionMenuOverlay(
                        showGameActions = !state.terminal,
                        canOfferDraw = state.canOfferDraw,
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

                // Opponent offered a draw: prompt to accept/decline (unless another
                // overlay is up).
                if (state.incomingDrawOffer && !state.menuOpen &&
                    state.confirmation == null && !state.promotionActive
                ) {
                    DrawOfferOverlay(viewModel = viewModel)
                }

                // Opponent offered a takeback: prompt to accept/decline (unless another
                // overlay is up).
                if (state.incomingTakebackOffer && !state.incomingDrawOffer && !state.menuOpen &&
                    state.confirmation == null && !state.promotionActive
                ) {
                    TakebackOfferOverlay(viewModel = viewModel)
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
                    onSeek = { viewModel.seekToFraction(it) },
                )
            } else {
                // Standard: the bottom bar carries my captured pieces (left-aligned)
                // between back/forward arrows — mirroring the Crazyhouse layout. No
                // skip-to-start/end during play; those are review-only.
                MaterialBottomBar(
                    state = state,
                    onBack = { viewModel.stepBack() },
                    onForward = { viewModel.stepForward() },
                    onSeek = { viewModel.seekToFraction(it) },
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
        NavArrow(LightIcons.BACK, "Previous move", canStepBack, onBack)
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", canStepForward, onForward)
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
    onSeek: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .moveScrubX(currentFraction = state.viewFraction, totalPlies = state.totalPlies, onSeek = onSeek)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipControl(BarSide.LEADING, LightIcons.BACK, "First move", state.canStepBack, onSkipStart)
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack, onBack)
        Row(
            modifier = Modifier
                .weight(1f),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaterialFan(
                captured = state.myCaptured,
                capturedColor = state.myColor.opposite,
                advantage = state.myAdvantage,
                cellUnits = MATERIAL_CELL_UNITS,
            )
        }
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward, onForward)
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
    onSeek: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .moveScrubX(currentFraction = state.viewFraction, totalPlies = state.totalPlies, onSeek = onSeek)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipControl(BarSide.LEADING, LightIcons.BACK, "First move", state.canStepBack, onSkipStart)
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack, onBack)
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
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward, onForward)
        Spacer(Modifier.width(1f.gridUnitsAsDp()))
        SkipControl(BarSide.TRAILING, LightIcons.ARROW_RIGHT, "Last move", state.canStepForward, onSkipEnd)
    }
}

/**
 * Long-press-then-drag to scrub through move history. The long press arms it (so quick taps
 * on the arrows still work); horizontal drag then advances the game at a FIXED moves-per-pixel
 * rate (see [SCRUB_SWEEP_FRACTION]/[SCRUB_SWEEP_PLIES]) — so the feel is the same regardless
 * of how many moves the game has. [totalPlies] is the game's move count (0 = nothing to
 * scrub); [onSeek] receives the resulting 0→1 position fraction.
 */
internal fun Modifier.moveScrubX(
    currentFraction: Float,
    totalPlies: Int,
    onSeek: (Float) -> Unit
): Modifier = composed {
    val currentFractionState by rememberUpdatedState(currentFraction)
    val totalPliesState by rememberUpdatedState(totalPlies)
    val onSeekState by rememberUpdatedState(onSeek)

    pointerInput(Unit) {
        val width = size.width.toFloat()
        if (width <= 0f) return@pointerInput

        var startX = 0f
        var startFraction = 0f

        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                // Capture the baseline at the moment the long-press is recognized
                startX = offset.x
                startFraction = currentFractionState
            },
            onDrag = { change, _ ->
                change.consume()
                val plies = totalPliesState
                if (plies > 0) {
                    val deltaX = change.position.x - startX
                    // Constant rate: SCRUB_SWEEP_PLIES moves per (width * SCRUB_SWEEP_FRACTION)
                    // px, converted to a position fraction by dividing by the game's own ply
                    // count — so the drag distance per move is the same in a short game as a long one.
                    val movesMoved = deltaX * SCRUB_SWEEP_PLIES / (width * SCRUB_SWEEP_FRACTION)
                    onSeekState((startFraction + movesMoved / plies).coerceIn(0f, 1f))
                }
            },
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

@Composable
internal fun NavArrow(
    icon: LightIconConfiguration,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    LightIcon(
        icon = icon,
        contentDescription = contentDescription,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.3f)
            .then(if (enabled) Modifier.lightClickable(onClick = onClick) else Modifier),
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
    // Optional review clocks: shown rotated 90° in the inset at the board's right edge —
    // opponent's near the top, mine near the bottom — without shifting the centered board.
    topRightLabel: String? = null,
    bottomRightLabel: String? = null,
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
                // Equal-weight side gutters keep the fixed-size board centered; the right
                // gutter also carries the rotated clocks, flush to the board's edge.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.size(edge)) { board() }
                    Box(modifier = Modifier.weight(1f).height(edge)) {
                        topRightLabel?.let {
                            RotatedClock(it, Modifier.align(Alignment.TopStart).padding(start = 0.2f.gridUnitsAsDp(), top = 1f.gridUnitsAsDp()))
                        }
                        bottomRightLabel?.let {
                            RotatedClock(it, Modifier.align(Alignment.BottomStart).padding(start = 0.2f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()))
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
    onSquareTap: (Int) -> Unit,
    // Long-press anywhere on the board enters the analysis sandbox — or, if it's already
    // open, resets it back to the snapshot (see BoardViewModel.onBoardLongPress; the
    // ActionMenuOverlay "Analysis" row is the other entry point). Defaults to a no-op so
    // ReviewScreen and LocalGameScreen — which don't offer analysis — need no changes at
    // their call sites.
    onLongPress: () -> Unit = {},
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
        val hidden: Set<Int> = when {
            !sliding -> emptySet()
            captureSliding -> emptySet()
            else -> anim.slides.mapTo(HashSet()) { it.endSquare }
        }

        Box(modifier = Modifier.size(edge)) {
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
                        // Across-table mode rotates the far side's (Black) pieces 180° so
                        // the sliding overlay matches the static board (see [SquareCell]).
                        // Racing Kings starts everyone on the SAME side (no "far player's
                        // row"), so it never applies per-piece rotation — see [BoardUiState].
                        PieceGlyph(
                            piece = s.piece,
                            squareSize = squareSize,
                            rotationDegrees = if (state.acrossMode && state.variant != Variant.RACING_KINGS &&
                                s.piece.color == EngineColor.BLACK
                            ) 180f else 0f,
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
        MaterialFan(captured = captured, capturedColor = capturedColor, advantage = advantage, cellUnits = cellUnits)
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
) {
    val cell = cellUnits.gridUnitsAsDp()
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Groups advance by MATERIAL_GROUP_GAP_UNITS (center-to-center); the negative gap
        // vs the cell width overlaps adjacent group boxes so groups can be pulled tight.
        Row(horizontalArrangement = Arrangement.spacedBy(MATERIAL_GROUP_GAP_UNITS.gridUnitsAsDp() - cell)) {
            // The list is ordered by type, so distinct() gives the groups in display order.
            captured.distinct().forEach { type ->
                CapturedStack(type = type, color = capturedColor, count = captured.count { it == type }, cell = cell)
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
    onSeek: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .moveScrubX(currentFraction = state.viewFraction, totalPlies = state.totalPlies, onSeek = onSeek)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack, onBack)
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
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward, onForward)
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
    onSeek: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .moveScrubX(currentFraction = state.viewFraction, totalPlies = state.totalPlies, onSeek = onSeek)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow(LightIcons.BACK, "Previous move", state.canStepBack, onBack)
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 1f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaterialFan(
                captured = state.myCaptured,
                capturedColor = state.myColor.opposite,
                advantage = state.myAdvantage,
                cellUnits = MATERIAL_CELL_UNITS,
            )
        }
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", state.canStepForward, onForward)
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
    onSquareTap: (Int) -> Unit,
    onLongPress: () -> Unit = {},
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
            // A single gesture recognizer handles both: quick tap moves/selects as
            // before, long-press enters the analysis sandbox. Using one
            // combinedClickable (instead of a plain lightClickable plus a separate
            // pointerInput long-press detector) avoids two competing recognizers on
            // the same pointer input — the standard Compose way to layer tap +
            // long-press on the same target.
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onLongClick = onLongPress,
                onClick = { onSquareTap(square) },
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
                // Across-table mode (in-person game): the far side's pieces (Black, since
                // the board stays unflipped) are turned 180° to read upright to the player
                // opposite. Added to the checkmate rotation so a mated Black king still
                // turns sideways relative to that player. Zero (unchanged) off across mode,
                // and always zero for Racing Kings (both sides start on the same side, so
                // there's no "far player's row" to rotate).
                val acrossRotation = if (state.acrossMode && state.variant != Variant.RACING_KINGS &&
                    it.color == EngineColor.BLACK
                ) 180f else 0f
                PieceGlyph(piece = it, squareSize = squareSize, rotationDegrees = angle + acrossRotation)
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
private fun PromotionOverlay(myColor: EngineColor, viewModel: BoardViewModel) {
    // Order the offered pieces by usefulness: queen first.
    val choices = listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
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
            Row(horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp())) {
                val cell = 6f.gridUnitsAsDp()
                choices.forEach { type ->
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

@Composable
private fun DrawOfferOverlay(viewModel: BoardViewModel) {
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
            LightText(
                text = "Opponent offers a draw",
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
        LightBottomBar(
            items = listOf(
                null,
                LightBarButton.Text(text = "ACCEPT", onClick = { viewModel.acceptIncomingDraw() }),
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = { viewModel.declineIncomingDraw() },
                    contentDescription = "Decline draw",
                ),
            ),
        )
    }
}

@Composable
private fun TakebackOfferOverlay(viewModel: BoardViewModel) {
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
            LightText(
                text = "Opponent requests a takeback",
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
        LightBottomBar(
            items = listOf(
                null,
                LightBarButton.Text(text = "ACCEPT", onClick = { viewModel.acceptIncomingTakeback() }),
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = { viewModel.declineIncomingTakeback() },
                    contentDescription = "Decline takeback",
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
                                    LightText(text = msg.text, variant = LightTextVariant.Copy)
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
                if (canOfferDraw) MenuRow("Offer draw") { viewModel.requestDraw() }
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
