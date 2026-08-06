package com.lightsession

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What it costs to read the real colour of a widget out of a frame we already captured, and
 * what the two ways of reducing a rect to one colour actually produce.
 *
 * The skeleton currently paints from a palette keyed on node type — green for text, purple for
 * buttons — so a wireframe looks like a wireframe of some other app. The wire format already
 * carries a colour per rect and the renderer already honours it, so the only question is where
 * a truthful colour comes from. Introspecting the framework is one answer and a bad one: it
 * cannot see anything drawn in a custom `draw()`, which is most of what a real screen is, and
 * today alone two documented Android internals turned out to be unreachable on API 36.
 *
 * The other answer is that we are already holding a picture of the screen. This measures that
 * path before anyone builds on it.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.ColourSamplingCostTest
 */
@RunWith(AndroidJUnit4::class)
class ColourSamplingCostTest {

    private companion object {
        const val TAG = "ColourSampling"

        /** A phone frame at full resolution, which is what the screen-map capture uses. */
        const val WIDTH = 1080
        const val HEIGHT = 2400

        /**
         * Every Nth pixel in each axis.
         *
         * Four means a sixteenth of the pixels, which for deciding "what colour is this
         * surface" is far more than enough — and it is the difference between a few
         * milliseconds and a few tens of them.
         */
        const val STRIDE = 4
    }

    /** A screen: white background, a photo-ish gradient, a card, and a block of text on it. */
    private fun screen(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        canvas.drawColor(Color.WHITE)

        // A "photo": a horizontal ramp, the case a palette cannot represent at all.
        for (x in 0 until WIDTH) {
            paint.color = Color.rgb(x * 255 / WIDTH, 90, 255 - x * 255 / WIDTH)
            canvas.drawRect(x.toFloat(), 200f, (x + 1).toFloat(), 900f, paint)
        }

        // A card, and text on it. The text is the privacy-relevant part.
        paint.color = Color.rgb(0xF2, 0xF4, 0xF7)
        canvas.drawRect(60f, 1000f, 1020f, 1600f, paint)
        paint.color = Color.rgb(0x1A, 0x1A, 0x1A)
        for (line in 0 until 8) {
            val y = 1060f + line * 60f
            canvas.drawRect(100f, y, 700f, y + 28f, paint)
        }
        return bitmap
    }

    /**
     * The frame as one array.
     *
     * `Bitmap.getPixel` is a JNI call per pixel, and the measurement said so: 116 rects cost
     * 186ms with it. Pulling the whole frame across once and indexing it in Kotlin is the same
     * arithmetic without the crossing.
     */
    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun meanFast(pixels: IntArray, stride: Int, width: Int, rect: Rect): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        var y = rect.top
        while (y < rect.bottom) {
            val row = y * width
            var x = rect.left
            while (x < rect.right) {
                val pixel = pixels[row + x]
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                n++
                x += stride
            }
            y += stride
        }
        if (n == 0L) return Color.TRANSPARENT
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * 32768 buckets as a flat array, reused between rects.
     *
     * The `HashMap<Int, Int>` version measured 175ms for a screen's rects, against 26ms for the
     * mean over the same pixels — all of it boxing an Int key and an Int count per pixel, and
     * hashing them. An array indexed by the quantised colour does the same counting with a
     * load and a store.
     */
    private val histogram = IntArray(1 shl 15)
    private val touched = IntArray(1 shl 15)

    private fun dominantHistogram(pixels: IntArray, stride: Int, width: Int, rect: Rect): Int {
        var used = 0
        var y = rect.top
        while (y < rect.bottom) {
            val row = y * width
            var x = rect.left
            while (x < rect.right) {
                val pixel = pixels[row + x]
                val key = ((pixel shr 19) and 0x1F shl 10) or
                    ((pixel shr 11) and 0x1F shl 5) or
                    ((pixel shr 3) and 0x1F)
                // Only colours actually seen are reset afterwards. Clearing all 32768 per rect
                // would cost more than the counting does.
                if (histogram[key] == 0) touched[used++] = key
                histogram[key]++
                x += stride
            }
            y += stride
        }

        var best = 0
        var bestCount = 0
        for (i in 0 until used) {
            val key = touched[i]
            val count = histogram[key]
            if (count > bestCount) {
                bestCount = count
                best = key
            }
            histogram[key] = 0
        }
        if (bestCount == 0) return Color.TRANSPARENT
        return Color.rgb(
            (((best shr 10) and 0x1F) shl 3) or 4,
            (((best shr 5) and 0x1F) shl 3) or 4,
            ((best and 0x1F) shl 3) or 4,
        )
    }

