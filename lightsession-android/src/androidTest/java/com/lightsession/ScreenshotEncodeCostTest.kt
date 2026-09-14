package com.lightsession

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.replay.ScreenDrawing
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the real-screenshot upgrade costs the thread it runs on, in milliseconds.
 *
 * `takeScreenshot` runs inside `scopeFor(activity)`, which is `lifecycleScope` for any
 * `ComponentActivity` — that is `Dispatchers.Main.immediate`. Inside
 * `captureScreenAsBase64Async` the bitmap arrives through a suspending callback (fine), and then
 * two things happen on whatever thread resumed the coroutine: `encodeToJpeg` compresses a
 * full-resolution bitmap, and `Base64.encodeToString` re-encodes the result. Reading the
 * dispatcher chain establishes *that* both land on the main thread; it says nothing about whether
 * that is 5 ms or 500.
 *
 * The estimate in the review was "100-500ms", which is a wide enough range to change the verdict:
 * 5 ms on a screen change is nothing, and 300 ms is a visible stall. So it gets measured.
 *
 * ## Why the bitmap is synthetic
 *
 * A capture of the test Activity would be a nearly uniform rectangle, and JPEG is a
 * content-sensitive codec — a flat image compresses in a fraction of the time a photograph does,
 * which would measure the emulator's luck rather than the SDK's cost. The gradient plus noise
 * below is closer to a real screen's entropy, and it is the same buffer for every sample, so the
 * comparison between the two encoders is fair.
 *
 * The size is the device's own, at `ScalePresets.ORIGINAL`, because that is what the screenshot
 * path uses.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotEncodeCostTest {

    private val samples = 10

    private fun screenSizedBitmap(): Bitmap {
        val metrics = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics
        val bitmap = Bitmap.createBitmap(
            metrics.widthPixels,
            metrics.heightPixels,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawPaint(
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(),
                    Color.parseColor("#1B3A6B"), Color.parseColor("#F5F0FF"),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        // Detail, so the codec has something to do. A flat gradient alone compresses unrealistically
        // fast; a real screen is full of edges.
        val ink = Paint().apply { color = Color.WHITE; textSize = 34f }
        var y = 60f
        while (y < bitmap.height) {
            canvas.drawText("4111 1111 1111 1111  ·  session replay  ·  $y", 24f, y, ink)
            y += 52f
        }
        return bitmap
    }

    @Test
    fun the_real_screenshot_encode_is_measured_on_the_main_thread() {
        val drawing = ScreenDrawing()
        val reference = screenSizedBitmap()
        val pixels = reference.width * reference.height

        var encodeTotal = 0L
        var base64Total = 0L
        var jpegBytes = 0
        var base64Chars = 0

        repeat(samples) {
            // A fresh copy each time: `encodeToJpeg` recycles the bitmap it is given, which is
            // correct for the real caller and would make the second sample throw here.
            val copy = reference.copy(Bitmap.Config.ARGB_8888, false)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val encodeStarted = System.nanoTime()
                val bytes = drawing.encodeToJpeg(copy, ScreenDrawing.Companion.ScalePresets.ORIGINAL)
                encodeTotal += System.nanoTime() - encodeStarted

                if (bytes != null) {
                    jpegBytes = bytes.size
                    val base64Started = System.nanoTime()
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    base64Total += System.nanoTime() - base64Started
                    base64Chars = encoded.length
                }
            }
        }

        val encodeMs = encodeTotal / samples / 1_000_000.0
        val base64Ms = base64Total / samples / 1_000_000.0
        val totalMs = encodeMs + base64Ms

        println("[ScreenshotEncodeCost] bitmap ${reference.width}x${reference.height} = $pixels px, ${pixels * 4 / 1024 / 1024} MB ARGB_8888")
        println("[ScreenshotEncodeCost] jpeg out = ${jpegBytes / 1024} KB, base64 out = ${base64Chars / 1024} KB")
        println("[ScreenshotEncodeCost] MAIN THREAD, mean of $samples:")
        println("[ScreenshotEncodeCost]   encodeToJpeg = %.1f ms".format(encodeMs))
        println("[ScreenshotEncodeCost]   Base64       = %.1f ms".format(base64Ms))
        println("[ScreenshotEncodeCost]   total        = %.1f ms  (%.1f frames at 60fps)".format(totalMs, totalMs / 16.7))

        reference.recycle()

        assertTrue("nothing was encoded, so there is no measurement", jpegBytes > 0)
    }
}
