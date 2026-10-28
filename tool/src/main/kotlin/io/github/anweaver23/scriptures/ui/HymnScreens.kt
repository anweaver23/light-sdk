package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.data.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HymnBooksScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()

    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        ScreenFrame(title = "Hymns", onBack = { goBack() }, rightButton = nowPlayingButton(), bottomBar = navBar()) {
            LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll()) {
                AppGraph.catalog.hymnBooks.forEach { book ->
                    ListRow(book.title) {
                        navigateTo({ HymnListScreen(it, book.id) })
                    }
                }
            }
        }
    }
}

class HymnListScreen(sealedActivity: SealedLightActivity, private val hymnBookId: String) :
    SimpleLightScreen<Unit>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()


    init {
        AppGraph.init(lightContext)
    }

    @Composable
    override fun Content() {
        val book = AppGraph.catalog.hymnBook(hymnBookId) ?: return

        // Long titles: the denser Subheading list, so more of each shows.
        ScreenFrame(title = book.title, onBack = { goBack() }, rightButton = nowPlayingButton(), bottomBar = navBar()) {
            LightLazyScrollView(
                Modifier.fillMaxSize(),
                listState = scrolls.list(),
                uniformItemHeightGridUnits = DENSE_ROW_UNITS,
            ) {
                items(book.hymns, key = { it.n }) { hymn ->
                    val target = Target.HymnRef(book.id, hymn.n)
                    ListRow("${hymn.n}  ${hymn.title}", variant = DENSE_ROW_TEXT, heightUnits = DENSE_ROW_UNITS) {
                        navigateTo({ HymnScreen(it, target) })
                    }
                }
            }
        }
    }
}

/**
 * A hymn's words (if the user uploaded them), and its player. There's no separate player screen: the
 * bar's play asks Vocal or Accompaniment (when both exist), then becomes the transport. Swiping turns
 * to the next or previous hymn, as chapters do, and when playback moves on to the next hymn the page
 * slides along with it. Opened from the now-playing button ([followPlayback]), it turns to whatever
 * hymn is live once it connects, in case autoplay moved on while no page was watching.
 */
