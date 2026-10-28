package io.github.anweaver23.scriptures.ui

import io.github.anweaver23.scriptures.core.HomeLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.core.LineSpacing
import io.github.anweaver23.scriptures.core.TextSize
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.launch

/** Two verses, so a preview shows both the size and the space between lines. */
private const val SAMPLE = "1  In the beginning God created the heaven and the earth.\n" +
    "2  And the earth was without form, and void; and darkness was upon the face of the deep."

/** Height of the line spacing slider's touch area, in grid units. */
private const val SLIDER_UNITS = 3f

/** Slider track thickness and knob diameter, in design px (the toggle glyph's proportions). */
private const val SLIDER_TRACK_PX = 3f
private const val SLIDER_KNOB_PX = 22f

/**
 * Settings, opened from home, laid out like LightOS Settings: one row per item, its current value
 * dimmed on the right.
 */
class SettingsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val pending by AppGraph.importer.pending.collectAsState()
        val playback by AppGraph.playback.collectAsState()
        val homeLayout by AppGraph.homeLayout.collectAsState()

        ScreenFrame(title = "Settings", onBack = { goBack() }, rightButton = nowPlayingButton()) {
            LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
                ListRow("Display") { navigateTo(::DisplayScreen) }
                // When a chapter or hymn ends, go on to the next one.
                ToggleRow("Autoplay chapters", playback.autoplayChapters) {
                    AppGraph.scope.launch { AppGraph.settings.setAutoplayChapters(!playback.autoplayChapters) }
                }
                ToggleRow("Autoplay hymns", playback.autoplayHymns) {
                    AppGraph.scope.launch { AppGraph.settings.setAutoplayHymns(!playback.autoplayHymns) }
                }
                // Two choices, so a tap flips between them (no picker screen).
                ListRow("Home", homeLayout.label) {
                    val next = if (homeLayout == HomeLayout.Tiles) HomeLayout.List else HomeLayout.Tiles
                    AppGraph.scope.launch { AppGraph.settings.setHomeLayout(next) }
                }
                ListRow("Add audio", if (pending.isEmpty()) null else "${pending.size} to review") {
                    navigateTo(::AddAudioScreen)
                }
            }
        }
    }
}

/**
 * Display: text size, line spacing and invert colours. Reached from Settings. Size and spacing apply to the app's content everywhere (not the bars or menus).
 */
class DisplayScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val display by AppGraph.display.collectAsState()

        ScreenFrame(title = "Display", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
                ListRow("Text size", display.textSize.label) { navigateTo(::TextSizeScreen) }
                ListRow("Line spacing", LineSpacing.label(display.lineSpacing)) { navigateTo(::LineSpacingScreen) }
                ToggleRow("Invert colors", display.invertColors) {
                    AppGraph.scope.launch { AppGraph.settings.setInvertColors(!display.invertColors) }
                }
            }
        }
    }
}

/** Picks the text size: the current one is underlined, with a preview underneath. */
class TextSizeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val display by AppGraph.display.collectAsState()

        ScreenFrame(title = "Text size", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                TextSize.entries.forEach { size ->
                    SelectableRow(size.label, selected = size == display.textSize) {
                        AppGraph.scope.launch { AppGraph.settings.setTextSize(size) }
                    }
                }
                Preview(display.textSize.scale, display.lineSpacing)
            }
        }
    }
}

/** Line spacing on a slider that snaps to [LineSpacing.STEPS] (no visible notches), with a preview. */
class LineSpacingScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val display by AppGraph.display.collectAsState()
        val steps = LineSpacing.STEPS
        // Local so the preview follows the finger at once; each step is saved as it's reached.
        var index by remember { mutableIntStateOf(steps.indexOf(LineSpacing.snap(display.lineSpacing))) }

        ScreenFrame(title = "Line spacing", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(ROW_UNITS.gridUnitsAsDp())
                        .padding(start = LIST_INSET_UNITS.gridUnitsAsDp()),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    AppText("Spacing", ROW_TEXT)
                    AppText(LineSpacing.label(steps[index]), ROW_TEXT, lighten = true)
                }
                StepSlider(count = steps.size, index = index) { i ->
                    index = i
                    AppGraph.scope.launch { AppGraph.settings.setLineSpacing(steps[i]) }
                }
                Preview(display.textSize.scale, steps[index])
            }
        }
    }
}

/** A row with Light's on/off toggle glyph on the right; tapping anywhere flips it. */
@Composable
private fun ToggleRow(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(start = LIST_INSET_UNITS.gridUnitsAsDp()),
        verticalAlignment = Alignment.Top,
    ) {
        // Text at the top of the row like every list row; the toggle centred on the text line.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            AppText(text = label, variant = ROW_TEXT, modifier = Modifier.weight(1f))
            LightIcon(
                icon = if (on) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                contentDescription = if (on) "$label: on" else "$label: off",
            )
        }
    }
}

/**
 * Sample verses drawn as the reader would draw them at [scale] and [spacing], in inverted colours when
 * that's on, as the reader would show them.
 */
@Composable
private fun Preview(scale: Float, spacing: Float) {
    val display by AppGraph.display.collectAsState()
    Note("Preview")
    AppTheme(reading = true) {
        BasicText(
            text = SAMPLE,
            style = readerTextStyle(scale, spacing),
            modifier = Modifier
                .fillMaxWidth()
                .background(if (display.invertColors) LightThemeTokens.colors.background else Color.Transparent)
                .padding(LIST_INSET_UNITS.gridUnitsAsDp()),
        )
    }
}

/**
 * A plain horizontal slider over [count] evenly spaced steps: a thin track and a round knob. Dragging
 * or tapping snaps to the nearest step and ticks a haptic as each step is reached. The steps aren't
 * drawn; they're felt.
 */
@Composable
private fun StepSlider(count: Int, index: Int, onChange: (Int) -> Unit) {
    val color = LightThemeTokens.colors.content
    val haptics = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    val currentIndex by rememberUpdatedState(index)
    val currentOnChange by rememberUpdatedState(onChange)
    val track = SLIDER_TRACK_PX.designVerticalPxToDp()
    val knob = SLIDER_KNOB_PX.designVerticalPxToDp()
    // The knob's centre travels between one radius in from each end, in the drawing and the touch maths.
    val inset = with(LocalDensity.current) { knob.toPx() } / 2

    fun select(x: Float, width: Int) {
        val usable = (width - 2 * inset).coerceAtLeast(1f)
        val i = (((x - inset) / usable) * (count - 1)).let { Math.round(it) }.coerceIn(0, count - 1)
        if (i != currentIndex) {
            if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnChange(i)
        }
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(SLIDER_UNITS.gridUnitsAsDp())
            .pointerInput(count) {
                detectTapGestures { select(it.x, size.width) }
            }
            .pointerInput(count) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    select(change.position.x, size.width)
                }
            },
    ) {
        val r = inset
        val left = r
        val right = size.width - r
        val y = size.height / 2
        drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = track.toPx())
        val x = left + (right - left) * (index.toFloat() / (count - 1).coerceAtLeast(1))
        drawCircle(color, radius = r, center = Offset(x, y))
    }
}
