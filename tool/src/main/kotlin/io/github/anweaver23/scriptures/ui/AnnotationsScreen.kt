package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.launch

/** Every highlight and note, newest first. Tapping one opens the reader there. */
class AnnotationsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val catalog = AppGraph.catalog
        val annotations by AppGraph.study.annotations.collectAsState()
        var editing by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { AppGraph.study.load() }
        val sorted = annotations.sortedByDescending { it.createdAt }

        ScreenFrame(
            title = "Annotations",
            onBack = { goBack() },
            rightButton = if (sorted.isEmpty()) {
                null
            } else {
                LightBarButton.Text(if (editing) "DONE" else "EDIT", onClick = { editing = !editing })
            },
        ) {
            if (sorted.isEmpty()) {
                Note("Press and hold a word in the reader to highlight it or add a note.")
                return@ScreenFrame
            }
            LightScrollView(Modifier.fillMaxSize()) {
                sorted.forEach { a ->
                    StackRow(
                        title = catalog.label(a),
                        lines = listOfNotNull("“${a.quote}”" to 2, a.note?.let { it to 2 }),
                        trailing = if (editing) {
                            {
                                LightIcon(
                                    LightIcons.CLOSE,
                                    contentDescription = "Remove",
                                    modifier = Modifier.lightClickable {
                                        navigateTo({ confirmRemove(it, a) }) { confirmed ->
                                            if (confirmed) AppGraph.scope.launch { AppGraph.study.deleteAnnotation(a.id) }
                                        }
                                    },
                                )
                            }
                        } else {
                            null
                        },
                    ) {
                        navigateTo({ ReaderScreen(it, a.chapter, focusVerse = a.start.ref.verse) })
                    }
                }
            }
        }
    }
}
