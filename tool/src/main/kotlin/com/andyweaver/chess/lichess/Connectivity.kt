package com.andyweaver.chess.lichess

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import kotlin.random.Random

/**
 * Centralised "can we reach Lichess?" state, inferred purely from transport outcomes.
 *
 * WHY INFERENCE: the Light SDK gives tools no connectivity API at all. `getSystemService`
 * is a hard build failure in the plugin's blocked-code patterns, `android.content.Context`
 * is a blocked import, `SealedLightContext.androidContext` is `internal` to `:sdk:client`,
 * and `LightServiceMethod` exposes no network method. Watching our own HTTP calls succeed
 * or fail is the only mechanism available to a tool.
 *
 * Everything in this file is deliberately free of Android (and Ktor-type) dependencies so
 * it stays plain-JVM unit testable — the established pattern in this codebase (see
 * `board/BoardAnimationTest.kt`). Ktor exception types are matched by class NAME rather
 * than `is`, both to keep this file dependency-light and because several of them are JVM
 * typealiases whose identity is easy to get subtly wrong.
 */

// ---------------------------------------------------------------------------
// State model
// ---------------------------------------------------------------------------

/**
 * A single transport failure is a blip, not an outage — a correspondence app on a phone
 * will drop the odd request. Only after this many CONSECUTIVE failures (with no successful
 * call in between) do we flip the UI to "offline".
 */
internal const val OFFLINE_AFTER_FAILURES = 2

/**
 * Observed connectivity to Lichess.
 *
 * @param online false only once [consecutiveFailures] has reached [OFFLINE_AFTER_FAILURES];
 *   a single failed request leaves this true (with a non-zero failure count) so the UI
 *   doesn't flap.
 * @param consecutiveFailures transport failures since the last success. Reset to 0 by any
 *   successful HTTP exchange (a non-2xx status still counts as success here — the server
 *   answered, so the network is fine).
 * @param sinceMillis when the current [online] value was ENTERED. Unchanged by further
 *   failures while already offline, so "offline for 3 minutes" is derivable.
 * @param lastError the friendly message for the most recent transport failure, or null.
 */
data class ConnectivityStatus(
    val online: Boolean = true,
    val consecutiveFailures: Int = 0,
    val sinceMillis: Long = 0L,
    val lastError: String? = null,
) {
    /** True while we've seen failures but haven't yet declared an outage. */
    val degraded: Boolean get() = online && consecutiveFailures > 0
}

/** Pure transition: a successful exchange. Only bumps [ConnectivityStatus.sinceMillis] on a real change. */
internal fun ConnectivityStatus.afterSuccess(nowMillis: Long): ConnectivityStatus =
    if (online) {
        if (consecutiveFailures == 0 && lastError == null) this
        else copy(consecutiveFailures = 0, lastError = null)
    } else {
        ConnectivityStatus(online = true, consecutiveFailures = 0, sinceMillis = nowMillis, lastError = null)
    }

/** Pure transition: a transport failure. Flips offline only at the [OFFLINE_AFTER_FAILURES] threshold. */
internal fun ConnectivityStatus.afterFailure(nowMillis: Long, message: String?): ConnectivityStatus {
    val failures = consecutiveFailures + 1
    val stillOnline = failures < OFFLINE_AFTER_FAILURES
    return if (online && !stillOnline) {
        // Transition online -> offline: this is when "since" starts.
        ConnectivityStatus(online = false, consecutiveFailures = failures, sinceMillis = nowMillis, lastError = message)
    } else {
        copy(consecutiveFailures = failures, lastError = message)
    }
}

/**
 * Process-wide connectivity state. A plain object rather than an injected dependency
 * because every screen wants the same answer and there is exactly one network we can
 * be off. [LichessApi] feeds it from one place per client (a Ktor `HttpResponseValidator`)
 * plus the stream reconnect loop; UI just collects [status].
 */
object Connectivity {

    /** Overridable only for tests; production always reads the wall clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val _status = MutableStateFlow(ConnectivityStatus())

    /**
     * The last throwable counted, so the SAME failure reported from two layers (the Ktor
     * response validator AND `safeAction` / the stream reconnect loop, which all see it)
     * only increments [ConnectivityStatus.consecutiveFailures] once. Without this a single
     * dropped request would be double-counted and trip the offline threshold immediately,
     * defeating the flap protection.
     */
    private var lastReported: Throwable? = null

    val status: StateFlow<ConnectivityStatus> = _status.asStateFlow()

    val isOnline: Boolean get() = _status.value.online

