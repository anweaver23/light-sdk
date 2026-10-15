package io.github.anweaver23.scriptures.ui

import androidx.compose.runtime.Composable
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import io.github.anweaver23.scriptures.data.AppGraph

/** Placeholder, opened from the home screen's bottom bar. */
class BookmarksScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        ScreenFrame(title = "Bookmarks", onBack = { goBack() }) {
            Note("Coming soon.")
        }
    }
}
