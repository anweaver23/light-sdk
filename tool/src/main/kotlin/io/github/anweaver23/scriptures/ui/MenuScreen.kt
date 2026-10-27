package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens
import io.github.anweaver23.scriptures.data.AppGraph

/** A screen a menu item opens, built like [SimpleLightScreen.navigateTo]'s factory. */
typealias ScreenFactory = (SealedLightActivity) -> SimpleLightScreen<Unit>

/**
 * One row of a page's "…" menu. Either it [opens] a screen, which goes on top of the menu so BACK
 * returns to the menu; or it's an action, [onSelect], which runs on the page that opened the menu
 * after the menu closes (Bookmark, Find, Edit act on that page).
 */
class MenuItem(
    val label: String,
    val detail: String? = null,
    val opens: ScreenFactory? = null,
    val onSelect: () -> Unit = {},
)

/**
 * The "…" button for a page's top bar. Opens [MenuScreen] with [items], then Display and Add audio,
 * which every page's menu ends with.
 */
@Composable
fun SimpleLightScreen<*>.pageMenu(items: List<MenuItem> = emptyList()): LightBarButton {
    val pending by AppGraph.importer.pending.collectAsState()
    val all = items + MenuItem("Display", opens = ::DisplayScreen) + MenuItem(
        label = "Add audio",
        detail = if (pending.isEmpty()) null else "${pending.size} to review",
        opens = ::AddAudioScreen,
    )
    return LightBarButton.LightIcon(
        LightIcons.ELLIPSES,
        onClick = { navigateTo({ MenuScreen(it, all) }) { index -> all.getOrNull(index)?.onSelect?.invoke() } },
        contentDescription = "Menu",
    )
}

/**
 * A page's menu: plain text rows, as LightOS menus are. An item that opens a screen opens it from
 * here (BACK comes back to the menu); an action returns its index to the page. BACK returns nothing.
 */
class MenuScreen(
    sealedActivity: SealedLightActivity,
    private val items: List<MenuItem>,
) : SimpleLightScreen<Int>(sealedActivity) {

    @Composable
    override fun Content() {
        ScreenFrame(title = "Menu", onBack = { goBack() }, scaleContent = false) {
            LightScrollView(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, item ->
                    ListRow(item.label, item.detail) {
                        val opens = item.opens
                        if (opens != null) navigateTo(opens) else goBack(i)
                    }
                }
            }
        }
    }
}

/** One-line entry for find in chapter. Returns the query on FIND; backing out returns nothing. */
class FindInputScreen(
    sealedActivity: SealedLightActivity,
    private val initial: String,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        val state = rememberTextFieldState(initial)
        AppTheme {
            LightTextInputEditor(
                title = "Find in chapter",
                state = state,
                keyboardOptionsFlow = keyboardOptions,
                onSubmit = { if (it.isNotBlank()) goBack(it.toString()) },
                onBack = { goBack(null) },
                submitLabel = "FIND",
                singleLine = true,
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