    /** Any completed HTTP exchange, whatever the status code — the network demonstrably works. */
    fun reportSuccess() {
        lastReported = null
        _status.value = _status.value.afterSuccess(clock())
    }

    /**
     * Reports a thrown failure. Non-transport failures (HTTP status errors, JSON decode
     * problems, cancellation) are IGNORED — they say nothing about connectivity.
     * Reporting the same throwable instance twice is a no-op (see [lastReported]).
     *
     * @return true if [error] was classified as a transport failure.
     */
    fun reportFailure(error: Throwable): Boolean {
        val transport = error.isConnectivityFailure()
        if (error === lastReported) return transport
        lastReported = error
        if (!transport) return false
        _status.value = _status.value.afterFailure(clock(), error.userMessage())
        return true
    }

    /** Test hook / logout reset. */
    internal fun reset() {
        lastReported = null
        _status.value = ConnectivityStatus()
    }
}

// ---------------------------------------------------------------------------
// Classification
// ---------------------------------------------------------------------------

/**
 * An HTTP response that Lichess actually returned but which we treat as an error
 * (used by the stream loop, where a non-2xx body would otherwise just look like an
 * empty stream). Explicitly NOT a connectivity failure: the server answered.
 */
class LichessHttpException(
    val statusCode: Int,
    override val message: String,
) : Exception(message)

/**
 * True for a single link in the cause chain that means "we couldn't complete the exchange
 * over the wire".
 *
 * Matched with `is` against the real types rather than by class name: the Light plugin's
 * `BLOCKED_CODE_PATTERNS` fails the build on `.javaClass` and `.java.`, so walking a
 * superclass chain by reflection isn't available — and `is` gets subclassing for free
 * anyway (SSLHandshakeException is an SSLException; ConnectException,
 * NoRouteToHostException and PortUnreachableException are all SocketExceptions).
 */
private fun Throwable.isTransportLink(): Boolean = when (this) {
    // JDK / OkHttp
    is UnknownHostException,        // DNS: no network, or lichess.org unresolvable
    is SocketException,             // covers Connect / NoRouteToHost / PortUnreachable / reset
    is SocketTimeoutException,      // OkHttp read timeout; also Ktor's JVM typealias
    is UnknownServiceException,
    is SSLException,                // covers handshake + peer-unverified
    is EOFException,                // truncated body: the socket died mid-stream
    // Ktor
    is HttpRequestTimeoutException, // whole-call (OkHttp callTimeout) budget blown
    is ConnectTimeoutException,
    is TimeoutCancellationException, // withTimeout {} — a stall, despite being a CancellationException
    -> true

    else -> false
}

/**
 * True when [this] is a transport-level failure (DNS, connect, TLS, timeout, dropped
 * socket) rather than something Lichess deliberately told us.
 *
 * NOT connectivity failures, by design:
 *  - [LichessHttpException] and any HTTP status error (401/403/429/5xx) — the server replied.
 *  - JSON/serialization errors — we got bytes, they just didn't parse.
 *  - [CancellationException] — that's us, leaving the screen.
 *
 * Walks the cause chain, since Ktor and OkHttp routinely wrap the real cause.
 */
fun Throwable.isConnectivityFailure(): Boolean {
    var e: Throwable? = this
    var depth = 0
    while (e != null && depth < 8) {
        // CancellationException is never a connectivity signal — but note Ktor's
        // HttpRequestTimeoutException is NOT a CancellationException, and Kotlin's
        // TimeoutCancellationException (from withTimeout) IS one yet does mean a stall,
        // so check the transport names BEFORE bailing out on cancellation.
        if (e.isTransportLink()) return true
        if (e is LichessHttpException) return false
        if (e is CancellationException) return false
        e = e.cause?.takeIf { it !== e }
        depth++
    }
    return false
}

// ---------------------------------------------------------------------------
// User-facing messages
// ---------------------------------------------------------------------------

const val OFFLINE_MESSAGE = "No connection to Lichess"
const val TIMEOUT_MESSAGE = "Lichess isn't responding"
const val RATE_LIMITED_MESSAGE = "Lichess is rate limiting — try again in a minute"

/**
 * A short, human message for [this], instead of leaking Ktor internals like
 * "Socket timeout has expired [url=…, socket_timeout=unknown] ms" to the user.
 * Non-transport failures keep their own message (Lichess's error text is usually the
 * most useful thing we can say) with a plain fallback.
 */
