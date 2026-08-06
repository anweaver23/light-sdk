package com.andyweaver.chess.lichess

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plain-JVM tests for the connectivity classifier, the flap-avoidance state machine and
 * the reconnect backoff schedule — no Android, no Compose, no network (this codebase's
 * established pattern; see board/BoardAnimationTest.kt).
 */
class ConnectivityTest {

    // ----- classification -----

    @Test
    fun `transport failures are connectivity failures`() {
        assertTrue(UnknownHostException("lichess.org").isConnectivityFailure())
        assertTrue(ConnectException("refused").isConnectivityFailure())
        assertTrue(SocketTimeoutException("timeout").isConnectivityFailure())
        assertTrue(SocketException("reset").isConnectivityFailure())
        assertTrue(SSLHandshakeException("bad cert").isConnectivityFailure())
    }

    @Test
    fun `subclasses of a transport type are matched via the superclass chain`() {
        class WeirdSocketTimeout : SocketTimeoutException("nested vendor subclass")
        assertTrue(WeirdSocketTimeout().isConnectivityFailure())
    }

    @Test
    fun `wrapped causes are unwrapped`() {
        val wrapped = IllegalStateException("engine failed", IOException("io", UnknownHostException("dns")))
        assertTrue(wrapped.isConnectivityFailure())
    }

    @Test
    fun `http status errors are not connectivity failures`() {
        assertFalse(LichessHttpException(429, "HTTP 429").isConnectivityFailure())
        assertFalse(LichessHttpException(401, "HTTP 401").isConnectivityFailure())
        assertFalse(LichessHttpException(503, "HTTP 503").isConnectivityFailure())
    }

    @Test
    fun `cancellation and ordinary errors are not connectivity failures`() {
        assertFalse(CancellationException("screen hidden").isConnectivityFailure())
        assertFalse(IllegalArgumentException("bad fen").isConnectivityFailure())
        assertFalse(RuntimeException(null as Throwable?).isConnectivityFailure())
    }

    @Test
    fun `a deep non-transport cause chain terminates without matching`() {
        var e: Throwable = RuntimeException("root")
        repeat(20) { e = RuntimeException("layer $it", e) }
        assertFalse(e.isConnectivityFailure())
        assertFalse(e.isTimeoutFailure())
    }

    // ----- user messages -----

    @Test
    fun `transport failures get friendly text instead of ktor internals`() {
        val ktorish = SocketTimeoutException(
            "Socket timeout has expired [url=https://lichess.org/api/board/game/stream/x, socket_timeout=unknown] ms",
        )
        assertEquals(TIMEOUT_MESSAGE, ktorish.userMessage())
        assertEquals(OFFLINE_MESSAGE, UnknownHostException("lichess.org").userMessage())
        assertEquals(OFFLINE_MESSAGE, ConnectException("refused").userMessage())
    }

    @Test
    fun `http status errors get status-specific text`() {
        assertEquals(RATE_LIMITED_MESSAGE, LichessHttpException(429, "HTTP 429").userMessage())
        assertTrue(LichessHttpException(401, "HTTP 401").userMessage().contains("token"))
        assertTrue(LichessHttpException(500, "HTTP 500").userMessage().contains("trouble"))
        assertEquals("HTTP 418", LichessHttpException(418, "HTTP 418").userMessage())
    }

    @Test
    fun `non-transport errors keep their own message`() {
        assertEquals("Lichess said no", IllegalStateException("Lichess said no").userMessage())
        assertEquals("Something went wrong", IllegalStateException().userMessage())
        assertEquals("Something went wrong", IllegalStateException("   ").userMessage())
    }

    // ----- flap avoidance / state transitions -----

    @Test
    fun `a single failure does not go offline`() {
        val after = ConnectivityStatus().afterFailure(nowMillis = 100, message = OFFLINE_MESSAGE)
        assertTrue(after.online)
        assertTrue(after.degraded)
        assertEquals(1, after.consecutiveFailures)
    }

    @Test
    fun `the threshold-th consecutive failure goes offline and stamps since`() {
        var s = ConnectivityStatus()
        repeat(OFFLINE_AFTER_FAILURES) { i -> s = s.afterFailure(nowMillis = 100L + i, message = "x") }
        assertFalse(s.online)
        assertEquals(OFFLINE_AFTER_FAILURES, s.consecutiveFailures)
        assertEquals(100L + OFFLINE_AFTER_FAILURES - 1, s.sinceMillis)
    }

    @Test
    fun `further failures while offline do not move since`() {
        var s = ConnectivityStatus()
        repeat(OFFLINE_AFTER_FAILURES) { s = s.afterFailure(nowMillis = 100, message = "x") }
        val offlineSince = s.sinceMillis
        s = s.afterFailure(nowMillis = 9_999, message = "y")
        s = s.afterFailure(nowMillis = 20_000, message = "z")
        assertFalse(s.online)
        assertEquals(offlineSince, s.sinceMillis)
        assertEquals(OFFLINE_AFTER_FAILURES + 2, s.consecutiveFailures)
        assertEquals("z", s.lastError)
    }

    @Test
    fun `one success clears the failure streak without a state change`() {
        val degraded = ConnectivityStatus().afterFailure(nowMillis = 100, message = "x")
        val recovered = degraded.afterSuccess(nowMillis = 200)
        assertTrue(recovered.online)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(null, recovered.lastError)
        // Still online the whole time, so "since" must NOT be restamped.
        assertEquals(degraded.sinceMillis, recovered.sinceMillis)
    }

