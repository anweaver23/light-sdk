package com.andyweaver.chess.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
// v1: PGN export disabled — may re-add
// import android.content.ClipData
// import androidx.compose.ui.platform.ClipEntry
// import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andyweaver.chess.engine.Color as EngineColor
import com.andyweaver.chess.engine.Piece
import com.andyweaver.chess.engine.PieceType
import com.andyweaver.chess.engine.Square
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

// Piece fill/outline. White piece = white fill + black outline; black piece =
// black fill + white outline — visible on any square color.
private val WHITE_PIECE_FILL = Color.White
private val WHITE_PIECE_OUTLINE = Color.Black
private val BLACK_PIECE_FILL = Color.Black
private val BLACK_PIECE_OUTLINE = Color.White

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

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 0.5f.gridUnitsAsDp()),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChessBoard(state = state, onSquareTap = viewModel::onSquareTap)
                    }

                    BottomControls(state = state, viewModel = viewModel)
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
            BrowseBar(
                canStepBack = state.canStepBack,
                canStepForward = state.canStepForward,
                onBack = { viewModel.stepBack() },
                onForward = { viewModel.stepForward() },
                onSkipStart = { viewModel.stepToStart() },
                onSkipEnd = { viewModel.stepToEnd() },
            )
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
                .height(2f.gridUnitsAsDp())
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
        if (square in state.legalDestinations) {
            LegalMarker(isCapture = piece != null, squareSize = squareSize)
        }
    }
}

@Composable
private fun PieceGlyph(piece: Piece, squareSize: Dp) {
    val fill = if (piece.color == EngineColor.WHITE) WHITE_PIECE_FILL else BLACK_PIECE_FILL
    val outline = if (piece.color == EngineColor.WHITE) WHITE_PIECE_OUTLINE else BLACK_PIECE_OUTLINE

    if (piece.type == PieceType.PAWN) {
        // Pawn = filled dot with a thin contrasting rim.
        Box(
            modifier = Modifier
                .size(squareSize * 0.28f)
                .background(fill, CircleShape)
                .border(squareSize * 0.03f, outline, CircleShape),
        )
        return
    }

    val letter = piece.type.sanLetter // K / Q / R / B / N
    val density = LocalDensity.current
    val fontSize = with(density) { (squareSize * 0.6f).toSp() }
    val strokeWidthPx = with(density) { squareSize.toPx() } * 0.045f
    val base = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )

    // Fill first, stroke on top so the outline rims the glyph edge.
    Box(contentAlignment = Alignment.Center) {
        Text(text = letter, style = base.copy(drawStyle = Fill), color = fill, maxLines = 1)
        Text(text = letter, style = base.copy(drawStyle = Stroke(width = strokeWidthPx)), color = outline, maxLines = 1)
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
