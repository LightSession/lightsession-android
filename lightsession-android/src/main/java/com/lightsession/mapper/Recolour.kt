package com.lightsession.mapper

/**
 * Replaces a wireframe's palette colours with the ones actually on screen.
 *
 * ## Why not ask the framework
 *
 * The obvious route is to read the colour off the widget: `Modifier.background(…)` is
 * inspectable, a `View` has a background `Drawable`. Both were rejected. They are blind to
 * anything painted in a custom `draw()` — every image, every gradient, every canvas — which is
 * a large share of a real screen, and they are two entirely separate implementations that both
 * break when their framework moves. Two documented Android internals turned out to be
 * unreachable on API 36 while this SDK was being written.
 *
 * The frame is already in hand. The colour of a widget is whatever pixels are inside its
 * rectangle, which is exact by construction: theme, dark mode, photographs, WebViews, and
 * anything drawn by hand all come out right, with one implementation and nothing to keep in
 * step with Compose.
 *
 * ## Dominant, or mean
 *
 * Neither reduction wins, and the measurement is why there are two. Over a card with text on
 * it — the card `#F2F4F7` — the mean gives `#BFC1C3`, a grey belonging to neither the card nor
 * the ink, while the most common colour gives `#F4F4F7`: the card, because most of a text block
 * is background. Over a gradient the mean gives its midpoint and the most common colour picks
 * whichever narrow band happened to win.
 *
 * So: the most common colour when one actually dominates, and the mean when nothing does — and
 * "nothing dominates" is what a photograph or a gradient *is*. See [DOMINANCE].
 *
 * ## What this means for privacy
 *
 * A rectangle's thousands of pixels become three numbers. That is not reversible: no glyph can
 * be recovered from a colour, and the dominant colour of a text block is its background, so the
 * text does not merely blur — it is gone.
 *
 * The guarantee is a property of the *rectangles*, though, not of this code. One colour per
 * text block says nothing; one colour per glyph would spell the word out. That is what
 * [assertNotPerGlyph] is for.
 */
internal object Recolour {

    /**
     * Every Nth pixel in each axis.
     *
     * A sixteenth of the pixels, which is far more than enough to answer "what colour is this
     * surface", and it is the difference between 12 ms and 190 ms for a screen's worth of
     * rectangles.
     */
    const val STRIDE = 4

    /**
     * How much of a rectangle one colour must cover to be called its colour.
     *
     * Two fifths separates the two cases cleanly and is not close to either: a card with text
     * on it is 70–85% background, and the widest band of a gradient is a few percent. Set much
     * lower and a photograph gets whichever colour it happens to have most of; much higher and
     * a busy but flat surface falls through to a mean it does not need.
     */
    const val DOMINANCE = 0.4f

    /** 32 levels per channel: merges anti-aliasing without merging colours anybody would call
     *  different. */
    private const val LEVELS_SHIFT = 3
    private const val BUCKETS = 1 shl 15

    /**
     * The rectangles, with their colours read from `pixels`.
     *
     * `pixels` is the frame as one array — `Bitmap.getPixels` once rather than `getPixel` per
     * pixel, which measured 173 ms against 12 ms for a screen's rectangles, all of it the cost
     * of crossing into native code a million times.
     *
     * Rectangles are given in the frame's coordinates, which need not be the bitmap's: a capture
     * taken at half scale is half the size of the geometry describing it. Scaled here rather
     * than requiring the caller to match them, because the caller that gets it wrong produces a
     * wireframe coloured from the wrong parts of the screen and nothing says so.
     */
    fun apply(
        frame: SkeletonFrame,
        pixels: IntArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): SkeletonFrame {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return frame
        if (frame.width <= 0 || frame.height <= 0) return frame
        if (pixels.size < bitmapWidth * bitmapHeight) return frame

        val scaleX = bitmapWidth.toFloat() / frame.width
        val scaleY = bitmapHeight.toFloat() / frame.height

        val histogram = IntArray(BUCKETS)
        val touched = IntArray(BUCKETS)

        val recoloured = frame.rects.map { rect ->
            // An outline is structure, not surface. Sampling a container's whole area would
            // give the average of its children and paint its border in a colour that belongs
            // to none of them.
            if (rect.stroke) return@map rect

            val sampled = sample(
                pixels,
                bitmapWidth,
                bitmapHeight,
                histogram,
                touched,
                (rect.left * scaleX).toInt(),
                (rect.top * scaleY).toInt(),
                (rect.right * scaleX).toInt(),
                (rect.bottom * scaleY).toInt(),
            )
            // Nothing sampled — a rectangle off screen, or thinner than nothing. Keeping the
            // palette colour is better than painting it transparent.
            if (sampled == null) rect else rect.copy(color = sampled)
        }

        return frame.copy(rects = recoloured)
    }

