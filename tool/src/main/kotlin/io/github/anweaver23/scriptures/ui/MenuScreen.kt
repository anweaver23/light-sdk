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
import com.andyweaver.scriptures.R
import io.github.anweaver23.scriptures.data.AppGraph

/** A screen a menu item opens, built like [SimpleLightScreen.navigateTo]'s factory. */
typealias ScreenFactory = (SealedLightActivity) -> SimpleLightScreen<*>

/**
 * One row of a page's "…" menu. Either it [opens] a screen, which goes on top of the menu so BACK
 * returns to the menu; or it's an action, [onSelect], which runs on the page that opened the menu
 * after the menu closes (Bookmark, Edit act on that page). With [onResult], a screen it opened that
 * returns a result (Find's query) closes the menu too, and [onResult] gets it on the page.
 */
class MenuItem(
    val label: String,
    val detail: String? = null,
    val opens: ScreenFactory? = null,
    val onResult: ((Any) -> Unit)? = null,
    val onSelect: () -> Unit = {},
) {
    /** A result from the screen this item opened, carried through the menu's close to the page. */
    internal var result: Any? = null
}

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
        onClick = {
            all.forEach { it.result = null }
            navigateTo({ MenuScreen(it, all) }) { index ->
                val item = all.getOrNull(index) ?: return@navigateTo
                val result = item.result
                if (result != null) item.onResult?.invoke(result) else item.onSelect()
            }
        },
        contentDescription = "Menu",
    )
}

/**
 * The bottom bar of home and the browse pages (volumes, books, chapters, hymnbooks, hymns):
 * Settings · Search · Bookmarks · Annotations, so they're a tap away anywhere you browse.
 */
@Composable
fun SimpleLightScreen<*>.navBar(): List<LightBarButton> = listOf(
    LightBarButton.LightIcon(LightIcons.SETTINGS, onClick = { navigateTo(::SettingsScreen) }, contentDescription = "Settings"),
    LightBarButton.LightIcon(LightIcons.SEARCH, onClick = { navigateTo(::SearchScreen) }, contentDescription = "Search"),
    LightBarButton.Icon(
        painter = tintedIcon(R.drawable.ic_bookmark),
        onClick = { navigateTo({ BookmarksScreen(it) }) },
        contentDescription = "Bookmarks",
    ),
    LightBarButton.Icon(
        painter = tintedIcon(R.drawable.ic_notebook),
        onClick = { navigateTo({ AnnotationsScreen(it) }) },
        contentDescription = "Annotations",
    ),
)

/**
 * A page's menu: plain text rows, as LightOS menus are. An item that opens a screen opens it from
 * here (BACK comes back to the menu); an action returns its index to the page. BACK returns nothing.
 */
class MenuScreen(
    sealedActivity: SealedLightActivity,
    private val items: List<MenuItem>,
) : SimpleLightScreen<Int>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()


    @Composable
    override fun Content() {
        ScreenFrame(title = "Menu", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
                items.forEachIndexed { i, item ->
                    ListRow(item.label, item.detail) {
                        // Screens return any type; viewed as Any here, since only onResult reads it.
                        @Suppress("UNCHECKED_CAST")
                        val opens = item.opens as ((SealedLightActivity) -> SimpleLightScreen<Any>)?
                        when {
                            opens == null -> goBack(i)
                            item.onResult == null -> navigateTo(opens)
                            else -> navigateTo(opens) { result ->
                                item.result = result
                                goBack(i)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One-line entry for find in chapter. Returns the query on FIND. Backing out returns nothing, or, with
 * [blankOnBack], an empty string, so the reader knows to end a search that was being edited.
 */
class FindInputScreen(
    sealedActivity: SealedLightActivity,
    private val initial: String,
    private val blankOnBack: Boolean = false,
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
                onBack = { goBack(if (blankOnBack) "" else null) },
                submitLabel = "FIND",
                singleLine = true,
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
