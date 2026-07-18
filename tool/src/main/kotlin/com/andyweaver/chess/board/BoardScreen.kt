package com.andyweaver.chess.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private val BOARD_DARK = Color(0xFF1B1B1B)
private val BOARD_LIGHT = Color(0xFF5B5B5B)

// Every indicator mark (dots, legal-move ring, last-move box, selection box) shares
// this shade; the legal-move dot additionally gets a thin contact outline.
private val MARK_SHADE = Color(0xFFB7B7B7)
private val DOT_OUTLINE = Color(0x8C000000)

private val CHECK_FILL = Color(0x59C0392B)          // muted red — king in check
private val GOAL_OUTLINE = Color(0xFFCC3B3B)        // red — variant goal squares (KotH centre, Racing Kings rank 8)

// Skip-to-start/end bar height, in grid units. The nav arrows render inside a 2f
// box but the visible glyph is padded within it, so a full-2f bar looks taller
// than the arrows. This is tuned to match the arrow's visible height — tweak here.
private const val SKIP_BAR_HEIGHT_UNITS = 1.4f

// Fraction of a square a piece glyph occupies (Fit-scaled, centred). Tune to taste.
private const val PIECE_SCALE = 0.82f

// Duration of a piece slide when stepping/scrubbing between positions (ms).
private const val MOVE_ANIM_MS = 180

private const val SCRUB_SENSITIVITY = 0.7f

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
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        center = LightTopBarCenter.TwoLineDetail(
                            line1 = state.opponentName,
                            line2 = state.subtitle,
                        ),
                        rightButton = LightBarButton.LightIcon(
                            icon = LightIcons.ELLIPSES,
                            onClick = { viewModel.openMenu() },
                            contentDescription = "Menu",
                        ),
                    )

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
                            // Opponent's reserves above the board (read-only), hugged tight
                            // to the board so it can grow toward the bottom bar.
                            ReadOnlyPocketBar(
                                pocket = state.opponentPocket,
                                color = state.myColor.opposite,
                                verticalPadUnits = 0.15f,
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 0.5f.gridUnitsAsDp()),
                                contentAlignment = Alignment.Center,
                            ) {
                                ChessBoard(state = state, onSquareTap = viewModel::onSquareTap)
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
                                ChessBoard(state = state, onSquareTap = viewModel::onSquareTap)
                            }
                        }
                        // My material/reserves live in the bottom bar next to the browse
                        // arrows (both variants) — see BottomControls. This frees the row
                        // below the board so the board itself can grow.
                        BottomControls(state = state, viewModel = viewModel)
                    }
                }

                if (state.promotionActive) {
                    PromotionOverlay(myColor = state.myColor, viewModel = viewModel)
                }

                if (state.menuOpen) {
                    ActionMenuOverlay(
                        showGameActions = !state.terminal,
                        canOfferDraw = state.canOfferDraw,
                        canAbort = state.canAbort,
                        viewModel = viewModel,
                    )
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
                    onTap = viewModel::onPocketTap,
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
            LightBottomBar(
                items = listOf(
                    null,
                    LightBarButton.Text(
                        text = "CONFIRM",
                        onClick = { viewModel.confirmPendingMove() },
                    ),
                    LightBarButton.LightIcon(
                        icon = LightIcons.CLOSE,
                        onClick = { viewModel.cancelPendingMove() },
                        contentDescription = "Cancel move",
                    ),
                ),
            )
        }
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
            .moveScrubX(currentFraction = state.viewFraction, onSeek = onSeek)
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
            .moveScrubX(currentFraction = state.viewFraction, onSeek = onSeek)
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
 * This modifier when applied to an object enables Long-press-then-drag to scrub through move history.
 * The long press arms it (so quick taps on the arrows still work); horizontal position across the
 * position then maps 0→1 across all moves. [onSeek] receives that fraction.
 */
private fun Modifier.moveScrubX(
    currentFraction: Float,
    onSeek: (Float) -> Unit
): Modifier = composed {
    val currentFractionState by rememberUpdatedState(currentFraction)
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
                val deltaX = change.position.x - startX
                // Add the relative movement (delta / width) to our starting fraction
                onSeekState((startFraction + deltaX / (width * SCRUB_SENSITIVITY)).coerceIn(0f, 1f))
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
private fun NavArrow(
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
internal fun ChessBoard(state: BoardUiState, onSquareTap: (Int) -> Unit) {
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
            if (anim != null) progress.animateTo(1f, animationSpec = tween(MOVE_ANIM_MS))
        }
        val sliding = anim != null && progress.value < 1f
        val hidden: Set<Int> = if (sliding) anim.slides.mapTo(HashSet()) { it.endSquare } else emptySet()

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
                                onSquareTap = onSquareTap,
                                hidePiece = square in hidden,
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
                        PieceGlyph(piece = s.piece, squareSize = squareSize)
                    }
                }
            }
        }
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
private val POCKET_ORDER = listOf(
    PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT, PieceType.PAWN,
)

