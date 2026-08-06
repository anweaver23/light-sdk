package com.andyweaver.chess.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [reconcileSeekState] — the pure core of `ChessSettings.reconcileSeeks`.
 *
 * The behaviour under test is the abort asymmetry: Lichess re-queues a correspondence seek
 * when the OPPONENT aborts the game it produced, but not when you abort it yourself, so
 * only the former should revive the local `PendingSeek` marker.
 */
class SeekReconcileTest {

    private val now = 1_000_000L
    private val ttl = 7L * 24 * 60 * 60 * 1000
    private val cap = 50

    private fun seek(id: String, createdAt: Long = 0) =
        PendingSeek(id = id, days = 3, rated = false, side = "random", createdAt = createdAt)

    private fun reconcile(
        state: SeekReconcileState,
        currentGames: Map<String, Int?>,
        at: Long = now,
        maxSelfAborts: Int = cap,
        ttlMs: Long = ttl,
    ) = reconcileSeekState(
        state = state,
        currentGames = currentGames,
        now = at,
        maxSelfAborts = maxSelfAborts,
        selfAbortTtlMs = ttlMs,
        newSeekId = { "revived" },
    )

    @Test
    fun `first observation only records a baseline`() {
        val out = reconcile(
            SeekReconcileState(pendingSeeks = listOf(seek("s1")), knownGames = null),
            mapOf("g1" to 0),
        )
        assertEquals(setOf("g1"), out.knownGames)
        assertEquals(listOf(seek("s1")), out.pendingSeeks)
        assertTrue(out.matches.isEmpty())
    }

    @Test
    fun `a newly appeared game consumes the oldest pending seek as a match`() {
        val out = reconcile(
            SeekReconcileState(pendingSeeks = listOf(seek("s1")), knownGames = emptySet()),
            mapOf("g1" to 0),
        )
        assertTrue(out.pendingSeeks.isEmpty())
        assertEquals(1, out.matches.size)
        assertEquals("g1", out.matches.single().gameId)
        assertEquals("s1", out.matches.single().seek.id)
    }

    @Test
    fun `opponent abort under two plies revives the seek`() {
        val matched = SeekMatch(gameId = "g1", seek = seek("s1"), lastObservedPly = 0)
        val out = reconcile(
            SeekReconcileState(matches = listOf(matched), knownGames = setOf("g1")),
            emptyMap(),
        )
        assertEquals(1, out.pendingSeeks.size)
        val revived = out.pendingSeeks.single()
        assertEquals("revived", revived.id)
        assertEquals(now, revived.createdAt)
        // Same terms as the original seek.
        assertEquals(3, revived.days)
        assertEquals("random", revived.side)
        assertTrue(out.matches.isEmpty())
    }

    @Test
    fun `self abort under two plies does not revive the seek`() {
        val matched = SeekMatch(gameId = "g1", seek = seek("s1"), lastObservedPly = 0)
        val out = reconcile(
            SeekReconcileState(
                matches = listOf(matched),
                knownGames = setOf("g1"),
                selfAborts = listOf(SelfAbort("g1", now - 5_000)),
            ),
            emptyMap(),
        )
        assertTrue(out.pendingSeeks.isEmpty(), "self-aborted game must not re-queue a seek")
        assertTrue(out.matches.isEmpty())
        // The record is consumed once its game is gone.
        assertTrue(out.selfAborts.isEmpty())
    }

    @Test
    fun `a self abort record for another game does not suppress an unrelated revival`() {
        val matched = SeekMatch(gameId = "g1", seek = seek("s1"), lastObservedPly = 0)
        val out = reconcile(
            SeekReconcileState(
                matches = listOf(matched),
                knownGames = setOf("g1"),
                selfAborts = listOf(SelfAbort("other", now - 5_000)),
            ),
            emptyMap(),
        )
        assertEquals(1, out.pendingSeeks.size)
        // Unrelated record survives (its game never appeared/disappeared).
        assertEquals(listOf("other"), out.selfAborts.map { it.gameId })
    }

    @Test
    fun `a normally completed game does not revive its seek`() {
        // Seen with real moves, then disappears (resign / checkmate / draw).
        val seen = reconcile(
            SeekReconcileState(matches = listOf(SeekMatch("g1", seek("s1"), 0)), knownGames = setOf("g1")),
            mapOf("g1" to 24),
        )
        assertEquals(24, seen.matches.single().lastObservedPly)

        val gone = reconcile(seen, emptyMap())
        assertTrue(gone.pendingSeeks.isEmpty(), "a finished game must not revive a seek")
        assertTrue(gone.matches.isEmpty())
    }

    @Test
    fun `ply high water mark survives a poll that could not determine the ply`() {
        val seen = reconcile(
            SeekReconcileState(matches = listOf(SeekMatch("g1", seek("s1"), 6)), knownGames = setOf("g1")),
            mapOf("g1" to null),
        )
        assertEquals(6, seen.matches.single().lastObservedPly)
        assertTrue(reconcile(seen, emptyMap()).pendingSeeks.isEmpty())
    }

    @Test
    fun `self abort records are pruned by age`() {
        val fresh = SelfAbort("fresh", now - 1_000)
        val stale = SelfAbort("stale", now - ttl - 1)
        val out = reconcile(
            SeekReconcileState(knownGames = setOf("g1"), selfAborts = listOf(fresh, stale)),
            mapOf("g1" to 0),
        )
        assertEquals(listOf("fresh"), out.selfAborts.map { it.gameId })
    }

    @Test
    fun `self abort records are capped, keeping the newest`() {
        val aborts = (1..10).map { SelfAbort("g$it", now - it * 1_000L) }
        val out = reconcile(
            SeekReconcileState(knownGames = setOf("x"), selfAborts = aborts),
            mapOf("x" to 0),
            maxSelfAborts = 3,
        )
        assertEquals(listOf("g1", "g2", "g3"), out.selfAborts.map { it.gameId })
    }

    @Test
    fun `pruneSelfAborts is order-independent and bounded`() {
        val aborts = listOf(SelfAbort("a", now - 5), SelfAbort("b", now - 1), SelfAbort("c", now - 3))
        assertEquals(
            listOf("b", "c"),
            pruneSelfAborts(aborts, now, maxSelfAborts = 2, selfAbortTtlMs = ttl).map { it.gameId },
        )
    }

    @Test
    fun `abort then requeue end to end`() {
        // 1. seek pending, nothing known yet
        var state = SeekReconcileState(pendingSeeks = listOf(seek("s1")), knownGames = emptySet())
        // 2. a game appears from it
        state = reconcile(state, mapOf("g1" to 0))
        assertTrue(state.pendingSeeks.isEmpty())
        // 3. the OPPONENT aborts it -> marker comes back
        state = reconcile(state, emptyMap())
        assertEquals(1, state.pendingSeeks.size)
        // 4. the re-queued seek later matches a different game -> marker clears again
        state = reconcile(state, mapOf("g2" to 0))
        assertTrue(state.pendingSeeks.isEmpty())
        assertEquals("g2", state.matches.single().gameId)
    }
}
