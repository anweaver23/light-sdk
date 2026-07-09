package com.andyweaver.chess.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
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
 * Settings screen for the chess tool. Lists the four persisted toggles from
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

        LightTheme(colors = themeColors) {
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
                    SettingsToggleRow(
                        label = "Notifications",
                        enabled = snapshot.notificationsEnabled,
                        onClick = { viewModel.toggleNotifications() },
                    )
                    SettingsToggleRow(
                        label = "Confirm moves",
                        enabled = snapshot.confirmMoves,
                        onClick = { viewModel.toggleConfirmMoves() },
                    )
                    SettingsToggleRow(
                        label = "Show time remaining",
                        enabled = snapshot.showTimeRemaining,
                        onClick = { viewModel.toggleShowTimeRemaining() },
                    )
                    SettingsToggleRow(
                        label = "Show last move",
                        enabled = snapshot.showLastMove,
                        onClick = { viewModel.toggleShowLastMove() },
                    )
                }
            }
        }
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
        // Light's TOGGLE_ON/OFF assets are swapped: TOGGLE_OFF is the filled knob on
        // the RIGHT (what reads as "on") and TOGGLE_ON is the empty knob on the LEFT
        // ("off"). So we deliberately use TOGGLE_OFF for enabled and TOGGLE_ON for
        // disabled to get the conventional on=filled-right / off=empty-left look.
        LightIcon(
            icon = if (enabled) LightIcons.TOGGLE_OFF else LightIcons.TOGGLE_ON,
            contentDescription = if (enabled) "$label: on" else "$label: off",
        )
    }
}
