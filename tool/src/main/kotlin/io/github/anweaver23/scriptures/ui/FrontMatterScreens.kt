package io.github.anweaver23.scriptures.ui

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
 * One imported page (front matter, a Topical Guide / Bible Dictionary / Guide entry, a JST passage) as
 * reading text that follows Display. Links open verses or other pages; swiping turns to the next or
 * previous page of the same group.
 */
class PageScreen(sealedActivity: SealedLightActivity, pageId: String) : AppScreen<Unit>(sealedActivity), Keyed {
    private val scrolls = ScrollMemory()

    private var pageId by mutableStateOf(pageId)

    override val navKey: Any get() = NavKeys.page(pageId)

    /** Swiping stays among the pages listed together: a folder's, or a group's loose pages. */
    private fun siblings(): List<PageEntry> {
        val all = AppGraph.text.pageIndex().groups[pageGroup(pageId)].orEmpty()
        val folder = all.firstOrNull { it.id == pageId }?.folder
        return all.filter { it.folder == folder }
    }

    private fun neighbor(direction: Int): String? {
        val list = siblings()
        val i = list.indexOfFirst { it.id == pageId }
        return if (i < 0) null else list.getOrNull(i + direction)?.id
    }

    private val pages = PageSlider(
        canTurn = { neighbor(it) != null },
        turn = { direction -> neighbor(direction)?.let { this.pageId = it } },
    )

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val id = pageId
        val page by produceState<FrontPage?>(null, id) { value = withContext(Dispatchers.IO) { AppGraph.text.page(id) } }
        val display by AppGraph.display.collectAsState()
        val style = readerTextStyle(display.textSize.scale, display.lineSpacing)
        val gap = VERSE_GAP_UNITS.gridUnitsAsDp()
        val secondary = LightThemeTokens.colors.contentSecondary
        val title = page?.title ?: AppGraph.text.pageIndex().groups[pageGroup(id)]?.firstOrNull { it.id == id }?.title ?: ""

        AppPage(title = title, reading = true, zen = Zen.on) {
            SwipePages(pages) {
                val p = page
                if (p != null) key(id) {
                    LightScrollView(Modifier.fillMaxSize().doubleTap(Zen::toggle), scrollState = scrolls.scroll(id)) {
                        p.blocks.forEach { block ->
                            val link: (String) -> Unit = { openLink(it) }
                            when (block.t) {
                                "heading" -> SpanText(
                                    block.spans,
                                    style.copy(textAlign = TextAlign.Center),
                                    Modifier.fillMaxWidth().padding(bottom = gap),
                                    link,
                                )
                                "subheading" -> SpanText(
                                    block.spans,
                                    style.copy(fontWeight = FontWeight.Bold),
                                    Modifier.fillMaxWidth().padding(top = gap / 2, bottom = gap / 2),
                                    link,
                                )
                                "sig" -> SpanText(block.spans, style.copy(color = secondary, textAlign = TextAlign.End), Modifier.fillMaxWidth(), link)
                                "entry" -> SpanText(block.spans, style, Modifier.fillMaxWidth().padding(bottom = gap / 2), link)
                                "verse" -> {
                                    androidx.compose.foundation.text.BasicText(
                                        text = buildAnnotatedString {
                                            block.n?.let { withStyle(SpanStyle(color = secondary)) { append("$it  ") } }
                                            append(annotated(block.spans, link))
                                        },
                                        style = style,
                                        modifier = Modifier.fillMaxWidth().padding(bottom = gap),
                                    )
                                }
                                else -> SpanText(block.spans, style, Modifier.fillMaxWidth().padding(bottom = gap), link)
                            }
                        }
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    }
                }
            }
        }
    }
}

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
                        open(NavKeys.page(entry.id), { PageScreen(it, entry.id) })
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
        ListRow(page.title) { open(NavKeys.page(page.id), { PageScreen(it, page.id) }) }
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
                pages.forEach { page -> ListRow(page.title) { open(NavKeys.page(page.id), { PageScreen(it, page.id) }) } }
            }
        }
    }
}
