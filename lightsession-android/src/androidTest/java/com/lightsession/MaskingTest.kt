package com.lightsession

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That masking actually covers what it claims to, and covers the right pixels.
 *
 * Two failures matter here and neither announces itself. A masker that finds nothing
 * produces a frame that looks like an ordinary unmasked frame. A masker that finds the
 * right widgets but converts their coordinates wrongly produces a frame with grey blocks
 * on it that *looks* masked while leaving the text legible somewhere else — which is
 * worse, because it passes a glance.
 *
 * The second one is the reason the coordinate test below exists. The capture canvas is
 * scaled once by `CaptureQuality` and everything is drawn into that transform, so mask
 * rectangles are in screen pixels; converting them by hand against the scale factor is
 * the same mistake that made the replay's touch blob four times too big.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     --tests '*MaskingTest'
 */
@RunWith(AndroidJUnit4::class)
class MaskingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2400
        /** The mask fill. Mid-grey, so it reads as redaction rather than as a dead view. */
        const val MASK = 0x9E
    }

    private val defaults = Triple(Masking.text, Masking.images, Masking.debugHighlight)

    @After
    fun restorePolicy() {
        // The policy is process-wide, so a test that changes it has to put it back or
        // the next test inherits it.
        Masking.text = defaults.first
        Masking.images = defaults.second
        Masking.debugHighlight = defaults.third
    }

    /** A laid-out screen with one of each interesting widget. */
    private fun screen(): View {
        val root = FrameLayout(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column)

        column.addView(TextView(context).apply {
            text = "balance 12345"
            textSize = 24f
        })
        column.addView(EditText(context).apply { setText("secret") })
        column.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(300, 300)
            setBackgroundColor(Color.BLUE)
        })

        root.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, WIDTH, HEIGHT)
        return root
    }

    @Test
    fun text_and_inputs_are_found_and_images_are_not_by_default() {
        val rects = MaskScanner().scan(screen(), maskText = true, maskImages = false)

        // A TextView and an EditText: two rectangles at least. An input holds what the
        // user typed, which is the most sensitive thing on a screen, so it goes with the
        // text rather than with the images.
        assertTrue("found ${rects.size} rects, expected at least 2", rects.size >= 2)
        assertTrue("a rect was empty", rects.none { it.isEmpty })
    }

    @Test
    fun images_are_found_only_when_asked_for() {
        val text = MaskScanner().scan(screen(), maskText = true, maskImages = false)
        val both = MaskScanner().scan(screen(), maskText = true, maskImages = true)
        assertTrue(
            "enabling images did not add anything (${text.size} -> ${both.size})",
            both.size > text.size
        )
    }

    @Test
    fun nothing_is_collected_when_masking_is_off() {
        // And the traversal is skipped entirely, which is why `Masking.enabled` exists.
        val rects = MaskScanner().scan(screen(), maskText = false, maskImages = false)
        assertTrue(rects.isEmpty())
    }

    @Test
    fun a_mask_lands_on_the_same_pixels_at_every_capture_quality() {
        // The coordinate test. A rectangle given in screen pixels must cover the same
        // *content* whether the frame was captured at full size or at a quarter, because
        // the canvas carries the scale and the rectangle does not know about it.
        val rect = Rect(200, 400, 600, 500)

        for (scale in floatArrayOf(1.0f, 0.5f, 0.25f)) {
            val width = (WIDTH * scale).toInt()
            val height = (HEIGHT * scale).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            if (scale != 1.0f) canvas.scale(scale, scale)

            Masking.draw(canvas, listOf(rect))

            // Centre of the rectangle, in screen coordinates, mapped to this bitmap.
            val cx = ((rect.left + rect.right) / 2 * scale).toInt()
            val cy = ((rect.top + rect.bottom) / 2 * scale).toInt()
            assertEquals(
                "scale $scale: the middle of the rect was not masked",
                MASK,
                Color.red(bitmap.getPixel(cx, cy))
            )

            // A point well outside it must be untouched, at every scale — a mask that
            // covers the whole frame would pass the check above.
            val ox = (900 * scale).toInt()
            val oy = (1800 * scale).toInt()
            assertEquals(
                "scale $scale: a pixel outside the rect was masked",
                0xFF,
                Color.red(bitmap.getPixel(ox, oy))
            )
            bitmap.recycle()
        }
    }

    @Test
    fun a_mask_is_opaque() {
        // The dead `ViewMasker` this replaces drew 50%-alpha green, which left the text
        // underneath perfectly readable: a mask that looked like one without being one.
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        Masking.draw(canvas, listOf(Rect(0, 0, 200, 200)))

        // Over black, a translucent mask would come out darker than the mask colour.
        assertEquals(MASK, Color.red(bitmap.getPixel(100, 100)))
        assertEquals(0xFF, Color.alpha(bitmap.getPixel(100, 100)))
        bitmap.recycle()
    }

    @Test
    fun debug_highlight_leaves_the_content_visible_on_purpose() {
        // It exists to check placement, so it must *not* hide. If this ever starts
        // hiding, the tool stops being able to show a misplaced mask.
        Masking.debugHighlight = true
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        Masking.draw(canvas, listOf(Rect(0, 0, 200, 200)))

        val pixel = bitmap.getPixel(100, 100)
        assertTrue(
            "debug highlight came out opaque; it cannot show what it covers",
            Color.green(pixel) < 200
        )
        bitmap.recycle()
    }

    @Test
    fun drawing_no_rects_touches_nothing() {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        Masking.draw(canvas, emptyList())
        assertEquals(0xFF, Color.red(bitmap.getPixel(25, 25)))
        bitmap.recycle()
    }

    @Test
    fun the_default_policy_masks_text() {
        // The default is the whole safety argument: an SDK that has to be told to mask
        // leaks whenever nobody thinks to tell it.
        assertTrue("text masking is not on by default", defaults.first)
        assertTrue("debug highlight is on by default", !defaults.third)
    }
}
