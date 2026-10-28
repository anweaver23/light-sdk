package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.andyweaver.scriptures.R
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * The bottom bar of most of the app (home, the browse pages, the reader, a hymn's page):
 * Settings · Search · [play] · Bookmarks · Annotations. There's no "…" menu: Display and Add audio
 * live in Settings, and pages that need more pass their own search and bookmark actions (the reader
 * offers Find in chapter through search, and adding a bookmark through Bookmarks).
 */
@Composable
fun SimpleLightScreen<*>.navBar(
    play: LightBarButton? = null,
    onSearch: () -> Unit = { navigateTo({ SearchScreen(it) }) },
    onBookmarks: () -> Unit = { navigateTo({ BookmarksScreen(it) }) },
): List<LightBarButton> = listOfNotNull(
    LightBarButton.LightIcon(LightIcons.SETTINGS, onClick = { navigateTo(::SettingsScreen) }, contentDescription = "Settings"),
    LightBarButton.LightIcon(LightIcons.SEARCH, onClick = onSearch, contentDescription = "Search"),
    play,
    LightBarButton.Icon(painter = tintedIcon(R.drawable.ic_bookmark), onClick = onBookmarks, contentDescription = "Bookmarks"),
    LightBarButton.Icon(
        painter = tintedIcon(R.drawable.ic_notebook),
        onClick = { navigateTo({ AnnotationsScreen(it) }) },
        contentDescription = "Annotations",
    ),
)

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
