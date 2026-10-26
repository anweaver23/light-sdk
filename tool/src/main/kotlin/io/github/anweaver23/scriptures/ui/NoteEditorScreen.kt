package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.flow.StateFlow

/** Space between the bottom of the cursor's line and the keyboard when the note scrolls, in grid units. */
private const val CURSOR_MARGIN_UNITS = 1f

class NoteEditorViewModel(initial: String) : LightViewModel<String>() {
    /** Lives here so the text survives recomposition; the cursor starts at the end. */
    val text = TextFieldState(initial, TextRange(initial.length))
}

/**
 * Full-screen note writing, like LightOS's own Notes: the note fills the page in reading-size text
 * (no field, no underline), scrolls as it grows, and the LP3 keyboard sits below. Return inserts a
 * new line; SAVE returns the text. Backing out returns nothing.
 */
class NoteEditorScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
) : LightScreen<String, NoteEditorViewModel>(sealedActivity) {

    init {
        AppGraph.init(lightContext)
    }

    override val viewModelClass = NoteEditorViewModel::class.java
    override fun createViewModel() = NoteEditorViewModel(initial)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val textSize by AppGraph.textSize.collectAsState()
        val keyboardOptions = rememberKeyboardOptions()
        val haptics = LocalHapticFeedback.current
        val hapticsOn by rememberUpdatedState(LocalHapticsEnabled.current)
        val state = viewModel.text
        val keyboard = remember {
            keyboardViewModel(
                NoteKeyboardCallback(state) {
                    if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                keyboardOptions,
                initialCaps = initial.isEmpty(),
            )
        }

        LightTheme(colors = colors) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack(null) }),
                    center = LightTopBarCenter.Text(title),
                )
                NoteBody(state, readerTextStyle(textSize.scale), Modifier.weight(1f))
                // The same extra bottom room LightTextInputEditor gives the keyboard.
                LightEmbeddedLp3Keyboard(
                    viewModel = keyboard,
                    additionalBottomHeight = 5f.gridUnitsAsDp(),
                    bottomBar = {
                        LightBottomBar(
                            items = listOf(LightBarButton.Text("SAVE", onClick = { goBack(state.text.toString()) })),
                        )
                    },
                )
            }
        }
    }

    private fun keyboardViewModel(
        callback: Lp3RepeatableKeyboardCallback,
        options: StateFlow<KeyboardOptions>,
        initialCaps: Boolean,
    ): Lp3KeyboardViewModel<*> {
        // Owned by this screen's store, so it's cleared when the editor closes.
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EnQwertyLp3KeyboardViewModel<Unit>(
                    callback,
                    keyboardOptionsFlow = options,
                    optionsForLayout = { LayoutOptions(!it.isRootLayout) },
                ).apply { if (initialCaps) setCapsMode(true) } as T
        }
        return ViewModelProvider(this, factory)[EnQwertyLp3KeyboardViewModel::class.java]
    }
}

/**
 * The note's text filling the page, with a caret. Tapping moves the caret there; dragging scrolls.
 * Keeps the caret's line in view as the note grows.
 */
@Composable
private fun NoteBody(state: TextFieldState, style: TextStyle, modifier: Modifier) {
    val colors = LightThemeTokens.colors
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    val marginPx = with(LocalDensity.current) { CURSOR_MARGIN_UNITS.gridUnitsAsDp().toPx() }
    val text = state.text.toString()
    val caret = state.selection.min.coerceIn(0, text.length)

    // Follow the caret: scroll down when it passes the bottom, up when it goes above the top.
    LaunchedEffect(caret, text, layout) {
        val l = layout ?: return@LaunchedEffect
        val rect = l.getCursorRect(caret.coerceAtMost(l.layoutInput.text.length))
        val top = scroll.value
        val bottom = top + scroll.viewportSize
        when {
            rect.bottom + marginPx > bottom -> scroll.scrollTo((rect.bottom + marginPx - scroll.viewportSize).toInt())
            rect.top < top -> scroll.scrollTo(rect.top.toInt())
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 1f.gridUnitsAsDp())
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    layout?.let { l -> state.edit { selection = TextRange(l.getOffsetForPosition(at)) } }
                }
            },
    ) {
        BasicText(
            text = text,
            style = style,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth(),
        )
        if (text.isEmpty()) {
            BasicText(text = "Write a note…", style = style.copy(color = colors.contentSecondary))
        }
        layout?.let { l ->
            val rect = l.getCursorRect(caret.coerceAtMost(l.layoutInput.text.length))
            Box(
                Modifier
                    .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                    .width(2.dp)
                    .height(with(LocalDensity.current) { rect.height.toDp() })
                    .background(colors.content),
            )
        }
    }
}

/**
 * Types into [state] at the caret. Mirrors the SDK's (internal) TextInputKeyboardCallback for a
 * multi-line field: Return is a new line, long-press backspace deletes a word.
 */
private class NoteKeyboardCallback(
    private val state: TextFieldState,
    private val onHaptic: () -> Unit,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = onHaptic()

    override fun onSpecialKeyPressed(key: SpecialKey) {
        onHaptic()
        if (key == SpecialKey.Space) insert(" ")
    }

    override fun onKeyReleased(code: Int) = insert(buildString { appendCodePoint(code) })

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> deleteBefore(if (before().lastOrNull()?.isLowSurrogate() == true) 2 else 1)
            SpecialKey.Return -> insert("\n")
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key != SpecialKey.Backspace) return
        val before = before()
        val trimmed = before.trimEnd()
        val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
        deleteBefore(before.length - if (lastSpace >= 0) lastSpace + 1 else 0)
    }

    override fun onKeyRepeated(code: Int) = insert(buildString { appendCodePoint(code) })

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insert(" ")
    }

    override fun onSubmitWord(word: CharSequence) = insert(word.toString())

    private fun before(): CharSequence = state.text.subSequence(0, state.selection.min)

    private fun insert(text: String) {
        state.edit {
            val start = selection.min
            replace(start, selection.max, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBefore(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}
