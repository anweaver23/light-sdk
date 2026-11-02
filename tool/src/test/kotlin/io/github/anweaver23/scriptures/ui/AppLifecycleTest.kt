package io.github.anweaver23.scriptures.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The callback sequences LightActivity produces, and what they must mean. */
class AppLifecycleTest {
    private val resumed get() = AppLifecycle.resumed.value

    private fun launch() {
        AppLifecycle.newActivity()
        AppLifecycle.showing() // onResume shows the initial screen
    }

    private fun navigate() {
        AppLifecycle.hiding()
        AppLifecycle.showing()
    }

    @Test
    fun `launch and navigation keep the app in the foreground`() {
        launch()
        assertTrue(resumed)
        navigate()
        assertTrue(resumed)
    }

    @Test
    fun `a page opened while paused does not count as a return`() {
        launch()
        AppLifecycle.paused()
        navigate()
        assertFalse(resumed)
        AppLifecycle.showing() // onResume
        assertTrue(resumed)
    }

    @Test
    fun `back out of home, then reopen`() {
        launch()
        AppLifecycle.hiding() // back on home: hidden, the activity finishes
        AppLifecycle.paused()
        launch() // same process, new activity
        assertTrue(resumed)
    }
}
