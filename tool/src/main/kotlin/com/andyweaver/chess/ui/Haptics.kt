package com.andyweaver.chess.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LocalHapticsEnabled

/**
 * Whether this app supplies its OWN tap haptics when LightOS's are unavailable.
 *
 * WHY THIS EXISTS. Nothing in the app buzzed on a real phone, and the cause is a chain
 * entirely outside our control:
 *
 *   a self-signed sideloaded APK
 *     -> its signing cert reads as ClientCertType.Unknown
 *     -> LightSdkService.verifyCallerIsInstalledClient denies a service token
 *        (the default clientFilterLevel, AllowLightApprovedApks, demands a Light-signed cert)
 *     -> GetUserPreferences fails
 *     -> LightHapticsManager leaves its cache at the `false` it was initialised with
 *     -> LocalHapticsEnabled stays false
 *     -> every Modifier.lightClickable haptic, and our own tapHaptic, is suppressed.
 *
 * Only Compose's built-in long-press haptic escapes, because it is the one thing not gated
 * on that flag — which is exactly why a long press was the single piece of feedback that
 * could be felt. So the motor and the Compose haptic path both work fine; only the gate is
 * the problem, and it cannot be opened from inside a tool. `LightHapticFeedback` is no help
 * either: its only entry point needs an Android `Context`, which tool code cannot legally
 * obtain (`LocalContext`, `android.content.Context` and `getSystemService(` are all rejected
 * by the plugin's source scan, and `SealedLightContext.androidContext` is internal to the
 * SDK). Checked against SDK v0.1.1 — none of the 23 commits in that release touched any
 * link in the chain.
 *
 * THE TRADE-OFF, stated plainly. `LocalHapticsEnabled` carries two different meanings at
 * once — "the user turned haptics off" and "we never got an answer" — and the SDK collapses
 * both to `false`, so they are genuinely indistinguishable from here. Overriding it
 * therefore also overrides a user who deliberately turned haptics off. That is why this is
 * an explicit app setting rather than unconditional: turning it off restores strict
 * deference to LightOS.
 *
 * Global rather than threaded through screens because it mirrors a device-level preference,
 * not per-screen state: one value, read by every tap target in the app. Kept in sync by the
 * settings collectors in HomeScreenViewModel (alive for as long as the app, since Home is
 * the root screen) and SettingsViewModel (so the toggle takes effect on the spot).
 */
object AppHaptics {
    var fallbackEnabled by mutableStateOf(true)
}

/**
 * [LightTheme] plus this app's haptics policy — the wrapper every screen's `Content()` uses
 * in place of `LightTheme` directly.
 *
 * All it adds is re-providing [LocalHapticsEnabled] as `SDK value OR our fallback`, which is
 * what makes haptics reach EVERY tap target rather than only the ones we hand-wire: the SDK's
 * own `lightClickable` and `LightBarButton` read that same composition local, so the top-bar
 * chevron, the gear/plus/history buttons and every row all start responding without a single
 * call-site change. Our `Modifier.tapHaptic` on board squares reads it too.
 *
 * OR, not replace: when the SDK does supply a working haptic the SDK's own one fires and ours
 * stays out of the way, so a Light-signed build never double-buzzes.
 */
@Composable
fun ChessTheme(colors: LightColors, content: @Composable () -> Unit) {
    val sdkHaptics = LocalHapticsEnabled.current
    CompositionLocalProvider(
        LocalHapticsEnabled provides (sdkHaptics || AppHaptics.fallbackEnabled),
    ) {
        LightTheme(colors = colors) { content() }
    }
}
