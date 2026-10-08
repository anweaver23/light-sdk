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
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerAvailability
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
import io.github.anweaver23.scriptures.data.AudioLibrary
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays a queue of the user's files: every chapter of a book that has audio, or every hymn
 * in a hymnbook that has audio, starting at the one asked for. Playback is detached, so it
 * keeps going after leaving the tool; position is saved so it can resume after the service stops.
 *
 * The detached handle is only held while this screen is showing. Only one may exist per
 * process, and releasing it doesn't stop playback, so letting go on hide/pause means a
 * forgotten screen can never block the next one from connecting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(private val audio: LightAudio, private val request: Target?) : LightViewModel<Unit>() {
    private val catalog: Catalog = AppGraph.catalog
    private val progress = AppGraph.progress

    private val playerFlow = MutableStateFlow<LightAudioPlayer?>(null)

    /** The connected player, or null when disconnected or released after a failed connection. */
    private val player: LightAudioPlayer?
        get() = playerFlow.value?.takeIf { it.availability.value != LightAudioPlayerAvailability.Released }

    private val _queue = MutableStateFlow<List<Target>>(emptyList())

    private fun <T> fromPlayer(initial: T, pick: (LightAudioPlayer) -> StateFlow<T>): StateFlow<T> = playerFlow
        .flatMapLatest { it?.let(pick) ?: flowOf(initial) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    private val currentIndex = fromPlayer(NO_MEDIA_ITEM) { it.currentMediaItemIndex }
    val current: StateFlow<Target?> = combine(_queue, currentIndex) { queue, i -> queue.getOrNull(i) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val positionMs = fromPlayer(0L) { it.positionMs }
    val durationMs = fromPlayer(0L) { it.durationMs }
    val isPlaying = fromPlayer(false) { it.isPlaying }
    val error = fromPlayer<LightAudioError?>(null) { it.error }

    private val _speed = MutableStateFlow(1f)
    val speed = _speed.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** Set once the requested item has been queued, so reconnecting after a pause doesn't restart it. */
    private var started = false
    private var connectJob: Job? = null

    fun label(target: Target) = catalog.label(target)

    init {
        viewModelScope.launch { autosave() }
        viewModelScope.launch { clearFinishedPositions() }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        connect()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        disconnect()
        super.onScreenHide(screen)
    }

    override fun onAppPause() {
        disconnect()
        super.onAppPause()
    }

    private fun connect() {
        if (playerFlow.value != null) return
        val newPlayer = try {
            audio.newPlayer(playback = LightAudioPlayback.Detached)
        } catch (e: LightAudioPlayerException) {
            _message.value = "Audio isn't available right now (${e.message})."
            return
        }
        playerFlow.value = newPlayer
        connectJob = viewModelScope.launch {
            if (!newPlayer.awaitReady()) {
                _message.value = "Couldn't connect to audio playback."
                return@launch
            }
            _message.value = null
            start(newPlayer)
        }
    }

    private fun disconnect() {
        val old = playerFlow.value ?: return
        saveDetached()
        connectJob?.cancel()
        playerFlow.value = null
        // Disconnects the handle; detached playback carries on until stop().
        old.release()
    }

    private suspend fun start(player: LightAudioPlayer) {
        val live = player.currentMediaItemIndex.value != NO_MEDIA_ITEM
        val savedQueue = progress.savedQueue()

        // Reconnecting to playback that kept going while the tool was closed or paused.
        if (live && (started || request == null || savedQueue.getOrNull(player.currentMediaItemIndex.value) == request)) {
            _queue.value = savedQueue
            started = true
            return
        }
        if (started) return // stopped while we were away; nothing to pick back up
        started = true

        val index = AppGraph.library.index.value
        val queue: List<Target>
        val first: Target
        if (request != null) {
            queue = queueFor(request, index)
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
        val items = queue.mapNotNull { target ->
            val file = index.audio(target) ?: return@mapNotNull null
            LightAudioItem(
                source = LightAudioSource.FileSource(file),
                metadata = LightMediaMetadata(title = catalog.label(target), album = albumFor(target)),
            )
        }
        _queue.value = queue
        player.speed = _speed.value
        player.setMediaQueue(items, startIndex)
        player.play()
        // seekTo clamps to the known duration, which is 0 until the file has loaded.
        val saved = progress.position(first)
        if (saved > 0 && withTimeoutOrNull(10.seconds) { player.durationMs.first { it > 0 } } != null) {
            player.seekTo(saved)
        }
    }

    /** Every item in the same book or hymnbook that has audio, so playback rolls on to the next one. */
    private fun queueFor(target: Target, index: AudioLibrary.Index): List<Target> {
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
        currentIndex.collect { i ->
            if (previous != NO_MEDIA_ITEM && i == previous + 1) {
                _queue.value.getOrNull(previous)?.let { progress.clearPosition(it) }
            }
            previous = i
        }
    }

    /** Saves on a scope of its own, for when the screen is closing and viewModelScope is going away. */
    private fun saveDetached() {
        if (stopped) return
        val target = current.value ?: return
        val queue = _queue.value
        val position = positionMs.value
        AppGraph.scope.launch { progress.savePlayback(queue, target, position) }
    }

    private suspend fun save() {
        val target = current.value ?: return
        progress.savePlayback(_queue.value, target, positionMs.value)
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (isPlaying.value) {
            p.pause()
            viewModelScope.launch { save() }
        } else {
            p.play()
        }
    }

    fun skipBack() { player?.skipBack() }
    fun skipForward() { player?.skipForward() }
    fun previous() { player?.skipToPrevious() }
    fun next() { player?.skipToNext() }

    fun seekToFraction(fraction: Float) {
        val duration = durationMs.value
        if (duration > 0) player?.seekTo((duration * fraction).toLong())
    }

    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOf(_speed.value) + 1).mod(SPEEDS.size)]
        _speed.value = next
        player?.speed = next
    }

    /** Ends detached playback for good, rather than just leaving the screen. */
    fun stop() {
        saveDetached()
        // After stop() the position reads 0; don't let the disconnect save overwrite the real one.
        stopped = true
        player?.stop()
    }

    private var stopped = false

    override fun onCleared() {
        disconnect()
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
                PlayerOption("SPEED", "${speed.toString().removeSuffix(".0")}x", Modifier.weight(1f), viewModel::cycleSpeed)
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
