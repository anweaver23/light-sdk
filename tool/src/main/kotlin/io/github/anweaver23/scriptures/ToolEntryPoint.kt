package io.github.anweaver23.scriptures

import android.util.Log
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec
import io.github.anweaver23.scriptures.core.UploadKind
import io.github.anweaver23.scriptures.data.AppGraph
import io.github.anweaver23.scriptures.data.TextLibrary
import kotlinx.coroutines.CancellationException

@EntryPoint
object ToolEntryPoint : LightEntryPoint {

    private val headers = mapOf(
        UploadKind.ScriptureAudio to "Drop chapter recordings here. Files named like the Church's downloads " +
            "(\"2015-11-1270-alma-32-...mp3\") or simply \"Alma 32.mp3\" are sorted automatically. " +
            "Folders and .zip files are fine.",
        UploadKind.HymnAudio to "Drop hymn recordings here. Files named by hymn title or number " +
            "(\"the_morning_breaks_accompaniment_eng.mp3\", \"Hymn 85.mp3\") are sorted automatically.",
        UploadKind.HymnLyrics to "Drop plain text files with a hymn's words, named by number " +
            "(\"85.txt\", \"1003.txt\") or title.",
    )

    private const val STANDARD_WORKS_HEADER =
        "Drop the Church's downloads here to load the scriptures: \"Standard Works — Victor Reader\" (the .zip) " +
            "for the text, introductions, Topical Guide, Bible Dictionary, Guide to the Scriptures and JST, and " +
            "the Bible and Book of Mormon EPUBs for footnotes. Leave the files as downloaded."

    override fun getToolManagerManifest(): ClientToolManifest = ClientToolManifest(
        title = "Scriptures",
        roots = listOf(
            ClientLeafNode(FileBrowserSpec(label = TextLibrary.LABEL, path = TextLibrary.FOLDER, headerText = STANDARD_WORKS_HEADER)),
        ) + UploadKind.entries.map { kind ->
            ClientLeafNode(FileBrowserSpec(label = kind.label, path = kind.folder, headerText = headers.getValue(kind)))
        },
    )

    override suspend fun onToolManagerDataUpdate() {
        super.onToolManagerDataUpdate()
        // Sort the upload right away if the tool is running; otherwise it happens on next open.
        if (AppGraph.isReady) {
            try {
                AppGraph.importer.importAll()
                AppGraph.text.importInbox()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Scriptures", "Sorting uploaded files failed", e)
            }
        }
    }
}
