package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import io.github.anweaver23.scriptures.core.FrontPage
import io.github.anweaver23.scriptures.data.AppGraph

/**
 * One page of a volume's front matter (the Book of Mormon's Title Page, the Witnesses), as reading
 * text that follows Display. Swiping turns to the neighbouring text pages; link pages (the Prophet
 * Joseph Smith's testimony, which opens Joseph Smith—History) are only in the book list.
 */
class FrontPageScreen(sealedActivity: SealedLightActivity, private val volumeId: String, pageId: String) :
    AppScreen<Unit>(sealedActivity) {
    private val scrolls = ScrollMemory()

    private var pageId by mutableStateOf(pageId)

    private fun textPages(): List<FrontPage> = AppGraph.frontMatter(volumeId).pages.filter { it.ref == null }

    private fun neighbor(direction: Int): String? {
        val pages = textPages()
        return pages.getOrNull(pages.indexOfFirst { it.id == pageId } + direction)?.id
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
        val page = textPages().firstOrNull { it.id == pageId } ?: return
        val display by AppGraph.display.collectAsState()
        val style = readerTextStyle(display.textSize.scale, display.lineSpacing)
        val gap = VERSE_GAP_UNITS.gridUnitsAsDp()

        ScreenFrame(title = page.title, onBack = { goBack() }, reading = true, zen = Zen.on, rightButton = nowPlayingButton()) {
            SwipePages(pages) {
                key(page.id) {
                    LightScrollView(Modifier.fillMaxSize().doubleTap(Zen::toggle), scrollState = scrolls.scroll(page.id)) {
                        page.blocks.forEach { block ->
                            when (block.t) {
                                "heading" -> BasicText(
                                    text = block.text,
                                    style = style.copy(textAlign = TextAlign.Center),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = gap),
                                )
                                "sig" -> BasicText(
                                    text = block.text,
                                    style = style.copy(color = LightThemeTokens.colors.contentSecondary, textAlign = TextAlign.End),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                else -> BasicText(
                                    text = block.text,
                                    style = style,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = gap),
                                )
                            }
                        }
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                    }
                }
            }
        }
    }
}
