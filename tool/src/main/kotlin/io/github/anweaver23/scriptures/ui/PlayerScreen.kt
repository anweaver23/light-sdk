package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerException
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.data.AppGraph
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays a queue of the user's files: every chapter of a book that has audio, or every hymn
 * in a hymnbook that has audio, starting at the one asked for. Playback is detached, so it
 * keeps going after leaving the tool; position is saved so it can resume after the service stops.
 */
class PlayerViewModel(audio: LightAudio, private val request: Target?) : LightViewModel<Unit>() {
    private val catalog: Catalog = AppGraph.catalog
    private val progress = AppGraph.progress

    // Only one detached handle may exist per process. If one is somehow still open, fall back
    // to playback that stops when this screen closes rather than failing outright.
    private val player: LightAudioPlayer = try {
        audio.newPlayer(playback = LightAudioPlayback.Detached)
    } catch (e: LightAudioPlayerException) {
        audio.newPlayer()
    }

    private val _queue = MutableStateFlow<List<Target>>(emptyList())

    val current: StateFlow<Target?> = combine(_queue, player.currentMediaItemIndex) { queue, i -> queue.getOrNull(i) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val positionMs = player.positionMs
    val durationMs = player.durationMs
    val isPlaying = player.isPlaying
    val error = player.error

    private val _speed = MutableStateFlow(1f)
    val speed = _speed.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun label(target: Target) = catalog.label(target)

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { autosave() }
        viewModelScope.launch { clearFinishedPositions() }
    }

    private suspend fun start() {
        if (!player.awaitReady()) return
        val live = player.currentMediaItemIndex.value != NO_MEDIA_ITEM
        val savedQueue = progress.savedQueue()

        // Reconnecting to playback that kept going while the tool was closed.
        if (live && (request == null || savedQueue.getOrNull(player.currentMediaItemIndex.value) == request)) {
            _queue.value = savedQueue
            return
        }

        val index = AppGraph.library.index.value
        val queue: List<Target>
        val first: Target
        if (request != null) {
            queue = queueFor(request)
            first = request
        } else {
            queue = savedQueue.filter { index.hasAudio(it) }
            first = progress.lastPlayed.first()?.takeIf { it in queue } ?: queue.firstOrNull() ?: run {
                _message.value = "Nothing to resume."
                return
            }
        }
        val startIndex = queue.indexOf(first)
        if (startIndex < 0) {
            _message.value = "That audio file is missing. Try uploading it again."
            return
        }
        val items = queue.map { target ->
            LightAudioItem(
                source = LightAudioSource.FileSource(checkNotNull(index.audio(target))),
                metadata = LightMediaMetadata(title = catalog.label(target), album = albumFor(target)),
            )
        }
        _queue.value = queue
        player.speed = _speed.value
        player.setMediaQueue(items, startIndex)
        progress.position(first).takeIf { it > 0 }?.let(player::seekTo)
        player.play()
    }

    /** Everything after (and before) [target] that has audio, so playback rolls on to the next one. */
    private fun queueFor(target: Target): List<Target> {
        val index = AppGraph.library.index.value
        val all: List<Target> = when (target) {
            is Target.Chapter -> {
                val book = catalog.scriptures.book(target.bookId)
                (1..(book?.chapters ?: 0)).map { target.copy(chapter = it) }
            }
            is Target.HymnRef -> catalog.hymnBook(target.hymnBookId)?.hymns.orEmpty().map { target.copy(number = it.n) }
        }
        return all.filter { index.hasAudio(it) }
    }

    private fun albumFor(target: Target): String? = when (target) {
        is Target.Chapter -> catalog.scriptures.volume(target.volumeId)?.title
        is Target.HymnRef -> catalog.hymnBook(target.hymnBookId)?.title
    }

    private suspend fun autosave() {
        while (true) {
            delay(5.seconds)
            if (isPlaying.value) save()
        }
    }

    /** When playback moves on to the next item, the one before it was finished. */
    private suspend fun clearFinishedPositions() {
        var previous: Int = NO_MEDIA_ITEM
        player.currentMediaItemIndex.collect { i ->
            if (previous != NO_MEDIA_ITEM && i == previous + 1) {
                _queue.value.getOrNull(previous)?.let { progress.clearPosition(it) }
            }
            previous = i
        }
    }

