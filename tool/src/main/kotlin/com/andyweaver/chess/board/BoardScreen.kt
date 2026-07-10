package com.andyweaver.chess.board

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
// v1: PGN export disabled — may re-add (was used by the clipboard LaunchedEffect)
// import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
// v1: PGN export disabled — may re-add
// import android.content.ClipData
// import androidx.compose.ui.platform.ClipEntry
// import androidx.compose.ui.platform.LocalClipboard
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

// Classic light/dark board squares. Not pure black/white: keeping a little tone
// lets each piece's *fill* read on both squares, while the outline (below) is what
// guarantees visibility of a same-colored piece on a same-colored square.
private val LIGHT_SQUARE = Color(0xFFE8E8E8)
private val DARK_SQUARE = Color(0xFF4D4D4D)

// Faint full-square highlight tints (translucent so they read over both squares).
private val SELECTED_FILL = Color(0x5A4C9A78)      // muted green — the piece you picked up
private val LAST_MOVE_FILL = Color(0x4DB08A3E)      // muted amber — the last move (either player)
private val CHECK_FILL = Color(0x59C0392B)          // muted red — king in check
private val LEGAL_MARKER = Color(0x992E7D5B)        // teal dot/ring — legal destinations
private val GOAL_OUTLINE = Color(0xFFCC3B3B)        // red — variant goal squares (KotH centre, Racing Kings rank 8)

// Skip-to-start/end bar height, in grid units. The nav arrows render inside a 2f
// box but the visible glyph is padded within it, so a full-2f bar looks taller
// than the arrows. This is tuned to match the arrow's visible height — tweak here.
private const val SKIP_BAR_HEIGHT_UNITS = 1.4f

// Fraction of a square a piece glyph occupies (Fit-scaled, centred). Tune to taste.
private const val PIECE_SCALE = 0.82f

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
                        // Breathing room between the top bar and the board, matching the
                        // home/history convention (and roughly the board's bottom gap).
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
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
                            // Opponent's reserves above the board (read-only, small).
                            OpponentPocketBar(
                                pocket = state.opponentPocket,
                                color = state.myColor.opposite,
                            )
                        } else {
                            // Material lead: pieces the opponent captured (my colour) + their +N,
                            // hugging the top edge of the board.
                            MaterialRow(
                                captured = state.opponentCaptured,
                                capturedColor = state.myColor,
                                advantage = state.opponentAdvantage,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 0.5f.gridUnitsAsDp()),
                            contentAlignment = Alignment.Center,
                        ) {
                            ChessBoard(state = state, onSquareTap = viewModel::onSquareTap)
                        }
                        if (state.variant != Variant.CRAZYHOUSE) {
                            // Material lead: pieces I captured (opponent's colour) + my +N,
                            // hugging the bottom edge of the board. (Crazyhouse puts my
                            // reserves in the bottom bar instead — see BottomControls.)
                            MaterialRow(
                                captured = state.myCaptured,
                                capturedColor = state.myColor.opposite,
                                advantage = state.myAdvantage,
                            )
                        }

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
                    pocket = state.myPocket,
                    color = state.myColor,
                    selected = state.selectedDrop,
                    onTap = viewModel::onPocketTap,
                    canStepBack = state.canStepBack,
                    canStepForward = state.canStepForward,
                    onBack = { viewModel.stepBack() },
                    onForward = { viewModel.stepForward() },
                )
            } else {
                BrowseBar(
                    canStepBack = state.canStepBack,
                    canStepForward = state.canStepForward,
                    onBack = { viewModel.stepBack() },
                    onForward = { viewModel.stepForward() },
                    onSkipStart = { viewModel.stepToStart() },
                    onSkipEnd = { viewModel.stepToEnd() },
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
            .padding(top = 1f.gridUnitsAsDp())
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 2f.gridUnitsAsDp()),
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
                .width(2.dp)
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

@Composable
internal fun ChessBoard(state: BoardUiState, onSquareTap: (Int) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val edge: Dp = minOf(maxWidth, maxHeight)
        val squareSize = edge / 8
        Column(
            modifier = Modifier
                .size(edge)
                .border(boardBorderWidth(), LightThemeTokens.colors.content.copy(alpha = 0.35f)),
        ) {
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
                        )
                    }
                }
            }
        }
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
// Captured-piece glyph size for the material rows hugging the board edges.
private const val MATERIAL_CELL_UNITS = 1.6f
// Same-type captured pieces fan out like a hand of cards: each overlaps the previous
// by this fraction of a cell, with a background-coloured halo giving a faint gap
// between them (like the pocket count badge). Different types sit a small gap apart.
private const val MATERIAL_OVERLAP_FRACTION = 0.7f
private const val MATERIAL_HALO_SCALE = 1.2f
private const val MATERIAL_GROUP_GAP_UNITS = 0f

/**
 * A material row hugging one board edge (non-Crazyhouse): the pieces one side has
 * captured (drawn in [capturedColor]), same types fanned/overlapped like a hand of
 * cards, followed by that side's "+N" point lead, if any. Left-aligned to the board's
 * edge. Always occupies its height so the board doesn't shift when captures appear.
 */
