package com.andyweaver.chess.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.andyweaver.chess.AboutScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
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

private const val EDGE_PADDING_UNITS = 1f
private const val ROW_VERTICAL_PADDING_UNITS = 1f
private val ROW_LABEL_VARIANT = LightTextVariant.Subheading

/**
 * Settings screen for the chess tool. Lists the persisted toggles from
 * [ChessSettings] as tap-to-flip rows, LP style (mirrors
 * `sdk/emulator`'s `EmulatorSettings.kt`): centered "Settings" title with a
 * back chevron on the top bar, and one row per setting with a trailing
 * TOGGLE_ON/TOGGLE_OFF icon.
 *
 * Navigate to it (e.g. from a gear button) with:
 * ```
 * navigateTo(::SettingsScreen)
 * ```
 */
class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel =
        SettingsViewModel(ChessSettings(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val snapshot by viewModel.snapshot.collectAsState()
        val confirmingLogOut by viewModel.confirmingLogOut.collectAsState()

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Settings"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(modifier = Modifier.fillMaxSize()) {
                    // Notifications toggle removed for v1 — push needs the (unbuilt) relay,
                    // so a toggle here would control nothing. Re-add with the relay.
                    SettingsToggleRow(
                        label = "Confirm moves",
                        enabled = snapshot.confirmMoves,
                        onClick = { viewModel.toggleConfirmMoves() },
                    )
                    SettingsToggleRow(
                        label = "Show legal moves",
                        enabled = snapshot.showLegalMoves,
                        onClick = { viewModel.toggleShowLegalMoves() },
                    )
                    SettingsToggleRow(
                        label = "Drag pieces",
                        enabled = snapshot.dragAndDrop,
                        onClick = { viewModel.toggleDragAndDrop() },
                    )
                    SettingsOptionRow(
                        label = "Move step speed",
                        value = snapshot.moveStepSpeed.label,
                        onClick = { viewModel.cycleMoveStepSpeed() },
                    )
                    SettingsActionRow(
                        label = "About",
                        onClick = { navigateTo(::AboutScreen) },
                    )
                    SettingsActionRow(
                        label = "Log out",
                        onClick = { viewModel.requestLogOut() },
                    )
                }
            }

            if (confirmingLogOut) {
                LogOutConfirmOverlay(
                    onConfirm = { viewModel.confirmLogOut(onComplete = { goBack() }) },
                    onCancel = { viewModel.cancelLogOut() },
                )
            }
            }
        }
    }
}

@Composable
private fun LogOutConfirmOverlay(onConfirm: () -> Unit, onCancel: () -> Unit) {
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
            LightText(text = "Log out?", variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
        LightBottomBar(
            items = listOf(
                null,
                LightBarButton.Text(text = "CONFIRM", onClick = onConfirm),
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = onCancel,
                    contentDescription = "Cancel",
                ),
            ),
        )
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(
                horizontal = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                vertical = ROW_VERTICAL_PADDING_UNITS.gridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = label,
            variant = ROW_LABEL_VARIANT,
            modifier = Modifier.weight(1f),
        )
        // Light's toggle assets (ic_toggle_state_on/off_white) were fixed upstream in the
        // 2026-07 SDK sync — TOGGLE_STATE_ON is now the filled knob on the RIGHT and
        // TOGGLE_STATE_OFF the empty knob on the LEFT, the conventional mapping. Verified
        // on-device: this straight mapping now reads correctly (the old TOGGLE_STATE_OFF-
        // for-enabled workaround would double-invert it post-fix).
        LightIcon(
            icon = if (enabled) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
            contentDescription = if (enabled) "$label: on" else "$label: off",
        )
    }
}

/**
 * A tap-to-cycle settings row: label on the left, the current preset dimmed on the right.
 * Deliberately identical in shape to `NewGameScreen`'s `OptionRow` (same label variant,
 * same `lighten = true` value) so the two screens' option rows read as one system, while
 * keeping this screen's own padding constants.
 */
@Composable
private fun SettingsOptionRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(
                horizontal = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                vertical = ROW_VERTICAL_PADDING_UNITS.gridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = label,
            variant = ROW_LABEL_VARIANT,
            modifier = Modifier.weight(1f),
        )
        LightText(text = value, variant = ROW_LABEL_VARIANT, lighten = true)
    }
}

/** A tap-to-act settings row (no toggle) — used for About and Log out. */
@Composable
private fun SettingsActionRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(
                horizontal = EDGE_PADDING_UNITS.gridUnitsAsDp(),
                vertical = ROW_VERTICAL_PADDING_UNITS.gridUnitsAsDp(),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = label,
            variant = ROW_LABEL_VARIANT,
            modifier = Modifier.weight(1f),
        )
    }
}
