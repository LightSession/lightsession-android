package com.lightsession.mapper

import org.json.JSONArray
import org.json.JSONObject

/**
 * A screen described as geometry, for the server to draw.
 *
 * This is the alternative to encoding a wireframe bitmap on the device. The SDK used
 * to walk the view hierarchy, draw coloured rectangles onto a full-resolution
 * `Bitmap`, JPEG-encode it and upload the result — measured at **8.25 ms and 80 KB**
 * on a 1080×2400 frame. The rectangles that produced that picture are about **3 KB**
 * of JSON, and the encode disappears: the server draws the same wireframe from them.
 *
 * That is the SDK's whole premise applied to the one place it was being violated. A
 * session-replay library competes on how little it costs the host app, and paying a
 * full image encode to deliver a picture of rectangles is the opposite of that.
 *
 * The shape of the payload is deliberately flat and terse:
 *
 *  * **Flat, in paint order.** The device's hierarchy is a tree, but the only thing
 *    the drawing needs from it is *what covers what* — so the tree is flattened
 *    pre-order and the server paints in sequence. No nesting to encode, no depth to
 *    recurse on, and a child cannot be drawn under its parent by accident.
 *  * **One-letter keys.** There is one object per widget per screen. `l/t/r/b` is the
 *    same order as [android.graphics.Rect].
 *  * **Colours from the device.** The device is the only side that knows the app's
 *    theme; a dark-mode screen drawn with a server's light-mode defaults is a
 *    wireframe of a screen that does not exist.
 *
 * It also cannot leak screen content, because there is none in it — no text, no
 * pixels, nothing rendered. That makes it safe by construction rather than safe by
 * policy, which is a different and stronger property than masking a real screenshot.
 *
 * The server-side counterpart is `ls_media::skeleton`.
 */
data class SkeletonFrame(
    val width: Int,
    val height: Int,
    /** ARGB, from the activity theme's `windowBackground`. */
    val background: Int,
    /** Pre-order: parents before children, so painting in sequence layers correctly. */
    val rects: List<SkeletonRect>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("width", width)
        put("height", height)
        put("background", colorToHex(background))
        put("nodes", JSONArray().also { array ->
            rects.forEach { array.put(it.toJson()) }
        })
    }

    companion object {
        private val HEX = "0123456789ABCDEF".toCharArray()

        /**
         * `#RRGGBB` when opaque, `#AARRGGBB` otherwise.
         *
         * Android's channel order, which is *not* CSS's `#RRGGBBAA` — reading one as
         * the other silently swaps a colour's alpha with its red, so the server
         * parses this order explicitly.
         *
         * Bit arithmetic rather than [android.graphics.Color]'s accessors, so this
         * stays a plain function: `android.graphics` is a stub on the JVM and calling
         * into it would make this untestable outside a device.
         *
         * Written by hand into a `CharArray` rather than with `String.format`, which is
         * what it used to use. This runs once per widget per screen, and `String.format`
         * parses its format string and drags in `Formatter` and locale machinery every
         * call — `SkeletonCostTest` measured the serialisation of a 117-widget screen at
         * 9.0 ms, almost all of it here, against a hierarchy walk of 0.62 ms. Formatting
         * a colour has no business costing more than finding the widgets.
         */
        internal fun colorToHex(color: Int): String {
            val alpha = (color ushr 24) and 0xFF
            // Two bytes per widget saved on the common case, and it exercises the
            // shorter form the server also has to accept.
            val opaque = alpha == 0xFF
            val out = CharArray(if (opaque) 7 else 9)
            out[0] = '#'
            var at = 1
            if (!opaque) {
                out[at++] = HEX[(alpha ushr 4) and 0xF]
                out[at++] = HEX[alpha and 0xF]
            }
            // Bit 20 down to 0 in steps of four: the six nibbles of RGB, high first.
            var shift = 20
            while (shift >= 0) {
                out[at++] = HEX[(color ushr shift) and 0xF]
                shift -= 4
            }
            return String(out)
        }
    }
}

/** One widget, in screen pixels. */
data class SkeletonRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** Matches `ls_media::skeleton::NodeKind`; unknown names degrade there, not here. */
    val kind: String,
    val color: Int,
    /** Outline rather than fill. A filled container hides everything inside it. */
    val stroke: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("l", left)
        put("t", top)
        put("r", right)
        put("b", bottom)
        put("kind", kind)
        put("color", SkeletonFrame.colorToHex(color))
        // Omitted when false, which is most nodes. The server defaults it.
        if (stroke) put("stroke", true)
    }
}
