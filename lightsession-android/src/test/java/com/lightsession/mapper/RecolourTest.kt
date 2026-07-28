package com.lightsession.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a wireframe painted from the screen is both truthful and unable to say too much.
 *
 * Runs on the JVM because the sampling works on an `IntArray`, not a `Bitmap` — which is the
 * reason it takes one. The pixels are the input; needing a device to test a weighted average
 * would have meant not testing it.
 */
class RecolourTest {

    private companion object {
        const val W = 200
        const val H = 400

        const val WHITE = 0xFFFFFFFF.toInt()
        const val CARD = 0xFFF2F4F7.toInt()
        const val INK = 0xFF1A1A1A.toInt()
        const val PALETTE_GREEN = 0xFF4CAF50.toInt()
    }

    /** A frame of `W`×`H` pixels, painted by a lambda given (x, y). */
    private fun pixels(paint: (Int, Int) -> Int): IntArray =
        IntArray(W * H) { index -> paint(index % W, index / W) }

    private fun frame(vararg rects: SkeletonRect) =
        SkeletonFrame(width = W, height = H, background = WHITE, rects = rects.toList())

    private fun rect(
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        kind: String = "TEXT",
        stroke: Boolean = false,
    ) = SkeletonRect(l, t, r, b, kind, PALETTE_GREEN, stroke)

    private fun red(color: Int) = (color shr 16) and 0xFF
    private fun green(color: Int) = (color shr 8) and 0xFF
    private fun blue(color: Int) = color and 0xFF
    private fun hex(color: Int) = "#%02X%02X%02X".format(red(color), green(color), blue(color))

    @Test
    fun `a flat surface comes back as itself`() {
        val flat = pixels { _, _ -> CARD }
        val out = Recolour.apply(frame(rect(10, 10, 190, 200)), flat, W, H)

        // Quantised to 32 levels per channel, so within 8 of the original.
        val colour = out.rects.single().color
        assertTrue("got ${hex(colour)}, wanted about ${hex(CARD)}", kotlin.math.abs(red(colour) - 0xF2) <= 8)
        assertTrue(kotlin.math.abs(green(colour) - 0xF4) <= 8)
        assertTrue(kotlin.math.abs(blue(colour) - 0xF7) <= 8)
    }

    @Test
    fun `text on a card takes the card's colour, not a grey between them`() {
        // The measurement this rule came from: over a real card with text, the mean gave
        // #BFC1C3 — a grey belonging to neither the paper nor the ink — while the most common
        // colour gave the card. Most of a text block is background.
        val card = pixels { _, y ->
            // Eight lines of ink, each 8px of every 40 — about a fifth of the block.
            if (y % 40 < 8) INK else CARD
        }
        val colour = Recolour.apply(frame(rect(0, 0, W, H)), card, W, H).rects.single().color

        assertTrue(
            "the paper, not a smear: got ${hex(colour)}",
            red(colour) > 0xE0 && green(colour) > 0xE0 && blue(colour) > 0xE0,
        )
    }

    @Test
    fun `a gradient falls through to its average`() {
        // No colour dominates a ramp, and the widest band of one is a few percent — so the
        // most common colour would be whichever narrow stripe happened to win. The mean is the
        // honest answer for something that has no single colour.
        val ramp = pixels { x, _ ->
            val r = x * 255 / W
            (0xFF shl 24) or (r shl 16) or (90 shl 8) or (255 - r)
        }
        val colour = Recolour.apply(frame(rect(0, 0, W, H)), ramp, W, H).rects.single().color

        assertTrue("green is flat across the ramp: got ${hex(colour)}", green(colour) in 88..92)
        assertTrue("red should sit mid-ramp: got ${hex(colour)}", red(colour) in 100..155)
        assertTrue("blue should sit mid-ramp: got ${hex(colour)}", blue(colour) in 100..155)
    }

    @Test
    fun `an outline keeps its palette colour`() {
        // A container is structure. Sampling its whole area would average its children and
        // paint the border a colour belonging to none of them.
        val mixed = pixels { x, _ -> if (x < W / 2) INK else WHITE }
        val out = Recolour.apply(frame(rect(0, 0, W, H, kind = "CONTAINER", stroke = true)), mixed, W, H)
        assertEquals(PALETTE_GREEN, out.rects.single().color)
    }

