package io.github.anweaver23.scriptures.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayerException
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightThemeTokens
import io.github.anweaver23.scriptures.core.Target
import io.github.anweaver23.scriptures.data.AppGraph
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What's playing (or paused part way), for the corner button. Only a connected player knows for sure,
 * and one is connected only on a chapter or hymn page, so this is what the last one saw: it can be a
 * track behind after autoplay moved on elsewhere. The page the button opens adopts the live playback
 * and catches up (`follow`).
 */
object NowPlaying {
    data class Live(val target: Target, val playing: Boolean)

    val live = MutableStateFlow<Live?>(null)

    /**
     * Connects just long enough to see whether detached playback is live (the tool was closed while it
     * played), then lets go: only one handle may exist per process.
     */
    suspend fun probe(audio: LightAudio): Live? {
        val player = try {
            audio.newPlayer(playback = LightAudioPlayback.Detached)
        } catch (e: LightAudioPlayerException) {
            return null
        }
        try {
            if (withTimeoutOrNull(PROBE_TIMEOUT_MS) { player.awaitReady() } != true) return null
            val index = player.currentMediaItemIndex.value
            if (index == NO_MEDIA_ITEM) return null
            val target = AppGraph.progress.savedQueue().getOrNull(index) ?: return null
            return Live(target, player.isPlaying.value).also { live.value = it }
        } finally {
            player.release()
        }
    }

    private const val PROBE_TIMEOUT_MS = 1500L
}

/** Whether [a] and [b] are the same page (a hymn's two recordings share one). */
fun samePage(a: Target, b: Target): Boolean = when {
    a is Target.HymnRef && b is Target.HymnRef -> a.base == b.base
    else -> a == b
}

/**
 * The top bar's right corner while something plays: a small equalizer, its bars gently moving while
 * playing and still while paused. It opens the playing chapter or hymn. Not shown on the page that's
 * [showing] it (its bar is already the transport). [jumpHere] lets a reader or hymn page turn to it in
 * place instead of opening another page; it returns false when it can't.
 */
@Composable
fun SimpleLightScreen<*>.nowPlayingButton(
    showing: Target? = null,
    jumpHere: (Target) -> Boolean = { false },
): LightBarButton? {
    val live by NowPlaying.live.collectAsState()
    val now = live ?: return null
    if (showing != null && samePage(now.target, showing)) return null
    return LightBarButton.Icon(
        painter = equalizerPainter(now.playing),
        contentDescription = "Now playing",
        onClick = {
            val target = now.target
            if (!jumpHere(target)) {
                when (target) {
                    is Target.Chapter -> navigateTo({ ReaderScreen(it, target, followPlayback = true) })
                    is Target.HymnRef -> navigateTo({ HymnScreen(it, target, followPlayback = true) })
                }
            }
        },
    )
}

@Composable
private fun equalizerPainter(playing: Boolean): Painter {
    val color = LightThemeTokens.colors.content
    val phase: State<Float> = if (playing) {
        rememberInfiniteTransition(label = "now playing").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(EQ_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "bars",
        )
    } else {
        remember { mutableFloatStateOf(-1f) }
    }
    return remember(color, phase) { EqualizerPainter(color, phase) }
}

/**
 * Three thin bars, as simple as a sound mark gets: the stroke of Material Symbols "graphic_eq"
 * (Outlined, weight 300), without its outer pairs. While playing, the middle bar sinks to just below
 * the outer two as they rise together, then comes back up as they settle: a slow see-saw rather
 * than a wave. A [phase] below 0 (paused) draws them still, middle tallest. Same 800-unit crop as our
 * other Material icons.
 */
private class EqualizerPainter(private val color: Color, private val phase: State<Float>) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val unit = minOf(size.width, size.height) / CROP
        val left = (size.width - CROP * unit) / 2
        val top = (size.height - CROP * unit) / 2
        // Read in draw, so the animation redraws without recomposing.
        val t = phase.value
        // 1 = middle tall, outer short; 0 = middle short, outer tall.
        val swing = if (t < 0f) 1f else 0.5f + 0.5f * sin(2 * PI * t).toFloat()
        BARS.forEach { (x, short, tall) ->
            val k = if (x == MIDDLE_X) swing else 1f - swing
            val h = short + (tall - short) * k
            drawRect(
                color = color,
                topLeft = Offset(left + (x - PAD) * unit, top + (MID - PAD - h) * unit),
                size = Size(BAR_WIDTH * unit, 2 * h * unit),
            )
        }
    }

    private companion object {
        const val CROP = 800f
        const val PAD = 80f
        const val MID = 480f
        const val BAR_WIDTH = 60f
        const val MIDDLE_X = 450f
        /** Each bar's left edge, then its half height at its lowest and highest, in 960-unit space. */
        val BARS = listOf(
            Triple(250f, 170f, 215f),
            Triple(MIDDLE_X, 140f, 300f),
            Triple(650f, 170f, 215f),
        )
    }
}

/** One slow breath: calm, not a VU meter. */
private const val EQ_PERIOD_MS = 2400
