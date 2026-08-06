package com.andyweaver.chess.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class MoveStepSpeedTest {

    @Test
    fun `default is normal`() {
        assertEquals(MoveStepSpeed.NORMAL, MoveStepSpeed.DEFAULT)
        assertEquals(MOVE_STEP_INTERVAL_NORMAL_MS, MoveStepSpeed.DEFAULT.intervalMs)
    }

    @Test
    fun `keys round trip`() {
        for (speed in MoveStepSpeed.entries) {
            assertEquals(speed, MoveStepSpeed.fromKey(speed.key))
        }
    }

    @Test
    fun `unknown or missing key falls back to the default`() {
        assertEquals(MoveStepSpeed.DEFAULT, MoveStepSpeed.fromKey(null))
        assertEquals(MoveStepSpeed.DEFAULT, MoveStepSpeed.fromKey(""))
        assertEquals(MoveStepSpeed.DEFAULT, MoveStepSpeed.fromKey("blistering"))
        // Enum NAMES are not accepted as keys — persistence is by the stable lowercase key.
        assertEquals(MoveStepSpeed.DEFAULT, MoveStepSpeed.fromKey("SLOW"))
    }

    @Test
    fun `next cycles and wraps`() {
        assertEquals(MoveStepSpeed.NORMAL, MoveStepSpeed.SLOW.next)
        assertEquals(MoveStepSpeed.FAST, MoveStepSpeed.NORMAL.next)
        assertEquals(MoveStepSpeed.SLOW, MoveStepSpeed.FAST.next)
    }

    @Test
    fun `intervals decrease from slow to fast`() {
        val intervals = MoveStepSpeed.entries.map { it.intervalMs }
        assertEquals(intervals.sortedDescending(), intervals)
        assertEquals(listOf("Slow", "Normal", "Fast"), MoveStepSpeed.entries.map { it.label })
    }
}
