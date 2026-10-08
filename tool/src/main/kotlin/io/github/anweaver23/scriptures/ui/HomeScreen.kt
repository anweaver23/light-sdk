package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Volume
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel : LightViewModel<Unit>() {
    val catalog: Catalog = AppGraph.catalog
    val lastRead = AppGraph.progress.lastRead.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val lastPlayed = AppGraph.progress.lastPlayed.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pending = AppGraph.importer.pending

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Pick up anything uploaded through the Tool Manager while the tool was closed.
        viewModelScope.launch { AppGraph.importer.importAll() }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    override val viewModelClass = HomeViewModel::class.java
    override fun createViewModel() = HomeViewModel()

    @Composable
    override fun Content() {
        val lastRead by viewModel.lastRead.collectAsState()
        val lastPlayed by viewModel.lastPlayed.collectAsState()
        val pending by viewModel.pending.collectAsState()
        val catalog = viewModel.catalog

        ScreenFrame(title = "Scriptures", onBack = null) {
            LightScrollView(Modifier.fillMaxSize()) {
                lastRead?.let { target ->
                    ListRow("Continue reading", catalog.label(target)) {
                        navigateTo({ ReaderScreen(it, target) })
                    }
                }
                lastPlayed?.let { target ->
                    ListRow("Resume listening", catalog.label(target)) {
                        navigateTo({ PlayerScreen(it, request = null) })
                    }
                }
                catalog.scriptures.volumes.forEach { volume ->
                    ListRow(volume.title) { openVolume(volume) }
                }
                ListRow("Hymns") { navigateTo(::HymnBooksScreen) }
                ListRow("Add audio", if (pending.isEmpty()) null else "${pending.size} to review") {
                    navigateTo(::AddAudioScreen)
                }
            }
        }
    }

    private fun openVolume(volume: Volume) {
        val onlyBook = volume.books.singleOrNull()
        if (onlyBook != null) {
            navigateTo({ ChapterGridScreen(it, volume.id, onlyBook.id) })
        } else {
            navigateTo({ BookListScreen(it, volume.id) })
        }
    }
}
