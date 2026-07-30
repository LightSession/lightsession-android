package com.lightsession

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Size
import android.view.Display

/**
 * The one answer to "how big is the screen", for everything that has to agree about it.
 *
 * ## Why this exists
 *
 * The SDK used to ask three different objects, and they gave three different answers:
 *
 *  * `activity.resources.displayMetrics` — the area an Activity is laid out in, which on a phone
 *    with system bars is **2337** of a 2400-pixel display. This sized the composite screen id and
 *    the `width`/`height` recorded against a capture.
 *  * `applicationContext.resources.displayMetrics` — the same family of answer, and it is what
 *    travelled in `device_info` as `screenWidth`/`screenHeight`. The server divides raw touch
 *    pixels by it to get the 0..1 coordinates every heatmap is built from.
 *  * `Resources.getSystem().displayMetrics` — the display, **2400**, which is what actually sizes
 *    the captured bitmap.
 *
 * So a capture row said 1080×2337 while the JPEG it pointed at was 1080×2400, and its own object
 * key said `1080x2337_light.jpg`. Measured, not inferred: the stored image for a screen recorded as
 * 2337 decodes to 2400, with the bottom 63 pixels black where the view hierarchy does not reach.
 *
 * ## Why the display wins
 *
 * A `MotionEvent` reports coordinates relative to the window, and the window starts at the display
 * origin — `mAppBounds=Rect(0, 0 - 1080, 2400)` on the device this was measured on. So a touch at
 * the bottom of the visible app area arrives as `y ≈ 2337`.
 *
 * Divided by 2337 that is 1.0, and drawn on the 2400-pixel image it lands at 2400: in the black
 * strip, below everything. Divided by 2400 it is 0.974 and lands at 2337, which is where the finger
 * was. Every heatmap built before this was shifted down by that difference — 1.3% of the height,
 * always in the same direction, which is why it read as a plausible picture rather than as a fault.
 *
 * The rule this encodes: **coordinates and captures must be expressed in the same space, and that
 * space is whatever the captured image is.** The image is the display, so the display it is.
 *
 * ## Why not `Resources.getSystem()`, which is the obvious way to ask
 *
 * It reports the display correctly, rotation included. It is avoided for a narrower reason: the
 * object is not ours.
 *
 * `getDisplayMetrics()` hands back the framework's **live** instance, not a copy, and both
 * `Display.getRealMetrics` and `Display.getMetrics` *write into* the metrics they are given. So any
 * code anywhere in the host process — ours, the client's, or a library either depends on — that
 * passes the shared instance to one of those rewrites it for every reader in the process. An SDK
 * embedded in someone else's app cannot rule that out, and the failure would be silent and remote
 * from its cause.
 *
 * So the display is asked directly, through a [Display] handle obtained from [DisplayManager], into
 * a metrics object this class owns. Same answer, no shared state.
 *
 * This is hardening, not a bug fix. It replaced no observed fault: a run that looked like the global
 * flipping between `1080x2400` and `2400x1080` on a still device turned out to be a device with
 * auto-rotate on that really did turn — `dumpsys` was read minutes later, at `rotation=0`, and a
 * snapshot of the present says nothing about the past. The captures it produced were correct.
 *
 * ## What this is not for
 *
 * Layout. An app that wants to know how much room it has should ask its own Activity — that is a
 * different question and `activity.resources` is the right answer to it. This is only for the
 * numbers that have to line up with a recorded pixel.
 */
internal object ScreenGeometry {

    /**
     * The default display, held from [attach].
     *
     * A `Display` handle stays valid across rotations — it is a reference to the display, not to a
     * description of it, so re-querying `DisplayManager` on every read would buy nothing.
     */
    @Volatile
    private var display: Display? = null

    /**
     * Called once from `LightSession.init`, before anything can capture.
     *
     * Every reader below runs after initialisation, so the un-attached fallback should never be
     * reached in a real app; it exists so that a unit test or a misordered call degrades to a
     * plausible number instead of throwing.
     */
    fun attach(context: Context) {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
    }

    /**
     * The display, in pixels, in its current rotation.
     *
     * Read as a pair rather than as two properties so that a rotation landing between two reads
     * cannot produce a size that was never real — `2400 × 2400` is not a mistake any single call
     * can make. This device rotates in practice, so that window is not hypothetical.
     *
     * `getRealMetrics` is deprecated in favour of `WindowMetrics`, and is still what this uses. The
     * replacements answer a different question: `currentWindowMetrics` reports the area *this app's
     * window* occupies, which is not the display in split-screen, and `maximumWindowMetrics`
     * reports the largest area the app may ever occupy, which is not promised to follow the current
     * rotation. Only `getRealMetrics` says "the whole display, as it is turned right now", which is
     * the space the captured bitmap lives in.
     */
    @Suppress("DEPRECATION")
    fun size(): Size {
        val metrics = DisplayMetrics()
        val handle = display
        if (handle != null) {
            handle.getRealMetrics(metrics)
        } else {
            metrics.setTo(Resources.getSystem().displayMetrics)
        }
        return Size(metrics.widthPixels, metrics.heightPixels)
    }

    /** Convenience for the callers that genuinely need one side. Prefer [size] for both. */
    val width: Int
        get() = size().width

    val height: Int
        get() = size().height

    /** Pixel density, for anything sizing against physical millimetres. */
    @Suppress("DEPRECATION")
    val density: Float
        get() {
            val handle = display ?: return Resources.getSystem().displayMetrics.density
            val metrics = DisplayMetrics()
            handle.getRealMetrics(metrics)
            return metrics.density
        }

    /** True while the display is wider than it is tall. */
    val isLandscape: Boolean
        get() = size().let { it.width > it.height }
}
