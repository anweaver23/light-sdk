package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import io.github.anweaver23.scriptures.core.TextSize
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.launch

/**
 * Settings, opened from home, laid out like LightOS Settings: one row per setting, its current
 * value dimmed on the right. Text size opens a picker; Add audio opens the audio import.
 */
class SettingsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val textSize by AppGraph.textSize.collectAsState()
        val pending by AppGraph.importer.pending.collectAsState()

        ScreenFrame(title = "Settings", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                ListRow("Text size", textSize.label) { navigateTo(::TextSizeScreen) }
                ListRow("Add audio", if (pending.isEmpty()) null else "${pending.size} to review") {
                    navigateTo(::AddAudioScreen)
                }
            }
        }
    }
}

/** Picks the text size. The current one is underlined; choosing one saves it and goes back. */
class TextSizeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val current by AppGraph.textSize.collectAsState()

        ScreenFrame(title = "Text size", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                TextSize.entries.forEach { size ->
                    SelectableRow(size.label, selected = size == current) {
                        AppGraph.scope.launch { AppGraph.settings.setTextSize(size) }
                        goBack()
                    }
                }
            }
        }
    }
}
