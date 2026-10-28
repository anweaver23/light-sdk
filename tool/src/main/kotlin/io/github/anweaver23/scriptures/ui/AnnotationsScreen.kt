package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightScrollView
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.launch

/**
 * Every highlight and note, newest first, each dated beside its reference. Tapping one opens the
 * reader there. EDIT (bottom bar, as in LightOS Notes) puts a remove mark on each, which asks first.
 */
class AnnotationsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()

    // On the screen, not in remember: the confirm screen takes this one out of composition.
    private var editing by mutableStateOf(false)

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val catalog = AppGraph.catalog
        val annotations by AppGraph.study.annotations.collectAsState()
        LaunchedEffect(Unit) { AppGraph.study.load() }
        val sorted = annotations.sortedByDescending { it.createdAt }
        if (sorted.isEmpty()) editing = false

        ScreenFrame(
            title = "Annotations",
            onBack = { goBack() },
            bottomBar = if (sorted.isEmpty()) {
                null
            } else {
                listOf(null, LightBarButton.Text(if (editing) "DONE" else "EDIT", onClick = { editing = !editing }), null)
            },
        ) {
            if (sorted.isEmpty()) {
                Note("Press and hold a word in the reader to highlight it or add a note.")
                return@ScreenFrame
            }
            LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
                sorted.forEach { a ->
                    val remove = {
                        navigateTo({ confirmRemove(it, a) }) { confirmed ->
                            if (confirmed) AppGraph.scope.launch { AppGraph.study.deleteAnnotation(a.id) }
                        }
                    }
                    StackRow(
                        title = catalog.label(a),
                        titleDetail = formatDate(a.createdAt),
                        lines = listOfNotNull("“${a.quote}”" to 2, a.note?.let { it to 2 }),
                        leading = if (editing) {
                            { RemoveMark(catalog.label(a), remove) }
                        } else {
                            null
                        },
                    ) {
                        if (editing) remove() else navigateTo({ ReaderScreen(it, a.chapter, focusVerse = a.start.ref.verse) })
                    }
                }
            }
        }
    }
}
