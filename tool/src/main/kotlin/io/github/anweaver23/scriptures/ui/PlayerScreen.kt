package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
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
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
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
 * With [autoStart] off (the hymn page), connecting only picks up playback that's already live;
 * nothing plays until [play].
 *
 * The detached handle is only held while this screen is showing. Only one may exist per
 * process, and releasing it doesn't stop playback, so letting go on hide/pause means a
 * forgotten screen can never block the next one from connecting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val audio: LightAudio,
    private val request: Target?,
    private val autoStart: Boolean = true,
) : LightViewModel<Unit>() {
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
            val asked = pendingPlay
            pendingPlay = null
            if (asked != null) playNow(newPlayer, asked) else start(newPlayer)
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
            // Left before the resume seek finished last time; finish it now.
            pendingSeek?.let { seekToSaved(player, it, autoplay = true) }
            return
        }
        // The hymn page starts playback only when asked.
        if (!autoStart) return
        // Coming back after the session ended on its own (stopped, or idle too long): rebuild
        // it from what was saved, but leave it paused. Only a fresh open starts playing.
        val autoplay = !started
        val resumeSaved = started || request == null
        started = true

        val index = AppGraph.library.index.value
        val queue: List<Target>
        val first: Target
        if (!resumeSaved && request != null) {
            queue = playbackQueue(request, index)
            first = request
        } else {
            queue = savedQueue.filter { index.hasAudio(it) }
            first = progress.lastPlayed.first()?.takeIf { it in queue } ?: queue.firstOrNull() ?: run {
                _message.value = "Nothing to resume."
                return
            }
        }
        startQueue(player, queue, first, autoplay)
    }

    private suspend fun startQueue(player: LightAudioPlayer, queue: List<Target>, first: Target, autoplay: Boolean) {
        val index = AppGraph.library.index.value
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
        seekToSaved(player, first, autoplay)
    }

    /** Asked for before the player was ready (e.g. right after coming back from the version picker). */
    private var pendingPlay: Target? = null

    /** Plays [target] and the rest of its book or hymnbook after it, replacing whatever was queued. */
    fun play(target: Target) {
        val p = player
        if (p != null && p.availability.value == LightAudioPlayerAvailability.Ready) {
            viewModelScope.launch { playNow(p, target) }
        } else {
            pendingPlay = target
        }
    }

    private suspend fun playNow(player: LightAudioPlayer, target: Target) {
        started = true
        stopped = false
        startQueue(player, playbackQueue(target, AppGraph.library.index.value), target, autoplay = true)
    }

    /** An item whose saved position hasn't been applied yet; its stored position must not be overwritten. */
    private var pendingSeek: Target? = null

    private suspend fun seekToSaved(player: LightAudioPlayer, target: Target, autoplay: Boolean) {
        pendingSeek = target
        // seekTo clamps to the known duration, which is 0 until setMediaQueue's prepare has loaded
        // the file. Seeking before play() also avoids a blip from the start of the recording.
        val saved = progress.position(target)
        val duration = if (saved > 0) withTimeoutOrNull(10.seconds) { player.durationMs.first { it > 0 } } else null
        // A recording that was left at its very end (it finished with autoplay off) starts over.
        if (duration != null && saved < duration - END_MARGIN_MS) {
            player.seekTo(saved)
        }
        pendingSeek = null
        if (autoplay) player.play()
    }

    /** Whether playback rolls on from [target] to the next chapter or hymn (Settings). */
    private fun autoplays(target: Target): Boolean = AppGraph.playback.value.let {
        when (target) {
            is Target.Chapter -> it.autoplayChapters
            is Target.HymnRef -> it.autoplayHymns
        }
    }

    /** What to queue for [target]: the rest of its book or hymnbook with autoplay on, else just it. */
    private fun playbackQueue(target: Target, index: AudioLibrary.Index): List<Target> =
        if (autoplays(target)) queueFor(target, index) else listOf(target)

    /** Every item in the same book or hymnbook that has audio, so playback rolls on to the next one. */
    private fun queueFor(target: Target, index: AudioLibrary.Index): List<Target> {
        val all: List<Target> = when (target) {
            is Target.Chapter -> {
                val book = catalog.scriptures.book(target.bookId)
                (1..(book?.chapters ?: 0)).map { target.copy(chapter = it) }
            }
            // Keep to the chosen version (vocal or accompaniment); a hymn with only the other one
            // plays that one rather than being skipped.
            is Target.HymnRef -> return catalog.hymnBook(target.hymnBookId)?.hymns.orEmpty().mapNotNull { hymn ->
                val same = target.copy(number = hymn.n)
                if (index.hasAudio(same)) same else index.versions(same).firstOrNull()
            }
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
        if (stopped || pendingSeek != null) return
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
            // Finished (autoplay off): play starts it again.
            val duration = durationMs.value
            if (duration > 0 && positionMs.value >= duration - END_MARGIN_MS) p.seekTo(0)
            p.play()
        }
    }

    fun skipBack() { player?.skipBack() }
    fun skipForward() { player?.skipForward() }
    fun previous() = step(-1)
    fun next() = step(1)

    /** With autoplay on the queue holds the whole book; with it off, only one item, so step by hand. */
    private fun step(direction: Int) {
        val p = player ?: return
        val now = current.value ?: return
        if (_queue.value.size > 1) {
            if (direction > 0) p.skipToNext() else p.skipToPrevious()
            return
        }
        val all = queueFor(now, AppGraph.library.index.value)
        val target = all.getOrNull(all.indexOf(now) + direction) ?: return
        viewModelScope.launch { playNow(p, target) }
    }

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

    /**
     * Stops and empties the queue, so the hymn page goes back to its play button. Stopping means
     * starting over next time, so the position isn't kept.
     */
    fun end() {
        val target = current.value
        stopped = true
        player?.let {
            it.stop()
            it.setMediaQueue(emptyList())
        }
        _queue.value = emptyList()
        if (target != null) AppGraph.scope.launch { progress.clearPosition(target) }
    }

    private var stopped = false

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object {
        /** Within this of the end, a recording counts as finished. */
        const val END_MARGIN_MS = 1500L
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
    }
}