class HymnScreen(
    private val sealedActivity: SealedLightActivity,
    target: Target.HymnRef,
    private var followPlayback: Boolean = false,
) : LightScreen<Unit, PlayerViewModel>(sealedActivity) {
    /** Scroll positions, kept while the screen is on the back stack. */
    private val scrolls = ScrollMemory()


    init {
        AppGraph.init(lightContext)
    }

    override val viewModelClass = PlayerViewModel::class.java
    override fun createViewModel() = PlayerViewModel(DefaultLightAudio(sealedActivity), request = null, autoStart = false)

    private val hymnBookId = target.hymnBookId
    private val hymns = AppGraph.catalog.hymnBook(hymnBookId)?.hymns.orEmpty()

    /** The hymn on the page. Lives on the screen so it survives a trip to the menu. */
    private var shown by mutableStateOf(target.base)

    /** Set when the page follows playback to a hymn that isn't simply the next one over. */
    private var jumpTo: Target.HymnRef? = null

    /** The last hymn playback was on, to tell when it moves on. */
    private var lastPlaying: Target.HymnRef? = null

    private var pageWidth = 0f

    private fun neighbor(direction: Int): Target.HymnRef? {
        val i = hymns.indexOfFirst { it.n == shown.number }
        return hymns.getOrNull(i + direction)?.let { shown.copy(number = it.n) }
    }

    private val pages = PageSlider(
        canTurn = { neighbor(it) != null },
        turn = { direction ->
            (jumpTo ?: neighbor(direction))?.let { shown = it }
            jumpTo = null
        },
    )

    @Composable
    override fun Content() {
        val hymn = AppGraph.catalog.hymnBook(hymnBookId)?.hymn(shown.number) ?: return
        val index by AppGraph.library.index.collectAsState()
        val current by viewModel.current.collectAsState()
        // Page animations need a frame clock, which the composition's scope has.
        val pageScope = rememberCoroutineScope()

        val lyricsFile = index.lyrics(shown)
        var lyrics by remember(lyricsFile) { mutableStateOf<String?>(null) }
        LaunchedEffect(lyricsFile) {
            lyrics = lyricsFile?.let { withContext(Dispatchers.IO) { runCatching { it.readText() }.getOrNull() } }
        }

        // Playback moved on (the next hymn in the queue, or NEXT / PREVIOUS): if the page was showing
        // the hymn that was playing, slide along to the new one.
        LaunchedEffect(current) {
            val now = current as? Target.HymnRef ?: return@LaunchedEffect
            val before = lastPlaying
            lastPlaying = now
            if (followPlayback) {
                followPlayback = false
                if (now.hymnBookId == hymnBookId) shown = now.base
                return@LaunchedEffect
            }
            if (before == null || before.base != shown || now.base == shown || now.hymnBookId != hymnBookId) {
                return@LaunchedEffect
            }
            val from = hymns.indexOfFirst { it.n == shown.number }
            val to = hymns.indexOfFirst { it.n == now.number }
            jumpTo = now.base
            pages.slide(pageScope, if (to > from) 1 else -1, pageWidth)
        }

        val playingHere = (current as? Target.HymnRef)?.base == shown
        val versions = index.versions(shown)
        // A hymn counts as where you left off, as a chapter does: home reopens it on launch.
        LaunchedEffect(shown) { AppGraph.progress.setLastHymn(shown) }

        ScreenFrame(
            title = "${hymn.n}. ${hymn.title}",
            reading = true,
            onBack = { goBack() },
            zen = Zen.on,
            // Another hymn in this book playing: turn to it here, as playback moving on would.
            rightButton = nowPlayingButton(showing = shown) { target ->
                (target is Target.HymnRef && target.hymnBookId == hymnBookId).also { if (it) shown = (target as Target.HymnRef).base }
            },
            // Not playing here: the app's bar, with play in the middle. Playing here: the transport.
            bottomBar = viewModel.transportBar(playingHere) ?: navBar(
                play = LightBarButton.LightIcon(LightIcons.PLAY, onClick = { listen(versions) }, contentDescription = "Play")
                    .takeIf { versions.isNotEmpty() },
            ),
        ) {
            // The frame that reads sideways drags stays still; only the inner page slides.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { pageWidth = it.width.toFloat() }
                    // Double tap: zen mode on or off (no bars). Swiping still works in it.
                    .doubleTap(Zen::toggle)
                    .pageSwipe(
                        enabled = true,
                        onDrag = { dx -> pages.drag(pageScope, dx) },
                        onRelease = { dx, width -> pages.release(pageScope, dx, width) },
                    ),
            ) {
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = pages.offset.value }) {
                    // Each hymn keeps its own spot; one not seen yet starts at the top.
                    key(shown) {
                        LightScrollView(Modifier.fillMaxSize(), scrollState = scrolls.scroll(shown)) {
                            val text = lyrics
                            if (text != null) {
                                // Hymn words are reading text: Display size, spacing and invert apply.
                                val display by AppGraph.display.collectAsState()
                                BasicText(
                                    text = text.trim(),
                                    style = readerTextStyle(display.textSize.scale, display.lineSpacing),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Note(
                                    "No words saved for this hymn. To add them, upload a text file named " +
                                        "\"${hymn.n}.txt\" to Hymn lyrics in the Tool Manager.",
                                )
                                if (versions.isEmpty()) {
                                    Note("No audio yet either. Add it in Settings, under Add audio.")
                                }
                            }
                        }
                    }
                }
            }
            PlaybackError(viewModel, playingHere)
        }
    }

    /** One recording plays straight away; with both, the picker asks which. */
    private fun listen(versions: List<Target.HymnRef>) {
        if (versions.size == 1) {
            viewModel.play(versions.single())
        } else {
            navigateTo(::HymnVersionScreen) { accompaniment -> viewModel.play(shown.copy(accompaniment = accompaniment)) }
        }
    }
}

/** Vocal or Accompaniment, as a LightOS picker. Returns true for accompaniment; BACK returns nothing. */
class HymnVersionScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Boolean>(sealedActivity) {
    @Composable
    override fun Content() {
        ScreenFrame(title = "Listen", onBack = { goBack() }) {
            LightScrollView(Modifier.fillMaxSize()) {
                ListRow("Vocal") { goBack(false) }
                ListRow("Accompaniment") { goBack(true) }
            }
        }
    }
}