    @Test
    fun `geometry and bitmap need not be the same size`() {
        // A capture taken at half scale is half the size of the geometry describing it. Getting
        // this wrong colours the wireframe from the wrong parts of the screen and nothing says so.
        val half = 2
        val small = IntArray((W / half) * (H / half)) { index ->
            val x = index % (W / half)
            // Left half of the *bitmap* is ink, so the left half of the frame must be too.
            if (x < W / half / 2) INK else CARD
        }
        val out = Recolour.apply(
            frame(rect(0, 0, W / 2, H), rect(W / 2, 0, W, H)),
            small,
            W / half,
            H / half,
        )
        assertTrue("left half should be ink", red(out.rects[0].color) < 0x40)
        assertTrue("right half should be card", red(out.rects[1].color) > 0xE0)
    }

    @Test
    fun `a hairline thinner than the stride is still sampled`() {
        val ink = pixels { _, _ -> INK }
        // 2px tall, where the stride is 4.
        val colour = Recolour.apply(frame(rect(0, 100, W, 102)), ink, W, H).rects.single().color
        assertNotEquals("must not fall back to the palette", PALETTE_GREEN, colour)
        assertTrue(red(colour) < 0x40)
    }

    @Test
    fun `a rectangle off the edge of the frame keeps its palette colour or clamps safely`() {
        val flat = pixels { _, _ -> CARD }
        // Wholly outside, and partially outside.
        val out = Recolour.apply(
            frame(rect(W + 50, H + 50, W + 100, H + 100), rect(W - 10, H - 10, W + 100, H + 100)),
            flat,
            W,
            H,
        )
        // Neither may crash, and neither may come back transparent.
        out.rects.forEach { assertEquals(0xFF, (it.color shr 24) and 0xFF) }
    }

    @Test
    fun `a capture that cannot be trusted leaves the frame untouched`() {
        val original = frame(rect(0, 0, 10, 10))

        assertEquals("no pixels at all", original, Recolour.apply(original, IntArray(0), 0, 0))
        // Shorter than the dimensions claim. A truncated capture must not be indexed past its
        // end, and the palette colour is a better answer than a crash or a guess.
        assertEquals("truncated", original, Recolour.apply(original, IntArray(10), W, H))
    }

    @Test
    fun `a tiny but complete capture is sampled, not refused`() {
        // 2x2 is smaller than the stride and still valid input — it is a real, if useless,
        // picture of the screen. Refusing it would mean a thumbnail-scale capture silently fell
        // back to the palette while looking like it had worked.
        val original = frame(rect(0, 0, 10, 10))
        val fourWhitePixels = IntArray(4) { WHITE }
        val colour = Recolour.apply(original, fourWhitePixels, 2, 2).rects.single().color
        assertNotEquals(PALETTE_GREEN, colour)
        assertTrue("got ${hex(colour)}", red(colour) > 0xF0)
    }

    // ------------------------------------------------- the privacy guardrail

    @Test
    fun `a wireframe of widgets has no glyph-sized rectangles`() {
        // What keeps a colour per rectangle from spelling anything out is the size of the
        // rectangle. This is the shape a real scan produces: widgets.
        val widgets = frame(
            rect(0, 0, W, 60),
            rect(20, 80, 180, 120),
            rect(20, 140, 100, 170),
        )
        assertEquals(0, Recolour.glyphSizedRects(widgets))
    }

    @Test
    fun `rectangles the size of letters are counted, because that is when this stops being safe`() {
        // If the scan ever emitted one rectangle per character, sampling would paint the text
        // back — each letter its own ink-coloured dot on paper. Nothing about the sampling
        // would have changed, only what it was asked about. This is the tripwire.
        val perGlyph = frame(*(0 until 12).map { i ->
            rect(20 + i * 9, 100, 28 + i * 9, 110)
        }.toTypedArray())

        assertEquals(12, Recolour.glyphSizedRects(perGlyph))
    }

    @Test
    fun `one narrow dimension is not enough to be a glyph`() {
        // A divider is 2px tall and the width of the screen. It is not a letter, and counting
        // it would make the tripwire fire on every screen that has a separator.
        val divider = frame(rect(0, 100, W, 102))
        assertEquals(0, Recolour.glyphSizedRects(divider))
    }
}
