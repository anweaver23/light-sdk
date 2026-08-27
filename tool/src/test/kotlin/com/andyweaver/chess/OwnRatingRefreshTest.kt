package com.andyweaver.chess

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [aGameEnded] decides when the home screen re-reads our own correspondence rating.
 *
 * Worth testing directly rather than through the screen: the failure it guards against is
 * silent in both directions. Too eager and every refresh spends an extra request; too shy
 * and the rating beside the "Chess" title never moves, which is the bug this fixed — it
 * only ever updated on an account switch, because that rebuilds the client.
 */
class OwnRatingRefreshTest {

    @Test
    fun `no baseline is not an ending`() {
        // The first fetch after login: every id is unfamiliar, but nothing has finished.
        assertFalse(aGameEnded(null, setOf("a", "b")))
        assertFalse(aGameEnded(null, emptySet()))
    }

    @Test
    fun `a game leaving the list is an ending`() {
        assertTrue(aGameEnded(setOf("a", "b"), setOf("a")))
        assertTrue(aGameEnded(setOf("a"), emptySet()))
    }

    @Test
    fun `an unchanged list is not an ending`() {
        assertFalse(aGameEnded(setOf("a", "b"), setOf("a", "b")))
        assertFalse(aGameEnded(emptySet(), emptySet()))
    }

    @Test
    fun `a game appearing is not an ending`() {
        // Accepting a challenge or a seek matching adds a game; no rating can change.
        assertFalse(aGameEnded(setOf("a"), setOf("a", "b")))
        assertFalse(aGameEnded(emptySet(), setOf("a")))
    }

    @Test
    fun `one ending alongside one start still counts`() {
        // Both in the same refresh — a finished game must not be masked by a new one, which
        // a bare size comparison would have missed.
        assertTrue(aGameEnded(setOf("a", "b"), setOf("a", "c")))
    }

    @Test
    fun `every game ending at once counts`() {
        assertTrue(aGameEnded(setOf("a", "b", "c"), emptySet()))
    }
}
