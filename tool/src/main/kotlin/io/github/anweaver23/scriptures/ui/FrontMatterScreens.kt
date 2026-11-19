package io.github.anweaver23.scriptures.ui

import io.github.anweaver23.scriptures.core.PageRefs
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import io.github.anweaver23.scriptures.core.FrontPage
import io.github.anweaver23.scriptures.core.importer.PageEntry
import io.github.anweaver23.scriptures.core.importer.TextBuild
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The group a page id belongs to: "tg/faith" → "tg", "bofm/three" → "bofm", "dc-testament/od/1" → "dc-testament". */
fun pageGroup(id: String) = id.substringBefore('/')

/**
 * Every page of a study help ("tg", "bd", "gs", "jst") or a volume's front matter, in book order.
 * Long lists (the Topical Guide has 3,500 entries) are lazy.
 */
class PageListScreen(sealedActivity: SealedLightActivity, private val group: String) : AppScreen<Unit>(sealedActivity), Keyed {
    private val scrolls = ScrollMemory()

    override val navKey: Any get() = NavKeys.page("list:$group")

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val entries by produceState(emptyList<PageEntry>(), group) {
            value = withContext(Dispatchers.IO) { AppGraph.text.pageIndex().groups[group].orEmpty() }
        }
        AppPage(title = TextBuild.HELPS[group] ?: group) {
            LightLazyScrollView(Modifier.fillMaxSize(), listState = scrolls.list(), uniformItemHeightGridUnits = DENSE_ROW_UNITS) {
                items(entries, key = { it.id }) { entry ->
                    ListRow(entry.title, variant = DENSE_ROW_TEXT, heightUnits = DENSE_ROW_UNITS) {
                        openReader(PageRefs.chapter(entry.id))
                    }
                }
            }
        }
    }
}

/** The study helps that were imported: Topical Guide, Bible Dictionary, Guide to the Scriptures, JST. */
class StudyHelpsScreen(sealedActivity: SealedLightActivity) : AppScreen<Unit>(sealedActivity) {
    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val manifest by AppGraph.text.manifest.collectAsState()
        AppPage(title = "Study Helps") {
            LightScrollView(Modifier.fillMaxSize()) {
                TextBuild.HELPS.forEach { (id, title) ->
                    if ((manifest.pages[id] ?: 0) > 0) {
                        ListRow(title) { open(NavKeys.page("list:$id"), { PageListScreen(it, id) }) }
                    }
                }
            }
        }
    }
}

/**
 * A volume's front matter rows: before its books ([end] false) the loose pages and one row per folder
 * (the Book of Mormon's Introduction and Witnesses); after them ([end] true) the pages that go last
 * (the Pronunciation Guide, the Official Declarations).
 */
@Composable
fun SimpleLightScreen<*>.FrontMatterRows(volumeId: String, end: Boolean) {
    val entries = AppGraph.text.pageIndex().groups[volumeId].orEmpty().filter { it.end == end }
    entries.filter { it.folder == null }.forEach { page ->
        ListRow(page.title) { openReader(PageRefs.chapter(page.id)) }
    }
    entries.mapNotNull { it.folder }.distinct().forEach { folder ->
        ListRow(folder) { open(NavKeys.page("folder:$volumeId:$folder"), { PageFolderScreen(it, volumeId, folder) }) }
    }
}

/** The pages in one front-matter folder, in book order. */
class PageFolderScreen(sealedActivity: SealedLightActivity, private val volumeId: String, private val folder: String) :
    AppScreen<Unit>(sealedActivity), Keyed {
    private val scrolls = ScrollMemory()

    override val navKey: Any get() = NavKeys.page("folder:$volumeId:$folder")

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val pages = AppGraph.text.pageIndex().groups[volumeId].orEmpty().filter { it.folder == folder }
        AppPage(title = folder) {
            LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
                pages.forEach { page -> ListRow(page.title) { openReader(PageRefs.chapter(page.id)) } }
            }
        }
    }
}