fun Throwable.userMessage(): String {
    if (this is LichessHttpException) {
        return when (statusCode) {
            429 -> RATE_LIMITED_MESSAGE
            401, 403 -> "Lichess rejected your token — try logging in again"
            in 500..599 -> "Lichess is having trouble right now"
            else -> message
        }
    }
    if (!isConnectivityFailure()) return message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
    return if (isTimeoutFailure()) TIMEOUT_MESSAGE else OFFLINE_MESSAGE
}

/** A transport failure that was specifically a stall, not an unreachable host. */
internal fun Throwable.isTimeoutFailure(): Boolean {
    var e: Throwable? = this
    var depth = 0
    while (e != null && depth < 8) {
        val timedOut = e is SocketTimeoutException ||
            e is HttpRequestTimeoutException ||
            e is ConnectTimeoutException ||
            e is TimeoutCancellationException
        if (timedOut) return true
        e = e.cause?.takeIf { it !== e }
        depth++
    }
    return false
}

// ---------------------------------------------------------------------------
// Stream health + reconnect backoff
// ---------------------------------------------------------------------------

/**
 * Progress signal for a self-healing stream, delivered through the optional `onStatus`
 * callback on [LichessApi.streamBoardGame] / [LichessApi.streamEvents]. Deliberately NOT
 * folded into the event Flow: both event types are sealed interfaces whose `when`s are
 * exhaustive at the call sites, so adding members there would be a breaking change.
 */
sealed interface StreamStatus {
    /** The stream is open and Lichess is talking to us. */
    data object Connected : StreamStatus

    /** The connection dropped; we'll retry in [delayMillis]. [attempt] is 1-based. */
    data class Reconnecting(val attempt: Int, val delayMillis: Long, val cause: Throwable?) : StreamStatus

    /** Retries exhausted (or the failure was fatal). The Flow throws [cause] right after this. */
    data class GaveUp(val cause: Throwable?) : StreamStatus
}

/** First retry delay. */
internal const val RECONNECT_BASE_DELAY_MS = 1_000L

/** Ceiling for ordinary backoff — a correspondence app never needs to hammer. */
internal const val RECONNECT_MAX_DELAY_MS = 30_000L

/**
 * LICHESS_API.md: on HTTP 429 Lichess asks clients to wait a full minute before the next
 * request. Honoured verbatim rather than folded into the exponential curve.
 */
internal const val RATE_LIMIT_DELAY_MS = 60_000L

/** Consecutive failed reconnects before a stream gives up and surfaces the error. */
internal const val MAX_RECONNECT_ATTEMPTS = 8

/**
 * A connection that stayed up at least this long is considered "healthy"; reconnecting
 * after it resets the attempt counter. Without this, a server that accepts and then
 * immediately drops us would retry forever at the base delay.
 */
internal const val STREAM_STABLE_MS = 10_000L

/**
 * Exponential backoff with ±25% jitter, capped at [RECONNECT_MAX_DELAY_MS]. A 429 cause
 * short-circuits to [RATE_LIMIT_DELAY_MS] (also jittered, so a fleet of clients doesn't
 * retry in lockstep).
 *
 * Pure — [random] is injected so the schedule is unit testable.
 */
internal fun reconnectDelayMillis(
    attempt: Int,
    cause: Throwable? = null,
    random: Random = Random.Default,
): Long {
    val rateLimited = cause is LichessHttpException && cause.statusCode == 429
    val base = if (rateLimited) {
        RATE_LIMIT_DELAY_MS
    } else {
        val exponent = (attempt - 1).coerceIn(0, 20)
        val raw = RECONNECT_BASE_DELAY_MS shl exponent
        if (raw <= 0L) RECONNECT_MAX_DELAY_MS else raw.coerceAtMost(RECONNECT_MAX_DELAY_MS)
    }
    // ±25% jitter.
    val jitter = base / 4
    return (base - jitter + random.nextLong(2 * jitter + 1)).coerceAtLeast(0L)
}

/**
 * Whether a failed stream connection is worth retrying.
 *
 * 401/403 mean the token is dead — retrying can only spam Lichess with a credential that
 * will never work, so those propagate to the caller (which drops the user back to login).
 * 400/404 mean the game/endpoint isn't there. Everything else — transport failures, 429,
 * 5xx, a clean EOF — is transient.
 */
internal fun isRetryableStreamFailure(cause: Throwable): Boolean {
    if (cause is CancellationException) return false
    if (cause is LichessHttpException) {
        return when (cause.statusCode) {
            401, 403, 400, 404 -> false
            else -> true
        }
    }
    return true
}
