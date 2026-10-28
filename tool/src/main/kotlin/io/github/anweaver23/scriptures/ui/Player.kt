package io.github.anweaver23.scriptures.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
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
import io.github.anweaver23.scriptures.core.Catalog
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.core.label
import io.github.anweaver23.scriptures.data.AppGraph
import io.github.anweaver23.scriptures.data.AudioLibrary
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapLatest
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
 * There's no player screen: the chapter and hymn pages play in place, their bar becoming the
 * transport ([transportBar]).
 *
 * With [autoStart] off (both pages), connecting only picks up playback that's already live; nothing
 * plays until [play]. The reader keeps one inside its own view model, passing its scope as
 * [outerScope] and forwarding the screen events; it calls [close] when it's cleared.
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
    private val outerScope: CoroutineScope? = null,
) : LightViewModel<Unit>() {
    private val scope: CoroutineScope get() = outerScope ?: viewModelScope

    private val catalog: Catalog = AppGraph.catalog
    private val progress = AppGraph.progress

    private val playerFlow = MutableStateFlow<LightAudioPlayer?>(null)

    /** The connected player, or null when disconnected or released after a failed connection. */
    private val player: LightAudioPlayer?
        get() = playerFlow.value?.takeIf { it.availability.value != LightAudioPlayerAvailability.Released }

    private val _queue = MutableStateFlow<List<Target>>(emptyList())

    private fun <T> fromPlayer(initial: T, pick: (LightAudioPlayer) -> StateFlow<T>): StateFlow<T> = playerFlow
        .flatMapLatest { it?.let(pick) ?: flowOf(initial) }
        .stateIn(scope, SharingStarted.Eagerly, initial)

    private val currentIndex = fromPlayer(NO_MEDIA_ITEM) { it.currentMediaItemIndex }

    /**
     * The item being started, from queueing it until it's actually playing. Until then the player
     * still reports the old track's index and position, and not playing, so the page would flash
     * from the transport to its own bar (or the paused bar) and back. While set it's [current].
     */
    private val _starting = MutableStateFlow<Target?>(null)
    val starting: StateFlow<Target?> = _starting.asStateFlow()

    val current: StateFlow<Target?> = combine(_queue, currentIndex, _starting) { queue, i, starting ->
        starting ?: queue.getOrNull(i)
    }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val positionMs = fromPlayer(0L) { it.positionMs }
    val durationMs = fromPlayer(0L) { it.durationMs }
    val isPlaying = fromPlayer(false) { it.isPlaying }
    val error = fromPlayer<LightAudioError?>(null) { it.error }
    private val availability = fromPlayer<LightAudioPlayerAvailability?>(null) { it.availability }

    private val _speed = MutableStateFlow(1f)
    val speed = _speed.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** Set once the requested item has been queued, so reconnecting after a pause doesn't restart it. */
    private var started = false
    private var connectJob: Job? = null

    fun label(target: Target) = catalog.label(target)

    init {
        scope.launch { autosave() }
        scope.launch { clearFinishedPositions() }
        scope.launch { publishNowPlaying() }
        scope.launch { logStates() }
    }

    /** What the player reports as it changes (not position), for chasing bar flicker in logcat. */
    private suspend fun logStates() {
        combine(availability, currentIndex, isPlaying, _starting, current) { ready, i, playing, starting, now ->
            "ready=$ready index=$i playing=$playing starting=${starting?.let(catalog::label)} current=${now?.let(catalog::label)}"
        }.collect { Log.d(LOG_TAG, it) }
    }

    private data class Reading(
        val ready: LightAudioPlayerAvailability,
        val index: Int,
        val now: Target?,
        val playing: Boolean,
    )

    /**
     * Keeps [NowPlaying] up to date while connected: what's live, or nothing once the player has
     * reported no item for [NOTHING_LIVE_MS]. Read straight from the one connected player, so letting
     * go of it (leaving the page) says nothing: the view model's own flows fall back to "no item" one
     * at a time on disconnect, which had cleared the button on every page you went back to. The moment
     * between connecting and adopting the live queue (an item, but no queue yet) is skipped too.
     */
    private suspend fun publishNowPlaying() {
        playerFlow.flatMapLatest { p ->
            if (p == null) {
                emptyFlow()
            } else {
                combine(p.availability, p.currentMediaItemIndex, current, p.isPlaying, ::Reading).mapLatest { r ->
                    if (r.ready == LightAudioPlayerAvailability.Ready && r.index == NO_MEDIA_ITEM) delay(NOTHING_LIVE_MS)
                    r
                }
            }
        }.collect { r ->
            when {
                r.ready != LightAudioPlayerAvailability.Ready -> Unit
                r.index == NO_MEDIA_ITEM -> NowPlaying.live.value = null
                r.now != null -> NowPlaying.live.value = NowPlaying.Live(r.now, r.playing)
            }
        }
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
        connectJob = scope.launch {
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
        _starting.value = first
        try {
            _queue.value = queue
            player.speed = _speed.value
            player.setMediaQueue(items, startIndex)
            seekToSaved(player, first, autoplay)
            if (autoplay) {
                withTimeoutOrNull(START_SETTLE_MS) {
                    combine(isPlaying, currentIndex) { playing, i -> playing && i == startIndex }.first { it }
                }
                // A fresh start can report playing, then not for a moment (buffering after the seek),
                // then playing again; stay pinned through that. Pausing ends the hold at once.
                withTimeoutOrNull(START_HOLD_MS) { _starting.first { it == null } }
            }
        } finally {
            _starting.value = null
        }
    }

    /** Asked for before the player was ready (e.g. right after coming back from the version picker). */
    private var pendingPlay: Target? = null

    /** Plays [target] and the rest of its book or hymnbook after it, replacing whatever was queued. */
    fun play(target: Target) {
        val p = player
        if (p != null && p.availability.value == LightAudioPlayerAvailability.Ready) {
            scope.launch { playNow(p, target) }
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

    /** Saves on a scope of its own, for when the screen is closing and scope is going away. */
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
        _starting.value = null
        if (isPlaying.value) {
            p.pause()
            scope.launch { save() }
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
        scope.launch { playNow(p, target) }
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
        NowPlaying.live.value = null
        if (target != null) AppGraph.scope.launch { progress.clearPosition(target) }
    }

    private var stopped = false

    /** Lets go of the player, as clearing does; for a player held inside another view model. */
    fun close() = disconnect()

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object {
        /** Within this of the end, a recording counts as finished. */
        const val END_MARGIN_MS = 1500L
        /** How long a connected player must report no item before the now-playing button goes. */
        const val NOTHING_LIVE_MS = 1000L
        const val LOG_TAG = "ScripturesPlayer"
        /** Longest a start holds the page in "playing" while waiting for the player to catch up. */
        const val START_SETTLE_MS = 5000L
        /** How long a start stays pinned after it first reports playing. */
        const val START_HOLD_MS = 1500L
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
    }
}

/** How long playback must stay paused before the bar becomes play / stop. */
internal const val PAUSED_BAR_DELAY_MS = 350L

/**
 * The bar of a page whose chapter or hymn is the one playing ([here]): the transport while it plays;
 * paused part way in, stop then play side by side in the middle, as the LightOS lock screen draws
 * them. Null when the page isn't the one playing, so it shows the app's bar.
 */
@Composable
fun PlayerViewModel.transportBar(here: Boolean): List<LightBarButton?>? {
    val position by positionMs.collectAsState()
    val duration by durationMs.collectAsState()
    val playing by isPlaying.collectAsState()
    val startingNow by starting.collectAsState()
    // Starting and switching tracks report "not playing" for a moment; only a pause that lasts, part
    // way in, swaps the bar.
    val pauseHeld by produceState(initialValue = !playing, playing) {
        if (playing) value = false else { delay(PAUSED_BAR_DELAY_MS); value = true }
    }
    if (!here) return null
    val canSeek = duration > 0
    return if (pauseHeld && position > 0 && startingNow == null) {
        listOf(
            null,
            LightBarButton.LightIcon(LightIcons.STOP, ::end, contentDescription = "Stop"),
            LightBarButton.LightIcon(LightIcons.PLAY, ::togglePlayPause, contentDescription = "Play"),
            null,
        )
    } else {
        listOf(
            LightBarButton.LightIcon(LightIcons.REWIND, ::previous, contentDescription = "Previous"),
            LightBarButton.LightIcon(LightIcons.SKIP_BACKWARD_FIFTEEN, ::skipBack.takeIf { canSeek }),
            LightBarButton.LightIcon(LightIcons.PAUSE, ::togglePlayPause, contentDescription = "Pause"),
            LightBarButton.LightIcon(LightIcons.SKIP_FORWARD_FIFTEEN, ::skipForward.takeIf { canSeek }),
            LightBarButton.LightIcon(LightIcons.FAST_FORWARD, ::next, contentDescription = "Next"),
        )
    }
}

/** Under the page, when its playback hit an error. */
@Composable
fun PlaybackError(player: PlayerViewModel, here: Boolean) {
    val error by player.error.collectAsState()
    val e = error
    if (!here || e == null) return
    AppText(
        text = playbackErrorMessage(e),
        variant = LightTextVariant.Detail,
        align = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

fun playbackErrorMessage(error: LightAudioError): String = when (error.kind) {
    LightAudioErrorKind.Unsupported -> "This file's format can't be played. Try an MP3 or M4A."
    LightAudioErrorKind.Source -> "Couldn't read this file. Try uploading it again."
    else -> "Playback stopped (${error.diagnostic})."
}

