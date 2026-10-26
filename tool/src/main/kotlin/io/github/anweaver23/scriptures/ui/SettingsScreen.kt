package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.core.TextSize
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.launch

/** A verse to preview the text size with. */
private const val SAMPLE = "1  In the beginning God created the heaven and the earth."

/** Settings, opened from home: text size (pick one, see a verse drawn at that size) and Add audio. */
class SettingsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val current by AppGraph.textSize.collectAsState()
        val pending by AppGraph.importer.pending.collectAsState()

        ScreenFrame(title = "Settings", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                LightText(
                    text = "Text size",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                TextSize.entries.forEach { size ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ROW_UNITS.gridUnitsAsDp())
                            .lightClickable { AppGraph.scope.launch { AppGraph.settings.setTextSize(size) } },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        LightText(text = size.label, variant = LightTextVariant.Copy, lighten = size != current)
                        if (size == current) LightIcon(LightIcons.ACCEPT, size = 1.5f, contentDescription = "Selected")
                    }
                }
                BasicText(
                    text = SAMPLE,
                    style = readerTextStyle(current.scale),
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
                )
                LightText(text = "Audio", variant = LightTextVariant.Fine, lighten = true)
                ListRow("Add audio", if (pending.isEmpty()) null else "${pending.size} to review") {
                    navigateTo(::AddAudioScreen)
                }
            }
        }
    }
}
