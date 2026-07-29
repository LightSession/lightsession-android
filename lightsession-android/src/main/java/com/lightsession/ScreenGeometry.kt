package com.lightsession

import android.content.res.Resources

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
 * was. Every heatmap built so far is shifted down by that difference — 1.3% of the height, always
 * in the same direction, which is why it reads as a plausible picture rather than as a fault.
 *
 * The rule this encodes: **coordinates and captures must be expressed in the same space, and that
 * space is whatever the captured image is.** The image is the display, so the display it is.
 *
 * ## What this is not for
 *
 * Layout. An app that wants to know how much room it has should ask its own Activity — that is a
 * different question and `activity.resources` is the right answer to it. This is only for the
 * numbers that have to line up with a recorded pixel.
 */
internal object ScreenGeometry {

    /**
     * The display, in pixels.
     *
     * Read on each call rather than cached: rotation changes it, and a cached copy is how a
     * landscape session ends up described in portrait numbers.
     */
    val width: Int
        get() = Resources.getSystem().displayMetrics.widthPixels

    val height: Int
        get() = Resources.getSystem().displayMetrics.heightPixels

    /** Pixel density, for anything sizing against physical millimetres. */
    val density: Float
        get() = Resources.getSystem().displayMetrics.density

    /** True while the display is wider than it is tall. */
    val isLandscape: Boolean
        get() = width > height
}