/** How long playback must stay paused before the bar becomes play / stop. */
internal const val PAUSED_BAR_DELAY_MS = 350L

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
        val message by viewModel.message.collectAsState()
        val canSeek = duration > 0
        // Switching tracks reports "not playing" for a moment; only a pause that lasts swaps the bar.
        val paused by produceState(initialValue = !playing, playing) {
            if (playing) value = false else { delay(PAUSED_BAR_DELAY_MS); value = true }
        }

        ScreenFrame(
            title = "Listening",
            onBack = { goBack() },
            // Playing: the full transport. Paused: just play and stop, everything else out of the way.
            bottomBar = if (!paused) {
                listOf(
                    LightBarButton.LightIcon(LightIcons.REWIND, viewModel::previous, contentDescription = "Previous"),
                    LightBarButton.LightIcon(LightIcons.SKIP_BACKWARD_FIFTEEN, viewModel::skipBack.takeIf { canSeek }),
                    LightBarButton.LightIcon(LightIcons.PAUSE, viewModel::togglePlayPause, contentDescription = "Pause"),
                    LightBarButton.LightIcon(LightIcons.SKIP_FORWARD_FIFTEEN, viewModel::skipForward.takeIf { canSeek }),
                    LightBarButton.LightIcon(LightIcons.FAST_FORWARD, viewModel::next, contentDescription = "Next"),
                )
            } else {
                // As the LightOS lock screen draws it: stop, then play, together in the middle.
                listOf(
                    null,
                    LightBarButton.LightIcon(LightIcons.STOP, contentDescription = "Stop", onClick = {
                        viewModel.stop()
                        goBack()
                    }),
                    LightBarButton.LightIcon(LightIcons.PLAY, viewModel::togglePlayPause, contentDescription = "Play"),
                    null,
                )
            },
        ) {
            Spacer(Modifier.weight(1f))
            AppText(
                // LightOS's podcast player: Subheading for what's playing, Detail for date and time.
                text = current?.let(viewModel::label) ?: message ?: "Loading…",
                variant = LightTextVariant.Subheading,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            AppText(
                text = "${formatDuration(position)}  /  ${if (canSeek) formatDuration(duration) else "--:--"}",
                variant = LightTextVariant.Detail,
                align = TextAlign.Center,
                monospace = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
            )
            // Always drawn: hiding it while the next track reports its length made it flash.
            LightTouchableProgressBar(
                colors = LightThemeTokens.colors,
                progress = if (canSeek) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { if (canSeek) viewModel.seekToFraction(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                AppText(
                    text = playbackErrorMessage(it),
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

fun playbackErrorMessage(error: LightAudioError): String = when (error.kind) {
    LightAudioErrorKind.Unsupported -> "This file's format can't be played. Try an MP3 or M4A."
    LightAudioErrorKind.Source -> "Couldn't read this file. Try uploading it again."
    else -> "Playback stopped (${error.diagnostic})."
}

