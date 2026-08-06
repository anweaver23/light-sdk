package com.andyweaver.chess.newgame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure rules behind the new-game screen: the rating-band -> `ratingRange`
 * conversion and the rated/variant restriction. Both feed request parameters
 * Lichess rejects with a hard 400 when they're wrong, so they're worth pinning
 * down without an emulator.
 */
class NewGameRulesTest {

    // ----- ratingRangeFor -----

    @Test
    fun `none band sends no filter`() {
        assertNull(ratingRangeFor(RatingBand.NONE, 1500))
    }

    @Test
    fun `unknown own rating sends no filter`() {
        // A brand-new account has no perfs.correspondence, and the fetch can fail.
        assertNull(ratingRangeFor(RatingBand.WITHIN_200, null))
        assertNull(ratingRangeFor(RatingBand.NONE, null))
    }

    @Test
    fun `band centres the range on your own rating`() {
        assertEquals("1400-1600", ratingRangeFor(RatingBand.WITHIN_100, 1500))
        assertEquals("1300-1700", ratingRangeFor(RatingBand.WITHIN_200, 1500))
        assertEquals("1200-1800", ratingRangeFor(RatingBand.WITHIN_300, 1500))
        assertEquals("1000-2000", ratingRangeFor(RatingBand.WITHIN_500, 1500))
    }

    @Test
    fun `bounds clamp to the api's meaningful window`() {
        assertEquals("400-950", ratingRangeFor(RatingBand.WITHIN_500, 450))
        assertEquals("2300-2900", ratingRangeFor(RatingBand.WITHIN_500, 2800))
        assertEquals("2400-2900", ratingRangeFor(RatingBand.WITHIN_500, 2900))
    }

    @Test
    fun `min is always strictly below max`() {
        // "1500-1500" is an HTTP 400, so a collapsed range must never be emitted —
        // including for absurd ratings outside the clamp window entirely.
        val ratings = listOf(-500, 0, 300, 399, 400, 401, 1500, 2899, 2900, 2901, 5000)
        for (band in RatingBand.entries) {
            for (rating in ratings) {
                val range = ratingRangeFor(band, rating) ?: continue
                val (min, max) = range.split("-").map { it.toInt() }
                assertTrue(min < max, "band=$band rating=$rating produced $range")
                assertTrue(min >= RATING_MIN, "band=$band rating=$rating produced $range")
                assertTrue(max <= RATING_MAX, "band=$band rating=$rating produced $range")
            }
        }
    }

    @Test
    fun `range is always the plain min-max form`() {
        val range = assertNotNull(ratingRangeFor(RatingBand.WITHIN_100, 1500))
        assertTrue(Regex("""^\d+-\d+$""").matches(range), "unexpected shape: $range")
    }

    @Test
    fun `none is the default band`() {
        assertEquals(RatingBand.NONE, RatingBand.entries.first())
        assertEquals(RatingBand.NONE, NewGameViewModel.Options().ratingBand)
    }

    // ----- ratedAllowed -----

    @Test
    fun `only standard can be rated`() {
        assertTrue(ratedAllowed("standard"))
        for (variant in NewGameViewModel.Variant.entries) {
            val expected = variant == NewGameViewModel.Variant.STANDARD
            assertEquals(expected, ratedAllowed(variant.apiValue), "variant=$variant")
        }
    }

    @Test
    fun `no variant other than standard is rateable`() {
        assertFalse(ratedAllowed("horde"))
        assertFalse(ratedAllowed("crazyhouse"))
        assertFalse(ratedAllowed("racingKings"))
        // An unknown future key is treated as non-standard: casual always works.
        assertFalse(ratedAllowed("someFutureVariant"))
    }
}
