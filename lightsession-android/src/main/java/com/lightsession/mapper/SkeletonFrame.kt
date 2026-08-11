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
 * ## What it can and cannot say
 *
 * There is no text in it, no glyph, nothing rendered — so it cannot reproduce what a screen
 * said. What it does carry, when `LightSessionConfig.trueColourWireframes` is on, is one colour
 * per widget, sampled from the screen. That is a real weakening of a claim this comment used to
 * make unconditionally, and worth stating plainly rather than leaving for someone to discover:
 *
 *  * With colours off, the payload is pure geometry and can leak nothing at all.
 *  * With colours on, a rectangle's thousands of pixels become three numbers. The reduction is
 *    irreversible — no glyph survives an average, and the dominant colour of a text block is its
 *    background, so text is discarded rather than smeared. But the guarantee now rests on the
 *    rectangles being coarse: one colour for a paragraph says nothing, one colour per letter
 *    would spell it out. `Recolour.glyphSizedRects` is the tripwire for that, and the sampling
 *    refuses to run when it fires.
 *
 * "Safe because the rectangles describe widgets" is a weaker property than "safe because there
 * is nothing in it", and it is the one that actually holds.
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
        // Wire version. 2 says two things. Rects may carry `surface` — a sampled colour for a
        // stroked rect that demonstrably has one — and the frame is safe to paint biggest-first,
        // which is the only order consistent with the screen having been visible: pre-order breaks
        // across subcompositions, and a full-screen shell emitted last erased everything the
        // moment containers gained fills. Absent (v1), the renderer keeps list order and bare
        // outlines, so every frame stored before this field existed keeps its exact picture. A
        // server that predates the field ignores it and degrades to that same old picture.
        put("v", 2)
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
    val stroke: Boolean,
    /**
     * The sampled colour of a stroked rect's own surface, when it demonstrably has one.
     *
     * Only set when one colour dominates the rect's pixels — a goal card that is actually red —
     * never from a mean, because the mean of a container full of mixed children is a colour
     * belonging to nothing on the screen. Measured on the screen that forced this: a full-screen
     * shell averaged to mauve `#DBC3CA` out of white cards, one red card and the black of pixels
     * that were never drawn. The renderer fills a surfaced container and keeps a darkened border;
     * without this field it draws the outline alone, exactly as it always has.
     */
    val surface: Int? = null,
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
        surface?.let { put("surface", SkeletonFrame.colorToHex(it)) }
    }
}
