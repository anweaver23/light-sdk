package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import io.github.anweaver23.scriptures.core.ImportDecision
import io.github.anweaver23.scriptures.core.UploadKind
import io.github.anweaver23.scriptures.data.AppGraph
import io.github.anweaver23.scriptures.data.Importer
import kotlinx.coroutines.launch

/** How to bulk upload, what's in the library, and anything the importer couldn't place. */
class AddAudioScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val importer = AppGraph.importer
        val pending by importer.pending.collectAsState()
        val report by importer.lastReport.collectAsState()
        val index by AppGraph.library.index.collectAsState()
        var sorting by remember { mutableStateOf(false) }

        ScreenFrame(title = "Add audio", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                Note(
                    "Audio isn't included, so bring your own. On a computer on the same Wi-Fi, open the " +
                        "Tool Manager address shown on your phone, choose Scriptures, and drop files into " +
                        "${UploadKind.entries.joinToString(", ") { it.label }}. Whole folders and .zip files " +
                        "work. Files named like the Church's downloads (\"alma-32\", \"section-04\", hymn " +
                        "titles) are sorted for you.",
                )
                Note(
                    "In your library: ${index.audioCount("scriptures/")} chapters and " +
                        "${index.audioCount("hymns/")} hymns with audio, ${index.lyrics.size} hymns with words.",
                )
                report?.let { Note("Last sort added ${it.imported} file${if (it.imported == 1) "" else "s"}.") }

                ListRow(if (sorting) "Sorting…" else "Sort new files now") {
                    if (!sorting) {
                        sorting = true
                        AppGraph.scope.launch {
                            try {
                                importer.importAll()
                            } finally {
                                sorting = false
                            }
                        }
                    }
                }

                if (pending.isNotEmpty()) {
                    LightText(
                        text = "Needs review (${pending.size})",
                        variant = LightTextVariant.Subheading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 1f.gridUnitsAsDp()),
                    )
                    val duplicates = pending.filter { it.reason == ImportDecision.Reason.Duplicate }
                    if (duplicates.isNotEmpty()) {
                        ListRow("Delete ${duplicates.size} duplicate${if (duplicates.size == 1) "" else "s"}") {
                            AppGraph.scope.launch { importer.delete(duplicates.map { it.file }) }
                        }
                    }
                    pending.forEach { item ->
                        ListRow(item.file.relativePath.substringAfterLast('/'), item.file.kind.label) {
                            navigateTo({ ReviewFileScreen(it, item) })
                        }
                    }
                }
            }
        }
    }
}

/** One file the importer couldn't place: assign it by hand or delete it. */
class ReviewFileScreen(sealedActivity: SealedLightActivity, private val item: Importer.ReviewItem) :
    SimpleLightScreen<Unit>(sealedActivity) {

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val importer = AppGraph.importer
        val file = item.file
        val isAudio = file.kind != UploadKind.HymnLyrics

        ScreenFrame(title = "Review file", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                LightText(file.relativePath, LightTextVariant.Copy, modifier = Modifier.fillMaxWidth())
                Note("${file.kind.label}. ${item.reason.message}.")
                if (item.reason != ImportDecision.Reason.WrongType) {
                    if (isAudio) {
                        ListRow("This is a chapter…") { pick(TargetPickerScreen.Mode.Chapter) }
                    }
                    ListRow(if (isAudio) "This is a hymn…" else "These are the words to…") {
                        pick(TargetPickerScreen.Mode.Hymn)
                    }
                }
                ListRow("Delete file") {
                    AppGraph.scope.launch { importer.delete(listOf(file)) }
                    goBack()
                }
            }
        }
    }

    private fun pick(mode: TargetPickerScreen.Mode) {
        navigateTo({ TargetPickerScreen(it, mode) }) { target ->
            AppGraph.scope.launch { AppGraph.importer.assign(item.file, target) }
            goBack()
        }
    }
}
