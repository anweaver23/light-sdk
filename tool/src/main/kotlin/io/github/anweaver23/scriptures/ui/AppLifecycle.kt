package io.github.anweaver23.scriptures.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is in the foreground. Players connect only while it is: a page opened just after
 * the app paused (the launch-time "where you left off" can land then, when the phone's screen is off)
 * once bound the audio service after the pause that should have released it, and the activity's end
 * crashed on that leaked connection (media3 "Service not registered").
 *
 * The SDK blocks Android's lifecycle owner, so this is read off the screen callbacks ([AppScreen],
 * [AppLightScreen]): the app pausing calls the current screen's pause; resuming shows the current
 * screen again *without* hiding another first, while navigating always hides one screen, then shows
 * the next.
 */
object AppLifecycle {
    private val _resumed = MutableStateFlow(false)
    val resumed: StateFlow<Boolean> = _resumed.asStateFlow()

    private var navigating = false

    /**
     * Also forgets a hide that no show followed: back from home hides it and closes the app, and that
     * stale hide once made the next launch's first show look like navigation, so the app never counted
     * as resumed and no player could connect.
     */
    internal fun paused() {
        _resumed.value = false
        navigating = false
    }

    /** A new activity starts with nothing half navigated. */
    internal fun newActivity() {
        navigating = false
    }

    internal fun hiding() {
        navigating = true
    }

    internal fun showing() {
        if (!navigating) _resumed.value = true
        navigating = false
    }
}