    @Test
    fun `coming back online stamps a new since`() {
        var s = ConnectivityStatus()
        repeat(OFFLINE_AFTER_FAILURES) { s = s.afterFailure(nowMillis = 100, message = "x") }
        val back = s.afterSuccess(nowMillis = 5_000)
        assertTrue(back.online)
        assertEquals(5_000L, back.sinceMillis)
        assertEquals(0, back.consecutiveFailures)
    }

    @Test
    fun `a steady success stream is identity`() {
        val online = ConnectivityStatus()
        assertTrue(online.afterSuccess(1) === online)
    }

    // ----- the singleton -----

    @BeforeTest
    fun setUp() {
        Connectivity.clock = { 1_000L }
        Connectivity.reset()
    }

    @AfterTest
    fun tearDown() {
        Connectivity.clock = { System.currentTimeMillis() }
        Connectivity.reset()
    }

    @Test
    fun `singleton ignores non-transport failures entirely`() {
        assertFalse(Connectivity.reportFailure(LichessHttpException(429, "HTTP 429")))
        assertFalse(Connectivity.reportFailure(IllegalStateException("nope")))
        assertTrue(Connectivity.isOnline)
        assertEquals(0, Connectivity.status.value.consecutiveFailures)
    }

    @Test
    fun `singleton goes offline only after the threshold and recovers on success`() {
        repeat(OFFLINE_AFTER_FAILURES - 1) {
            assertTrue(Connectivity.reportFailure(UnknownHostException("lichess.org")))
            assertTrue(Connectivity.isOnline)
        }
        assertTrue(Connectivity.reportFailure(UnknownHostException("lichess.org")))
        assertFalse(Connectivity.isOnline)
        assertEquals(OFFLINE_MESSAGE, Connectivity.status.value.lastError)

        Connectivity.reportSuccess()
        assertTrue(Connectivity.isOnline)
        assertEquals(0, Connectivity.status.value.consecutiveFailures)
    }

    @Test
    fun `reporting the same throwable twice counts once`() {
        // The response validator and safeAction both see the very same instance.
        val e = ConnectException("refused")
        repeat(OFFLINE_AFTER_FAILURES + 3) { assertTrue(Connectivity.reportFailure(e)) }
        assertTrue(Connectivity.isOnline)
        assertEquals(1, Connectivity.status.value.consecutiveFailures)
    }

    // ----- backoff -----

    @Test
    fun `backoff grows exponentially and is capped`() {
        val fixed = Random(1)
        val delays = (1..12).map { reconnectDelayMillis(it, cause = null, random = fixed) }
        // Within jitter bounds of 1s, 2s, 4s, … capped at RECONNECT_MAX_DELAY_MS.
        val expectedBase = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        expectedBase.forEachIndexed { i, base ->
            val d = delays[i]
            assertTrue(d >= base - base / 4, "attempt ${i + 1}: $d below jitter floor of $base")
            assertTrue(d <= base + base / 4, "attempt ${i + 1}: $d above jitter ceiling of $base")
        }
        delays.forEach { assertTrue(it <= RECONNECT_MAX_DELAY_MS + RECONNECT_MAX_DELAY_MS / 4) }
    }

    @Test
    fun `a 429 waits the minute Lichess asks for regardless of attempt`() {
        val cause = LichessHttpException(429, "HTTP 429")
        listOf(1, 2, 9).forEach { attempt ->
            val d = reconnectDelayMillis(attempt, cause, Random(7))
            assertTrue(d >= RATE_LIMIT_DELAY_MS - RATE_LIMIT_DELAY_MS / 4, "attempt $attempt: $d")
            assertTrue(d <= RATE_LIMIT_DELAY_MS + RATE_LIMIT_DELAY_MS / 4, "attempt $attempt: $d")
        }
    }

    @Test
    fun `jitter actually varies`() {
        val seen = (1..40).map { reconnectDelayMillis(5, null, Random(it)) }.toSet()
        assertTrue(seen.size > 1, "expected jittered delays, got $seen")
    }

    @Test
    fun `huge attempt numbers do not overflow into a negative delay`() {
        listOf(30, 64, 1_000, Int.MAX_VALUE).forEach { attempt ->
            val d = reconnectDelayMillis(attempt, null, Random(3))
            assertTrue(d > 0, "attempt $attempt gave $d")
            assertTrue(d <= RECONNECT_MAX_DELAY_MS + RECONNECT_MAX_DELAY_MS / 4, "attempt $attempt gave $d")
        }
    }

    // ----- retry policy -----

    @Test
    fun `dead-token and not-found responses are never retried`() {
        assertFalse(isRetryableStreamFailure(LichessHttpException(401, "HTTP 401")))
        assertFalse(isRetryableStreamFailure(LichessHttpException(403, "HTTP 403")))
        assertFalse(isRetryableStreamFailure(LichessHttpException(404, "HTTP 404")))
        assertFalse(isRetryableStreamFailure(LichessHttpException(400, "HTTP 400")))
    }

    @Test
    fun `transient failures are retried and cancellation is not`() {
        assertTrue(isRetryableStreamFailure(LichessHttpException(429, "HTTP 429")))
        assertTrue(isRetryableStreamFailure(LichessHttpException(502, "HTTP 502")))
        assertTrue(isRetryableStreamFailure(SocketTimeoutException("stalled")))
        assertTrue(isRetryableStreamFailure(UnknownHostException("lichess.org")))
        assertFalse(isRetryableStreamFailure(CancellationException("screen hidden")))
    }
}