    private fun dominantFast(pixels: IntArray, stride: Int, width: Int, rect: Rect): Int {
        val buckets = HashMap<Int, Int>(64)
        var y = rect.top
        while (y < rect.bottom) {
            val row = y * width
            var x = rect.left
            while (x < rect.right) {
                val pixel = pixels[row + x]
                val key = ((pixel shr 19) and 0x1F shl 10) or
                    ((pixel shr 11) and 0x1F shl 5) or
                    ((pixel shr 3) and 0x1F)
                buckets[key] = (buckets[key] ?: 0) + 1
                x += stride
            }
            y += stride
        }
        val top = buckets.maxByOrNull { it.value }?.key ?: return Color.TRANSPARENT
        return Color.rgb(
            (((top shr 10) and 0x1F) shl 3) or 4,
            (((top shr 5) and 0x1F) shl 3) or 4,
            ((top and 0x1F) shl 3) or 4,
        )
    }

    /** The mean of every sampled pixel. */
    private fun mean(bitmap: Bitmap, rect: Rect): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val pixel = bitmap.getPixel(x, y)
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
                n++
                x += STRIDE
            }
            y += STRIDE
        }
        if (n == 0L) return Color.TRANSPARENT
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * The most common colour, quantised into buckets.
     *
     * Quantised because exact equality finds nothing on anti-aliased content: a card's
     * "single" background is dozens of neighbouring values once something is drawn over it.
     */
    private fun dominant(bitmap: Bitmap, rect: Rect): Int {
        val buckets = HashMap<Int, Int>(64)
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val pixel = bitmap.getPixel(x, y)
                // 5 bits per channel: 32 levels, which merges anti-aliasing without merging
                // colours anybody would call different.
                val key = ((pixel shr 19) and 0x1F shl 10) or
                    ((pixel shr 11) and 0x1F shl 5) or
                    ((pixel shr 3) and 0x1F)
                buckets[key] = (buckets[key] ?: 0) + 1
                x += STRIDE
            }
            y += STRIDE
        }
        val top = buckets.maxByOrNull { it.value }?.key ?: return Color.TRANSPARENT
        // Back to the middle of the bucket rather than its floor, so a white surface comes
        // back white instead of slightly grey.
        val r = ((top shr 10) and 0x1F) shl 3
        val g = ((top shr 5) and 0x1F) shl 3
        val b = (top and 0x1F) shl 3
        return Color.rgb(r or 4, g or 4, b or 4)
    }

    private fun hex(color: Int) =
        "#%02X%02X%02X".format(Color.red(color), Color.green(color), Color.blue(color))

    @Test
    fun sampling_a_whole_screens_worth_of_rects_is_affordable() {
        val bitmap = screen()

        // A busy screen. Measured on the reporting app, a real one produced 117 rects.
        val rects = buildList {
            add(Rect(0, 0, WIDTH, HEIGHT))
            add(Rect(0, 200, WIDTH, 900))
            add(Rect(60, 1000, 1020, 1600))
            for (line in 0 until 8) {
                val y = 1060 + line * 60
                add(Rect(100, y, 700, y + 28))
            }
            // Filler, to reach the count a real screen produces.
            for (i in 0 until 105) {
                val top = 1700 + (i % 10) * 60
                add(Rect(40 + (i % 5) * 200, top, 220 + (i % 5) * 200, top + 40))
            }
        }

        // Warm up, so the first-call cost of the pixel accessor is not the measurement.
        rects.forEach { mean(bitmap, it) }

        var startedAt = System.nanoTime()
        rects.forEach { mean(bitmap, it) }
        val meanMs = (System.nanoTime() - startedAt) / 1_000_000.0

        rects.forEach { dominant(bitmap, it) }
        startedAt = System.nanoTime()
        rects.forEach { dominant(bitmap, it) }
        val dominantMs = (System.nanoTime() - startedAt) / 1_000_000.0

        // The same work over one array pulled across the boundary once.
        val flat = pixels(bitmap)
        rects.forEach { meanFast(flat, STRIDE, WIDTH, it) }
        startedAt = System.nanoTime()
        rects.forEach { meanFast(flat, STRIDE, WIDTH, it) }
        val meanFastMs = (System.nanoTime() - startedAt) / 1_000_000.0

        rects.forEach { dominantFast(flat, STRIDE, WIDTH, it) }
        startedAt = System.nanoTime()
        rects.forEach { dominantFast(flat, STRIDE, WIDTH, it) }
        val dominantFastMs = (System.nanoTime() - startedAt) / 1_000_000.0

        rects.forEach { dominantHistogram(flat, STRIDE, WIDTH, it) }
        startedAt = System.nanoTime()
        rects.forEach { dominantHistogram(flat, STRIDE, WIDTH, it) }
        val histogramMs = (System.nanoTime() - startedAt) / 1_000_000.0

        startedAt = System.nanoTime()
        pixels(bitmap)
        val pullMs = (System.nanoTime() - startedAt) / 1_000_000.0

        Log.i(TAG, ("${rects.size} rects, stride $STRIDE\n" +
            "  getPixel per pixel : mean %.1f ms | dominant %.1f ms\n" +
            "  one getPixels      : %.1f ms\n" +
            "  over the array     : mean %.1f ms | dominant(hashmap) %.1f ms | " +
            "dominant(histogram) %.1f ms")
            .format(meanMs, dominantMs, pullMs, meanFastMs, dominantFastMs, histogramMs))

        // Held against the path being retired rather than against a millisecond budget.
        //
        // These were `meanFastMs < 40` and `histogramMs < 60`, which measure the machine as
        // much as the code: a shared CI emulator is several times slower than the phone those
        // numbers came from, so the test would fail for having been run somewhere busy. A ratio
        // is taken on one machine in one run and cancels that out — it is also the claim
        // actually being made, and the shape `MaskingCostTest` already uses.
        //
        // A quarter is deliberately loose. Measured on device the array path is over ten times
        // cheaper than reaching across JNI per pixel; this only fires if that advantage has
        // largely gone, which is the thing worth knowing. The number in the log is still what is
        // worth reading.
        assertTrue(
            "mean over the array took %.1f ms against %.1f ms per pixel — the array path has "
                .format(meanFastMs, meanMs) + "stopped being the cheap one",
            meanFastMs < meanMs / 4,
        )
        assertTrue(
            "dominant over a histogram took %.1f ms against %.1f ms per pixel"
                .format(histogramMs, dominantMs),
            histogramMs < dominantMs / 4,
        )
        // Same answer, cheaper — a faster reduction that disagreed would be no use.
        val card = Rect(60, 1000, 1020, 1600)
        assertEquals(
            dominantFast(flat, STRIDE, WIDTH, card),
            dominantHistogram(flat, STRIDE, WIDTH, card),
        )
    }

    /**
     * What the two reductions do to a block of text, which is the whole privacy question.
     *
     * Neither can reconstruct a glyph — both are a projection of thousands of pixels onto
     * three numbers. But they differ in what they show: the mean of dark text on a light card
     * is a grey that belongs to neither, while the dominant colour is the card itself, because
     * most of a text block is background. Dominant is both the more faithful description of
     * the surface and the one that discards the text entirely.
     */
    @Test
    fun a_text_block_reduces_to_its_surface_not_to_a_smear() {
        val bitmap = screen()
        val card = Rect(60, 1000, 1020, 1600)

        val meanColour = mean(bitmap, card)
        val dominantColour = dominant(bitmap, card)
        Log.i(TAG, "card with text: mean ${hex(meanColour)}, dominant ${hex(dominantColour)}")

        // The card is #F2F4F7. Dominant should land on it; the mean is dragged down by ink.
        assertTrue(
            "dominant should be close to the card's own colour, got ${hex(dominantColour)}",
            Color.red(dominantColour) > 0xE0 && Color.blue(dominantColour) > 0xE0,
        )
        assertTrue(
            "the mean is pulled towards the text, got ${hex(meanColour)}",
            Color.red(meanColour) < Color.red(dominantColour),
        )
    }

    /** A gradient has no single colour, and that is exactly what a palette cannot express. */
    @Test
    fun a_photo_gets_a_plausible_colour_instead_of_being_labelled_an_image() {
        val bitmap = screen()
        val photo = Rect(0, 200, WIDTH, 900)

        val meanColour = mean(bitmap, photo)
        Log.i(TAG, "gradient: mean ${hex(meanColour)}, dominant ${hex(dominant(bitmap, photo))}")

        // The ramp runs red→blue with green fixed at 90, so its mean sits mid-way on both ends.
        assertTrue("green is flat across the ramp, got ${Color.green(meanColour)}", Color.green(meanColour) in 88..92)
        assertTrue(Color.red(meanColour) in 100..155)
        assertTrue(Color.blue(meanColour) in 100..155)
    }

    /** A rect smaller than the stride still has to come back with something. */
    @Test
    fun a_rect_thinner_than_the_stride_is_still_sampled() {
        val bitmap = screen()
        // A 2px divider, which is thinner than STRIDE and would otherwise sample no pixels.
        val hairline = Rect(100, 1060, 700, 1062)
        assertTrue(
            "a thin rect must not come back transparent",
            Color.alpha(mean(bitmap, hairline)) > 0,
        )
    }
}