@Composable
internal fun MaterialRow(captured: List<PieceType>, capturedColor: EngineColor, advantage: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(2f.gridUnitsAsDp())
            // Align to the board's left edge (the board Box uses 0.5f horizontal padding).
            .padding(horizontal = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cell = MATERIAL_CELL_UNITS.gridUnitsAsDp()
        // The list is ordered by type, so distinct() gives the groups in display order.
        captured.distinct().forEachIndexed { groupIndex, type ->
            if (groupIndex > 0) Spacer(Modifier.width(MATERIAL_GROUP_GAP_UNITS.gridUnitsAsDp()))
            CapturedStack(type = type, color = capturedColor, count = captured.count { it == type }, cell = cell)
        }
        if (advantage > 0) {
            LightText(
                text = "+$advantage",
                variant = LightTextVariant.Detail,
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
    Row(horizontalArrangement = Arrangement.spacedBy(-(cell * MATERIAL_OVERLAP_FRACTION))) {
        repeat(count) {
            Box(modifier = Modifier.size(cell), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(pieceDrawable(piece)),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(LightThemeTokens.colors.background),
                    modifier = Modifier.size(cell * PIECE_SCALE * MATERIAL_HALO_SCALE),
                )
                PieceGlyph(piece = piece, squareSize = cell)
            }
        }
    }
}

/**
 * Crazyhouse: opponent's reserves above the board — read-only. Same glyph size as
 * the player's bar, but hugged tight vertically (minimal top/bottom space).
 */
@Composable
private fun OpponentPocketBar(pocket: Map<PieceType, Int>, color: EngineColor) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height((OPP_POCKET_CELL_UNITS + 0.4f).gridUnitsAsDp())
            .padding(horizontal = 1f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        POCKET_ORDER.filter { (pocket[it] ?: 0) > 0 }.forEach { type ->
            PocketPiece(
                type = type,
                color = color,
                count = pocket[type] ?: 0,
                cell = OPP_POCKET_CELL_UNITS.gridUnitsAsDp(),
                selected = false,
                onTap = null,
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
    pocket: Map<PieceType, Int>,
    color: EngineColor,
    selected: PieceType?,
    onTap: (PieceType) -> Unit,
    canStepBack: Boolean,
    canStepForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1f.gridUnitsAsDp())
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 2f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavArrow(LightIcons.BACK, "Previous move", canStepBack, onBack)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            POCKET_ORDER.filter { (pocket[it] ?: 0) > 0 }.forEach { type ->
                PocketPiece(
                    type = type,
                    color = color,
                    count = pocket[type] ?: 0,
                    cell = MY_POCKET_CELL_UNITS.gridUnitsAsDp(),
                    selected = type == selected,
                    onTap = { onTap(type) },
                )
            }
        }
        NavArrow(LightIcons.ARROW_RIGHT, "Next move", canStepForward, onForward)
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
) {
    Box(
        modifier = Modifier
            .padding(horizontal = 0.3f.gridUnitsAsDp())
            .then(if (selected) Modifier.background(SELECTED_FILL) else Modifier)
            .then(if (onTap != null) Modifier.lightClickable { onTap() } else Modifier)
            .padding(2.dp),
    ) {
        Box(modifier = Modifier.size(cell), contentAlignment = Alignment.Center) {
            PieceGlyph(piece = Piece(color, type), squareSize = cell)
            if (count > 1) {
                CountBadge(count = count, diameter = cell * 0.50f, modifier = Modifier.align(Alignment.BottomEnd))
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
) {
    val background = if (Square.isLight(square)) LIGHT_SQUARE else DARK_SQUARE
    val highlight = when {
        square == state.selectedSquare -> SELECTED_FILL
        square == state.lastMoveFrom || square == state.lastMoveTo -> LAST_MOVE_FILL
        else -> null
    }
    val piece = state.board.getOrNull(square)

    Box(
        modifier = Modifier
            .size(squareSize)
            .background(background)
            .lightClickable { onSquareTap(square) },
        contentAlignment = Alignment.Center,
    ) {
        highlight?.let { Box(Modifier.matchParentSize().background(it)) }
        if (square == state.checkedKingSquare) {
            Box(Modifier.matchParentSize().background(CHECK_FILL))
        }
        piece?.let { PieceGlyph(piece = it, squareSize = squareSize) }
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

@Composable
private fun LegalMarker(isCapture: Boolean, squareSize: Dp) {
    if (isCapture) {
        Box(modifier = Modifier.size(squareSize * 0.92f).border(squareSize * 0.06f, LEGAL_MARKER, CircleShape))
    } else {
        Box(modifier = Modifier.size(squareSize * 0.30f).background(LEGAL_MARKER, CircleShape))
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

// Board outline width; kept tiny and theme-tinted so the board reads as a unit on
// either background without drawing attention.
@Composable
private fun boardBorderWidth(): Dp = 0.08f.gridUnitsAsDp()
