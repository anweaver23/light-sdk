package io.github.anweaver23.scriptures.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.data.AppGraph

/** Size of a menu row's icon, in grid units. */
private const val MENU_ICON_UNITS = 1.75f

/** A menu row's icon: a Light glyph or one of our own drawables (tinted to the theme). */
sealed interface MenuIcon {
    data class Light(val icon: LightIconConfiguration) : MenuIcon
    data class Res(@DrawableRes val id: Int) : MenuIcon
}

/**
 * One row of a page's "…" menu. [onSelect] runs on the page that opened the menu, after the menu has
 * closed, so it can navigate from there.
 */
class MenuItem(
    val label: String,
    val icon: MenuIcon? = null,
    val detail: String? = null,
    val onSelect: () -> Unit,
)

/**
 * The "…" button for a page's top bar. Opens [MenuScreen] with [items], then Add audio, which every
 * page's menu ends with.
 */
@Composable
fun SimpleLightScreen<*>.pageMenu(items: List<MenuItem> = emptyList()): LightBarButton {
    val pending by AppGraph.importer.pending.collectAsState()
    val all = items + MenuItem(
        label = "Add audio",
        icon = MenuIcon.Light(LightIcons.ADD),
        detail = if (pending.isEmpty()) null else "${pending.size} to review",
    ) { navigateTo(::AddAudioScreen) }
    return LightBarButton.LightIcon(
        LightIcons.ELLIPSES,
        onClick = { navigateTo({ MenuScreen(it, all) }) { index -> all.getOrNull(index)?.onSelect?.invoke() } },
        contentDescription = "Menu",
    )
}

/** A page's menu. Returns the index of the chosen item; BACK returns nothing. */
class MenuScreen(
    sealedActivity: SealedLightActivity,
    private val items: List<MenuItem>,
) : SimpleLightScreen<Int>(sealedActivity) {

    @Composable
    override fun Content() {
        ScreenFrame(title = "Menu", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, item -> MenuRow(item) { goBack(i) } }
            }
        }
    }
}

/** A menu row: icon, label, then a dimmed detail on the right. */
@Composable
private fun MenuRow(item: MenuItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
    ) {
        when (val icon = item.icon) {
            is MenuIcon.Light -> LightIcon(icon.icon, size = MENU_ICON_UNITS, contentDescription = null)
            is MenuIcon.Res -> Image(tintedIcon(icon.id), null, Modifier.size(MENU_ICON_UNITS.gridUnitsAsDp()))
            null -> Unit
        }
        LightText(text = item.label, variant = LightTextVariant.Copy, maxLines = 1, modifier = Modifier.weight(1f))
        if (item.detail != null) {
            LightText(
                text = item.detail,
                variant = LightTextVariant.Fine,
                lighten = true,
                maxLines = 1,
                modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
            )
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
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
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