    /**
     * One rectangle's colour, or null if it covers no pixels.
     *
     * The histogram and its scratch list are passed in because this runs once per rectangle and
     * a 32768-entry array per call would allocate 15 MB across a screen. Only the buckets
     * actually used are reset, since clearing all of them per rectangle costs more than the
     * counting does.
     */
    private fun sample(
        pixels: IntArray,
        width: Int,
        height: Int,
        histogram: IntArray,
        touched: IntArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Int? {
        val x0 = left.coerceIn(0, width - 1)
        val y0 = top.coerceIn(0, height - 1)
        // At least one pixel wide and tall. A hairline divider is thinner than the stride and
        // would otherwise sample nothing at all.
        val x1 = right.coerceIn(x0 + 1, width)
        val y1 = bottom.coerceIn(y0 + 1, height)

        var red = 0L
        var green = 0L
        var blue = 0L
        var counted = 0
        var used = 0

        var y = y0
        while (y < y1) {
            val row = y * width
            var x = x0
            while (x < x1) {
                val pixel = pixels[row + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                red += r
                green += g
                blue += b
                counted++

                val bucket = ((r shr LEVELS_SHIFT) shl 10) or
                    ((g shr LEVELS_SHIFT) shl 5) or
                    (b shr LEVELS_SHIFT)
                if (histogram[bucket] == 0) touched[used++] = bucket
                histogram[bucket]++

                x += STRIDE
            }
            y += STRIDE
        }

        if (counted == 0) return null

        var best = 0
        var bestCount = 0
        for (i in 0 until used) {
            val bucket = touched[i]
            val count = histogram[bucket]
            if (count > bestCount) {
                bestCount = count
                best = bucket
            }
            histogram[bucket] = 0
        }

        val opaque = 0xFF shl 24
        return if (bestCount.toFloat() / counted >= DOMINANCE) {
            // Mid-bucket rather than its floor, so a white surface comes back white instead of
            // very slightly grey.
            val half = 1 shl (LEVELS_SHIFT - 1)
            opaque or
                ((((best shr 10) and 0x1F) shl LEVELS_SHIFT) + half shl 16) or
                ((((best shr 5) and 0x1F) shl LEVELS_SHIFT) + half shl 8) or
                (((best and 0x1F) shl LEVELS_SHIFT) + half)
        } else {
            opaque or
                ((red / counted).toInt() shl 16) or
                ((green / counted).toInt() shl 8) or
                (blue / counted).toInt()
        }
    }

    /**
     * Whether a wireframe's rectangles are coarse enough for their colours to say nothing.
     *
     * The privacy property here rests entirely on rectangle size. One colour for a paragraph is
     * the colour of the paper; one colour per letter would spell the paragraph out in a
     * mosaic — and nothing about sampling would have changed, only what it was asked about.
     *
     * So the shape of the payload is the thing to defend. A rectangle small enough to hold a
     * single character is the signal, and there is no legitimate reason for the scan to emit
     * one: the smallest thing it describes is a widget.
     *
     * Returns the offending count rather than a boolean, so a caller can say how bad it is.
     */
    fun glyphSizedRects(frame: SkeletonFrame, minimumSide: Int = MIN_SAFE_SIDE): Int =
        frame.rects.count { rect ->
            !rect.stroke &&
                (rect.right - rect.left) < minimumSide &&
                (rect.bottom - rect.top) < minimumSide
        }

    /**
     * Below this, on both axes, a rectangle could be one character.
     *
     * 12px is smaller than any glyph a phone renders at a readable size — body text at the
     * smallest common scale is around 30px tall on a 1080-wide screen. Anything under this on
     * *both* axes is not a widget.
     */
    const val MIN_SAFE_SIDE = 12
}
