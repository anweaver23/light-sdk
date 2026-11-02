package io.github.anweaver23.scriptures.ui

import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import io.github.anweaver23.scriptures.core.BackStack

/**
 * The app's copy of the SDK back stack, so going somewhere that's already open can close the loop:
 * [open] pops back to that screen instead of stacking a second copy (bookmark → verse → bookmarks →
 * verse → … stays Home › Bookmarks › verse). The SDK only pushes and pops, and its stack is private,
 * so every screen registers itself by extending [AppScreen] or [AppLightScreen]. Main thread only.
 */
object Nav {
    private val stack = ArrayList<SimpleLightScreen<*>>()

    /** A screen was built; it's about to be pushed. The initial screen ([HomeScreen]) starts over. */
    internal fun created(screen: SimpleLightScreen<*>, root: Boolean) {
        if (root) {
            stack.clear()
            AppLifecycle.newActivity()
        }
        stack += screen
    }

    /** A screen came to the top: anything still listed above it is gone. */
    internal fun shown(screen: SimpleLightScreen<*>) {
        val i = stack.indexOf(screen)
        if (i < 0) stack += screen else while (stack.lastIndex > i) stack.removeAt(stack.lastIndex)
    }

    internal fun destroyed(screen: SimpleLightScreen<*>) {
        stack.remove(screen)
    }

    val top: SimpleLightScreen<*>? get() = stack.lastOrNull()

    /**
     * Pops back to the newest screen whose [Keyed.navKey] is [key] and returns it, or returns null
     * when there's none (nothing is popped then). Stops early, returning null, if a screen won't go
     * back (a picker stepping up a level).
     */
    fun popTo(key: Any): SimpleLightScreen<*>? {
        val keys = stack.map { (it as? Keyed)?.navKey }
        val pops = BackStack.popsTo(keys, key) ?: return null
        val target = stack[stack.lastIndex - pops]
        while (stack.lastOrNull() !== target) {
            val current = stack.lastOrNull() ?: return null
            current.goBack()
            if (stack.lastOrNull() === current) return null
        }
        return target
    }
}

/** A screen that's one place: going there again returns to it instead of opening another copy. */
interface Keyed {
    /** Where this screen is now (it can change, e.g. the reader turning chapters); null = nowhere in particular. */
    val navKey: Any?
}

/** The places [open] closes loops on. */
object NavKeys {
    data object Bookmarks
    data object Annotations
    data object Settings

    data class Page(val id: String)

    /** An imported page ("tg/faith"), or a list of them ("list:tg"). */
    fun page(id: String): Any = Page(id)
}

/**
 * Goes to [key]: back to it if it's already open (then [revisit] updates it, e.g. a verse to show),
 * otherwise opens [factory] on top.
 */
inline fun <T, reified S : SimpleLightScreen<T>> SimpleLightScreen<*>.open(
    key: Any,
    noinline factory: (SealedLightActivity) -> S,
    revisit: (S) -> Unit = {},
) {
    val existing = Nav.popTo(key)
    if (existing is S) revisit(existing) else navigateTo(factory)
}

/** A [SimpleLightScreen] that keeps [Nav] informed. */
abstract class AppScreen<R>(sealedActivity: SealedLightActivity) : SimpleLightScreen<R>(sealedActivity) {
    init {
        Nav.created(this, root = false)
    }

    override fun willShow() {
        AppLifecycle.showing()
        super.willShow()
        Nav.shown(this)
    }

    override fun willHide() {
        AppLifecycle.hiding()
        super.willHide()
    }

    override fun onAppPause() {
        AppLifecycle.paused()
        super.onAppPause()
    }

    override fun onScreenDestroy() {
        Nav.destroyed(this)
        super.onScreenDestroy()
    }
}

/** A [LightScreen] that keeps [Nav] informed. [root] is the app's initial screen. */
abstract class AppLightScreen<R, VM : LightViewModel<R>>(sealedActivity: SealedLightActivity, root: Boolean = false) :
    LightScreen<R, VM>(sealedActivity) {
    init {
        Nav.created(this, root)
    }

    override fun willShow() {
        AppLifecycle.showing()
        super.willShow()
        Nav.shown(this)
    }

    override fun willHide() {
        AppLifecycle.hiding()
        super.willHide()
    }

    override fun onAppPause() {
        AppLifecycle.paused()
        super.onAppPause()
    }

    override fun onScreenDestroy() {
        Nav.destroyed(this)
        super.onScreenDestroy()
    }
}