    /** Saves on a scope of our own, for when the screen is closing and viewModelScope is going away. */
    private fun saveDetached() {
        val target = current.value ?: return
        val queue = _queue.value
        val position = player.positionMs.value
        CoroutineScope(Dispatchers.IO).launch {
            withContext(NonCancellable) { progress.savePlayback(queue, target, position) }
        }
    }

    private suspend fun save() {
        val target = current.value ?: return
        progress.savePlayback(_queue.value, target, player.positionMs.value)
    }

    fun togglePlayPause() {
        if (isPlaying.value) {
            player.pause()
            viewModelScope.launch { save() }
        } else {
            player.play()
        }
    }

    fun skipBack() = player.skipBack()
    fun skipForward() = player.skipForward()
    fun previous() = player.skipToPrevious()
    fun next() = player.skipToNext()

    fun seekToFraction(fraction: Float) {
        val duration = durationMs.value
        if (duration > 0) player.seekTo((duration * fraction).toLong())
    }

    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOf(_speed.value) + 1).mod(SPEEDS.size)]
        _speed.value = next
        player.speed = next
    }

    private var stopped = false

    /** Ends detached playback for good, rather than just leaving the screen. */
    fun stop() {
        saveDetached()
        stopped = true
        player.stop()
    }

    override fun onCleared() {
        if (!stopped) saveDetached()
        // Disconnects the handle; detached playback carries on until stop().
        player.release()
        super.onCleared()
    }

    private companion object {
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
    }
}

class PlayerScreen(private val sealedActivity: SealedLightActivity, private val request: Target?) :
    LightScreen<Unit, PlayerViewModel>(sealedActivity) {

    init {
        AppGraph.init(lightContext)
    }

    override val viewModelClass = PlayerViewModel::class.java
    override fun createViewModel() = PlayerViewModel(DefaultLightAudio(sealedActivity), request)

    @Composable
    override fun Content() {
        val current by viewModel.current.collectAsState()
        val position by viewModel.positionMs.collectAsState()
        val duration by viewModel.durationMs.collectAsState()
        val playing by viewModel.isPlaying.collectAsState()
        val error by viewModel.error.collectAsState()
        val speed by viewModel.speed.collectAsState()
        val message by viewModel.message.collectAsState()
        val canSeek = duration > 0

        ScreenFrame(
            title = "Listening",
            onBack = { goBack() },
            bottomBar = listOf(
                LightBarButton.LightIcon(LightIcons.REWIND, viewModel::previous, contentDescription = "Previous"),
                LightBarButton.LightIcon(LightIcons.SKIP_BACKWARD_FIFTEEN, viewModel::skipBack.takeIf { canSeek }),
                LightBarButton.LightIcon(if (playing) LightIcons.PAUSE else LightIcons.PLAY, viewModel::togglePlayPause),
                LightBarButton.LightIcon(LightIcons.SKIP_FORWARD_FIFTEEN, viewModel::skipForward.takeIf { canSeek }),
                LightBarButton.LightIcon(LightIcons.FAST_FORWARD, viewModel::next, contentDescription = "Next"),
            ),
        ) {
            Spacer(Modifier.weight(1f))
            LightText(
                text = current?.let(viewModel::label) ?: message ?: "Loading…",
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            LightText(
                text = "${formatDuration(position)}  /  ${if (canSeek) formatDuration(duration) else "--:--"}",
                variant = LightTextVariant.Fine,
                align = TextAlign.Center,
                monospace = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
            )
            if (canSeek) {
                LightTouchableProgressBar(
                    colors = LightThemeTokens.colors,
                    progress = (position.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = viewModel::seekToFraction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let {
                LightText(
                    text = errorMessage(it),
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth()) {
                PlayerOption("SPEED", "${speed}x", Modifier.weight(1f), viewModel::cycleSpeed)
                PlayerOption("PLAYBACK", "STOP", Modifier.weight(1f)) {
                    viewModel.stop()
                    goBack()
                }
            }
        }
    }
}

private fun errorMessage(error: LightAudioError): String = when (error.kind) {
    LightAudioErrorKind.Unsupported -> "This file's format can't be played. Try an MP3 or M4A."
    LightAudioErrorKind.Source -> "Couldn't read this file. Try uploading it again."
    else -> "Playback stopped (${error.diagnostic})."
}

@Composable
private fun PlayerOption(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    ) {
        LightText(label, LightTextVariant.Superfine, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        LightText(value, LightTextVariant.Fine, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
