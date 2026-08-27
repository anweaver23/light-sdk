package com.andyweaver.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.andyweaver.chess.ui.ChessTheme
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
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

private const val EDGE_UNITS = 1f

class AboutViewModel : LightViewModel<Unit>()

/**
 * Credits / attribution shown to end users (the repo's ATTRIBUTION.md doesn't reach
 * someone who only installed the APK). Links are shown as plain text — LightOS blocks
 * launching a browser, so they aren't tappable.
 */
class AboutScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, AboutViewModel>(sealedActivity) {

    override val viewModelClass: Class<AboutViewModel>
        get() = AboutViewModel::class.java

    override fun createViewModel(): AboutViewModel = AboutViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        ChessTheme(colors = themeColors) {
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
                    center = LightTopBarCenter.Text("About"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(horizontal = EDGE_UNITS.gridUnitsAsDp()),
                        verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
                    ) {
                        Credit(
                            heading = "Chess",
                            body = "Correspondence chess for the Light Phone.",
                        )
                        Credit(
                            heading = "Powered by Lichess",
                            body = "Games and play are provided by lichess.org, a free/open " +
                                "chess service. Lichess software is licensed GNU AGPL v3+.",
                        )
                        Credit(
                            heading = "Piece artwork",
                            body = "The \"pixel\" piece set by therealqtpi, from Lichess (lila), " +
                                "licensed GNU AGPL v3 or later.",
                        )
                        Credit(
                            heading = "Open source",
                            body = "Built with Kotlin, Ktor, and Jetpack Compose (Apache 2.0). " +
                                "Full credits and license texts are in ATTRIBUTION.md in the " +
                                "source repository.",
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Credit(heading: String, body: String) {
        Column(verticalArrangement = Arrangement.spacedBy(0.25f.gridUnitsAsDp())) {
            LightText(text = heading, variant = LightTextVariant.Subheading)
            LightText(text = body, variant = LightTextVariant.Detail)
        }
    }
}
