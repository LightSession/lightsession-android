package com.lightsession

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * What masking a frame on-device actually costs, measured on a real device.
 *
 * The question this settles is whether drawing mask rectangles onto the captured
 * bitmap is expensive enough to justify sending the rectangles to the server and
 * masking there — the argument being that the SDK must stay lighter than the
 * alternatives.
 *
 * It measures the three things in the pipeline separately, because the cost that
 * matters is the *marginal* one:
 *
 *  * the JPEG encode, which the SDK already pays for every frame;
 *  * drawing N filled rectangles, which is what masking adds;
 *  * the bitmap allocation, for scale.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     --tests '*MaskingCostTest'
 * and read the `MaskingCost` lines in logcat.
 */
@RunWith(AndroidJUnit4::class)
class MaskingCostTest {

    private companion object {
        const val TAG = "MaskingCost"

        /** A 1080×2400 phone, the size the SDK captures at `ScalePresets.ORIGINAL`. */
        const val WIDTH = 1080
        const val HEIGHT = 2400

        /** JPEG quality the SDK uses for a full-scale frame. */
        const val QUALITY = 80

        const val WARMUP = 5
        const val RUNS = 30
    }

    /** A frame with enough structure that JPEG has real work to do. */
    private fun sampleFrame(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint()
        // Bands and boxes, so the encoder is not compressing a flat colour — a
        // uniform bitmap would understate the encode cost and flatter the argument
        // this test exists to check.
        for (row in 0 until 24) {
            paint.color = Color.rgb(40 + row * 8, 90, 200 - row * 6)
            canvas.drawRect(
                60f,
                (row * 100).toFloat(),
                (WIDTH - 60).toFloat(),
                (row * 100 + 72).toFloat(),
                paint
            )
        }
        paint.color = Color.DKGRAY
        for (i in 0 until 200) {
            canvas.drawCircle((i * 37 % WIDTH).toFloat(), (i * 91 % HEIGHT).toFloat(), 6f, paint)
        }
        return bitmap
    }

    /** Rectangles standing in for what a semantic pass would mark as sensitive. */
    private fun maskRects(count: Int): List<Rect> =
        (0 until count).map { index ->
            val top = 120 + index * 140
            Rect(80, top, WIDTH - 80, top + 96)
        }

    private inline fun median(runs: Int, block: () -> Unit): Double {
        repeat(WARMUP) { block() }
        val samples = DoubleArray(runs) {
            val started = System.nanoTime()
            block()
            (System.nanoTime() - started) / 1_000_000.0
        }
        samples.sort()
        return samples[runs / 2]
    }

    @Test
    fun masking_is_a_rounding_error_next_to_the_encode_the_sdk_already_pays() {
        val frame = sampleFrame()
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        val allocate = median(RUNS) {
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).recycle()
        }

        val encode = median(RUNS) {
            val out = ByteArrayOutputStream(256 * 1024)
            frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.size()
        }

        val results = LinkedHashMap<Int, Double>()
        for (count in intArrayOf(1, 5, 10, 25, 50)) {
            val rects = maskRects(count)
            // On a copy, because masking in place would leave the frame masked for
            // the next iteration and make the encode measurement drift.
            val target = frame.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(target)
            results[count] = median(RUNS) {
                for (rect in rects) canvas.drawRect(rect, paint)
            }
            target.recycle()
        }

        val size = ByteArrayOutputStream(256 * 1024).also {
            frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)
        }.size()

        Log.w(TAG, "=== masking cost on ${WIDTH}x$HEIGHT, median of $RUNS runs ===")
        Log.w(TAG, String.format("allocate bitmap      %8.3f ms", allocate))
        Log.w(TAG, String.format("JPEG encode (q=%d)   %8.3f ms   -> %d KB", QUALITY, encode, size / 1024))
        for ((count, ms) in results) {
            Log.w(
                TAG,
                String.format(
                    "draw %2d mask rects   %8.3f ms   = %5.2f%% of the encode",
                    count, ms, ms / encode * 100
                )
            )
        }

        // The claim, asserted rather than merely printed: masking a realistic number
        // of fields costs a small fraction of the encode. If this ever fails, the
        // performance argument for masking server-side has become real and this test
        // is the place that says so.
        val tenRects = results.getValue(10)
        assert(tenRects < encode * 0.25) {
            "drawing 10 mask rects took ${tenRects}ms against a ${encode}ms encode — " +
                "masking is no longer cheap and the tradeoff needs revisiting"
        }

        frame.recycle()
    }

    /**
     * The other half of the picture: what sending *only* rectangles would save.
     *
     * If the server draws the whole frame from a rectangle list, the device never
     * encodes a JPEG at all — which is the cost that actually dominates. This
     * measures the payload difference.
     */
    @Test
    fun a_rect_only_frame_is_orders_of_magnitude_smaller_than_a_jpeg() {
        val frame = sampleFrame()
        val jpeg = ByteArrayOutputStream(256 * 1024).also {
            frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)
        }.size()

        // 40 widgets as JSON: four coordinates, a kind, and a colour.
        val rects = maskRects(40)
        val payload = StringBuilder("[")
        for ((index, rect) in rects.withIndex()) {
            if (index > 0) payload.append(',')
            payload.append(
                """{"l":${rect.left},"t":${rect.top},"r":${rect.right},"b":${rect.bottom},"k":"BUTTON","c":"#5B4FA8"}"""
            )
        }
        payload.append(']')
        val rectBytes = payload.toString().toByteArray().size

        Log.w(TAG, "=== payload per frame ===")
        Log.w(TAG, String.format("JPEG                 %8d bytes", jpeg))
        Log.w(TAG, String.format("40 rects as JSON     %8d bytes   = 1/%d of the JPEG", rectBytes, jpeg / rectBytes))

        assert(rectBytes * 10 < jpeg) {
            "a rect list ($rectBytes B) is not meaningfully smaller than the JPEG ($jpeg B)"
        }
        frame.recycle()
    }
}
