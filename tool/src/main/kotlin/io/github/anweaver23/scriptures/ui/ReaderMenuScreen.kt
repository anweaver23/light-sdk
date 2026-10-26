package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.andyweaver.scriptures.R
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIcon
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

/** Size of a menu row's icon, in grid units. */
private const val MENU_ICON_UNITS = 1.75f

/** What the reader should do after its menu closes. */
enum class ReaderMenuAction { TOGGLE_BOOKMARK, FIND }

/**
 * The reader's menu (top right): bookmark this page and find in chapter. More reading tools go here
 * as they come. Returns the chosen action; BACK returns nothing.
 */
class ReaderMenuScreen(
    sealedActivity: SealedLightActivity,
    private val hasBookmark: Boolean,
) : SimpleLightScreen<ReaderMenuAction>(sealedActivity) {

    @Composable
    override fun Content() {
        ScreenFrame(title = "Menu", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                MenuRow(
                    icon = { Image(tintedIcon(R.drawable.ic_bookmark), null, Modifier.size(MENU_ICON_UNITS.gridUnitsAsDp())) },
                    label = if (hasBookmark) "Remove bookmark" else "Bookmark",
                ) { goBack(ReaderMenuAction.TOGGLE_BOOKMARK) }
                MenuRow(
                    icon = { LightIcon(LightIcons.SEARCH, size = MENU_ICON_UNITS, contentDescription = null) },
                    label = "Find in chapter",
                ) { goBack(ReaderMenuAction.FIND) }
            }
        }
    }
}

/** A menu row: icon, then label. */
@Composable
private fun MenuRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
    ) {
        icon()
        LightText(text = label, variant = LightTextVariant.Copy, maxLines = 1)
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