// Pocket piece cell sizes (grid units). The opponent's read-only row above the
// board uses the same glyph size as the player's bar, just with less vertical space.
private const val MY_POCKET_CELL_UNITS = 2.4f
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
private const val MATERIAL_GROUP_GAP_UNITS = 0.9f
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
private fun MaterialFan(captured: List<PieceType>, capturedColor: EngineColor, advantage: Int, cellUnits: Float) {
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
 * A read-only, centered row of reserves — used for the opponent's pocket above the
 * board during play (at [OPP_POCKET_CELL_UNITS]) and for both sides' pockets stacked
 * above the browse bar in Crazyhouse review (at [MATERIAL_CELL_UNITS]). The count
 * badges stay a fixed size ([POCKET_BADGE_DIAMETER_UNITS]) regardless of glyph size.
 */
@Composable
internal fun ReadOnlyPocketBar(
    pocket: Map<PieceType, Int>,
    color: EngineColor,
    cellUnits: Float = OPP_POCKET_CELL_UNITS,
    verticalPadUnits: Float = 0.4f,
    badgeOffsetUnits: Float = 0f,
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
                selected = false,
                onTap = null,
                badgeOffsetUnits = badgeOffsetUnits,
            )
        }
    }
}

/**
 * Crazyhouse bottom bar: the player's reserves centered between a back arrow (left
 * edge) and a forward arrow (right edge). Tap a reserve piece to pick it up to drop.
 */
@Composable
private fun CrazyhousePocketBar(
    state: BoardUiState,
    onTap: (PieceType) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .moveScrubX(currentFraction = state.viewFraction, onSeek = onSeek)
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
                    selected = type == state.selectedDrop,
                    onTap = { onTap(type) },
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
private fun MaterialBottomBar(
    state: BoardUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .moveScrubX(currentFraction = state.viewFraction, onSeek = onSeek)
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
private fun PocketPiece(
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
    onSquareTap: (Int) -> Unit,
    hidePiece: Boolean = false,
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
    val piece = state.board.getOrNull(square)

    Box(
        modifier = Modifier
            .size(squareSize)
            .background(background)
            .lightClickable { onSquareTap(square) },
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected || isLastMove) {
            Box(Modifier.matchParentSize().border(squareSize * 0.04f, MARK_SHADE))
        }
        if (square == state.checkedKingSquare) {
            Box(Modifier.matchParentSize().background(CHECK_FILL))
        }
        // hidePiece: this square is the landing square of an in-flight slide; the sliding
        // overlay draws the piece instead so it isn't shown twice.
        if (!hidePiece) piece?.let { PieceGlyph(piece = it, squareSize = squareSize) }
        if (square in state.legalDestinations || square in state.dropTargets) {
            // A drop always targets an empty square, so it reads as the non-capture marker.
            LegalMarker(isCapture = square in state.legalDestinations && piece != null, squareSize = squareSize)
        }
        // Variant goal squares (KotH centre, Racing Kings rank 8): a red outline, drawn
        // on top so it stays visible over pieces and highlights.
        if (square in state.goalSquares) {
            Box(Modifier.matchParentSize().border(0.18f.gridUnitsAsDp(), GOAL_OUTLINE))
        }
    }
}

@Composable
private fun PieceGlyph(piece: Piece, squareSize: Dp) {
    // Custom piece art (vector drawables): the white/black variants bake in a fill
    // plus a contrasting outline stroke, so a piece reads on a same-coloured square
    // without any runtime tint. Rendered Fit-inside a square box so it stays centred.
    Image(
        painter = painterResource(pieceDrawable(piece)),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(squareSize * PIECE_SCALE),
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
private fun ActionMenuOverlay(
    showGameActions: Boolean,
    canOfferDraw: Boolean,
    canAbort: Boolean,
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
            if (showGameActions) {
                MenuRow("Resign") { viewModel.requestResign() }
                // Lichess only allows aborting before both players have moved.
                if (canAbort) MenuRow("Abort game") { viewModel.requestAbort() }
                // Lichess only allows a draw offer once both players have moved.
                if (canOfferDraw) MenuRow("Offer draw") { viewModel.requestDraw() }
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
