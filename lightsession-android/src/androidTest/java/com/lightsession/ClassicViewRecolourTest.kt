package com.lightsession

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.ScreenGeometry
import com.lightsession.mapper.Recolour
import com.lightsession.mapper.SkeletonFrame
import com.lightsession.mapper.SkeletonGenerator
import com.lightsession.mapper.SkeletonRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * That sampling colours from the screen works on a classic View screen, not only on Compose.
 *
 * Worth its own test because the two hierarchies reach [SkeletonFrame] by completely separate
 * code: `scanViewHierarchy` recurses through `ViewGroup.children` for Views, and hands off to the
 * Compose tooling API for a composition. Only the *output* of those two paths is shared. A change
 * that worked on one and silently produced palette colours on the other would look identical from
 * the dashboard — the wireframe renders either way — so nothing would report it.
 *
 * There is no Compose in the hierarchy under test. That is the point of the file.
 *
 * ## Why this needs a real window
 *
 * The first version of this test laid the views out unattached, the way `SkeletonCostTest` does.
 * That is fine for counting nodes and timing them, and wrong here: `getLocationOnScreen` returns
 * (0, 0) for a view with no window, so every rect in the frame collapses onto the origin. The
 * scan still succeeds and the sampling still runs — every widget just comes back holding the
 * colour of whatever occupies the top-left corner, which in that version was a green toolbar.
 * Nothing fails; the numbers are quietly about the wrong pixels.
 *
 * So the geometry has to come from a hierarchy that is actually on screen. That also makes this a
 * test of the coordinate agreement between the scan and the capture, which is the failure the
 * Compose path already carries a warning about: a frame whose rects are offset from the bitmap
 * they are sampled from is a wireframe painted from the wrong parts of the screen — and a mask
 * drawn over the wrong part of it.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.ClassicViewRecolourTest
 * and read the `ClassicRecolour` lines in logcat.
 */
@RunWith(AndroidJUnit4::class)
class ClassicViewRecolourTest {

    /**
     * The frame's coordinate space and the bitmap's have to be the same space.
     *
     * `SkeletonGenerator.canvasSize` takes the frame's dimensions from [ScreenGeometry], and
     * `Recolour.apply` divides the bitmap's dimensions by them to map a rect onto pixels. Un-
     * attached, `ScreenGeometry` falls back to `Resources.getSystem().displayMetrics` — a number
     * that belongs to no window in particular — so the two spaces agree only by luck.
     *
     * The luck held on every machine this suite had ever run on, and ran out on CI: both a
     * button painted `#B71C4A` and a view painting a red gradient came back the *same* colour,
     * `#D4E4FC`, which is what a wrong scale does — every rect samples the same displaced
     * region. `DialogMaskingTest`, `MaskLeakProofTest`, `MaskStalenessTest` and
     * `ModalSkeletonTest` all attach in their own setup for this reason; this file read pixels
     * without doing so.
     *
     * Attaching is also what the SDK itself does, first thing in `LightSession.init`. Without it
     * the test exercised a fallback no real app ever reaches.
     */
    @Before
    fun attachGeometry() {
        ScreenGeometry.attach(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private companion object {
        const val TAG = "ClassicRecolour"

        const val TOOLBAR_HEIGHT = 160
        const val CARD_HEIGHT = 600
        const val TEXT_HEIGHT = 240
        const val BUTTON_HEIGHT = 200
        const val GRADIENT_HEIGHT = 400

        /** Deliberately none of the palette's colours, so a rect that merely kept its palette
         *  colour cannot be mistaken for one that was sampled and happened to agree. */
        const val TOOLBAR = 0xFF00695C.toInt()
        const val PAGE = 0xFFFAF6F0.toInt()
        const val CARD = 0xFFD7E3FC.toInt()
        const val BUTTON_FILL = 0xFFB71C4A.toInt()
        const val INK = 0xFF202124.toInt()

        const val GRADIENT_FROM = 0xFFEC5C14.toInt()
        const val GRADIENT_TO = 0xFF1443EC.toInt()
    }

    // ------------------------------------------------------------------ the screen

    /** A View that paints a gradient in `onDraw`, which no framework getter can report. */
    private class GradientView(activity: Activity) : View(activity) {
        private val paint = Paint()

        override fun onDraw(canvas: Canvas) {
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                GRADIENT_FROM, GRADIENT_TO, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    /**
     * The widgets a test asserts about, held rather than found by coordinate.
     *
     * `LinearLayout` does not put a child exactly where its `LayoutParams` ask — a `Button` brings
     * its own padding and `minHeight` — so a test that hardcodes `top == 760` is asserting about
     * the framework's layout maths and fails for reasons that have nothing to do with colour.
     * Asking each View where it ended up uses the same call the scan used, so the two agree by
     * construction.
     */
    private class Screen(
        val decor: View,
        val toolbar: View,
        val paragraph: View,
        val button: View,
        val gradient: View,
    )

    /** A plain `LinearLayout` screen: toolbar, a card with a paragraph, a button, a gradient. */
    private fun buildInto(activity: Activity): Screen {
        val root = FrameLayout(activity).apply { setBackgroundColor(PAGE) }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE)
        }
        root.addView(column)

        val toolbar = View(activity).apply { setBackgroundColor(TOOLBAR) }
        column.addView(toolbar, lp(TOOLBAR_HEIGHT))

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(CARD)
        }
        column.addView(card, lp(CARD_HEIGHT))

        // Not `val text`: inside `apply`, `text = …` resolves to the local being declared rather
        // than to the TextView's property, and the compiler's complaint points at the wrong thing.
        val paragraph = TextView(activity).apply {
            setText("Estoque baixo em 4 medicamentos. Revise o pedido antes de sexta.")
            setTextColor(INK)
            textSize = 16f
        }
        card.addView(paragraph, lp(TEXT_HEIGHT))

        val button = Button(activity).apply {
            setText("Confirmar")
            setBackgroundColor(BUTTON_FILL)
        }
        column.addView(button, lp(BUTTON_HEIGHT))

        val gradient = GradientView(activity)
        column.addView(gradient, lp(GRADIENT_HEIGHT))

        activity.setContentView(root)
        return Screen(activity.window.decorView, toolbar, paragraph, button, gradient)
    }

    private fun lp(height: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)

    // ------------------------------------------------------------------ scan and sample

    /**
     * The screen up, drawn once, then scanned and sampled — the View work on the main thread.
     *
     * Waiting for a real draw pass rather than for `onActivity` to return is the part that
     * matters: `setContentView` only schedules a traversal, and a frame scanned before that
     * traversal has no geometry to be right or wrong about.
     */
    private fun onScreen(assertions: (Screen, SkeletonFrame) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var built: Screen? = null
            scenario.onActivity { built = buildInto(it) }
            val screen = requireNotNull(built) { "onActivity did not run" }

            awaitDraw(screen.decor)

            var sampled: SkeletonFrame? = null
            scenario.onActivity { sampled = scanAndSample(screen) }
            assertions(screen, requireNotNull(sampled) { "no frame" })
        }
    }

    /**
     * Blocks until the hierarchy has actually been drawn once.
     *
     * The listener is attached unconditionally, and that is the whole fix. This used to check
     * `decor.width > 0 && decor.isShown` first and, when the window had not been laid out yet,
     * count the latch down anyway — declaring "drawn" without a draw. Every assertion after it
     * then read an undrawn window, which is not a failure a fast emulator can produce: locally
     * the layout is always finished by the time this posted runnable runs, so the branch was
     * dead code here and live on CI, where the same suite takes seven times as long.
     *
     * What that looked like: four red checks whose colours differed per run — `#D4E4FC` on one
     * API level, `#040404` on another — because an undrawn window has no colour of its own, only
     * whatever its buffer happens to hold.
     *
     * A window that never lays out now trips the timeout with a message that says so, instead of
     * passing quietly. `invalidate` is posted after the listener rather than before, so a
     * traversal that was already scheduled cannot land in the gap between the two.
     */
    private fun awaitDraw(decor: View) {
        val drawn = CountDownLatch(1)
        decor.post {
            decor.viewTreeObserver.addOnDrawListener(
                object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        // Removed from a post, not from inside the callback: the observer is
                        // iterating its listener list here and mutating it throws.
                        decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
                        drawn.countDown()
                    }
                }
            )
            decor.invalidate()
        }
        assertTrue(
            "the screen never drew: laid out ${decor.width}x${decor.height}, shown=${decor.isShown}",
            drawn.await(20, TimeUnit.SECONDS),
        )
        // A draw pass fired, but a zero-sized window can fire one and still hold nothing. Asserted
        // separately so the two failures read differently — "never drew" and "drew nothing" have
        // different causes and the message is the only thing a CI log carries.
        assertTrue(
            "the screen drew at ${decor.width}x${decor.height}, which samples nothing",
            decor.width > 0 && decor.height > 0,
        )
    }

    /**
     * The frame, with its colours read from the window's own pixels.
     *
     * `decorView.draw` rather than `PixelCopy`: the decor view sits at the screen's origin, so
     * drawing it gives a bitmap whose (0, 0) is screen (0, 0) — the space the scan's rects are
     * already in. `PixelCopy` yields the same pixels here and needs a callback; the two diverge
     * only for content this screen does not have (SurfaceView, hardware overlays).
     */
    private fun scanAndSample(screen: Screen): SkeletonFrame {
        val frame = requireNotNull(SkeletonGenerator().frameFrom(screen.decor, PAGE)) {
            "no frame from an attached decor view"
        }
        // Stated rather than assumed. `Recolour` scales rects by bitmap ÷ frame, so a mismatch
        // here does not fail — it silently samples the wrong pixels, and every assertion below
        // then reports a colour belonging to somewhere else on the screen.
        assertEquals(
            "the frame and the bitmap are in different coordinate spaces",
            "${screen.decor.width}x${screen.decor.height}",
            "${frame.width}x${frame.height}",
        )
        val bitmap = Bitmap.createBitmap(
            screen.decor.width, screen.decor.height, Bitmap.Config.ARGB_8888,
        )
        try {
            screen.decor.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return Recolour.apply(frame, pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun hex(color: Int) =
        "#%02X%02X%02X".format((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)

    private fun near(actual: Int, expected: Int, tolerance: Int = 12): Boolean {
        fun channel(shift: Int) =
            kotlin.math.abs(((actual shr shift) and 0xFF) - ((expected shr shift) and 0xFF))
        return channel(16) <= tolerance && channel(8) <= tolerance && channel(0) <= tolerance
    }

    /** The filled rect covering `view`, by the bounds the view itself reports. */
    private fun rectFor(frame: SkeletonFrame, view: View): SkeletonRect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return frame.rects.firstOrNull { rect ->
            !rect.stroke &&
                kotlin.math.abs(rect.left - location[0]) <= 2 &&
                kotlin.math.abs(rect.top - location[1]) <= 2 &&
                kotlin.math.abs((rect.right - rect.left) - view.width) <= 2 &&
                kotlin.math.abs((rect.bottom - rect.top) - view.height) <= 2
        } ?: error(
            "no filled rect at (${location[0]}, ${location[1]}) ${view.width}x${view.height} " +
                "for ${view.javaClass.simpleName}; frame has " +
                frame.rects.joinToString {
                    "${it.kind}${if (it.stroke) "/stroke" else ""}" +
                        "(${it.left},${it.top} ${it.right - it.left}x${it.bottom - it.top})"
                }
        )
    }

    // ------------------------------------------------------------------ the tests

    @Test
    fun a_classic_view_screen_is_coloured_from_its_pixels() = onScreen { _, frame ->
        val filled = frame.rects.filter { !it.stroke }
        assertTrue("the scan found nothing to colour", filled.isNotEmpty())
        Log.i(TAG, "${frame.rects.size} rects, ${filled.size} filled")
    }

    @Test
    fun the_toolbar_comes_back_the_toolbar_s_own_colour() = onScreen { screen, frame ->
        // A bare `View` with a flat background is the one case the framework getters already
        // handled. Sampling has to agree with them, or it is worse than what it replaced.
        val toolbar = rectFor(frame, screen.toolbar)
        Log.i(TAG, "toolbar: ${hex(toolbar.color)} (real ${hex(TOOLBAR)})")
        assertTrue("got ${hex(toolbar.color)}, wanted ${hex(TOOLBAR)}", near(toolbar.color, TOOLBAR))
    }

    @Test
    fun the_button_keeps_the_colour_it_is_actually_painted() = onScreen { screen, frame ->
        val button = rectFor(frame, screen.button)
        Log.i(TAG, "button: ${hex(button.color)} (real ${hex(BUTTON_FILL)})")
        assertTrue(
            "a button painted ${hex(BUTTON_FILL)} came back ${hex(button.color)}",
            near(button.color, BUTTON_FILL),
        )
    }

    @Test
    fun a_view_that_paints_itself_in_onDraw_is_the_whole_reason_for_this() =
        onScreen { screen, frame ->
            // A gradient drawn in `onDraw` has no background Drawable, no tint, and no colour any
            // framework getter can report — `extractVisuals` falls through to a palette grey.
            // Reading the pixels is the only way to know it is orange-to-blue, and the mean is
            // the honest answer for something that has no single colour.
            val gradient = rectFor(frame, screen.gradient)
            Log.i(
                TAG,
                "gradient: ${hex(gradient.color)} " +
                    "(ramp ${hex(GRADIENT_FROM)} -> ${hex(GRADIENT_TO)})",
            )

            val red = (gradient.color shr 16) and 0xFF
            val blue = gradient.color and 0xFF
            assertTrue("mid-ramp red, got ${hex(gradient.color)}", red in 90..190)
            assertTrue("mid-ramp blue, got ${hex(gradient.color)}", blue in 60..160)
        }

    @Test
    fun an_unmasked_text_block_comes_back_as_its_paper() = onScreen { screen, frame ->
        // Worth pinning down because it is a visible change and not an obvious one. With the
        // palette, a TEXT rect is filled with `currentTextColor` — the ink — so a text block
        // reads as a dark bar. Sampled, most of a text block is paper, so it comes back the
        // card's colour and stops standing out against the card.
        //
        // The shipping default hides this: `maskText` is on, the capture has a mask block over
        // the text, and the dominant colour inside the rect is the mask. This asserts the
        // *unmasked* case, so that turning masking off is a known trade rather than a surprise.
        val text = rectFor(frame, screen.paragraph)
        Log.i(TAG, "unmasked text block: ${hex(text.color)} (ink ${hex(INK)}, card ${hex(CARD)})")

        assertTrue(
            "expected the card ${hex(CARD)}, got ${hex(text.color)}",
            near(text.color, CARD, tolerance = 24),
        )
    }

    @Test
    fun a_classic_view_screen_has_no_glyph_sized_rectangles() = onScreen { _, frame ->
        // The privacy guarantee rests on rectangle size, and the View path takes its rects from
        // `View.getWidth/getHeight` — a widget is a widget. A `TextView` is one rect however many
        // words are in it, because the framework has no node below it.
        val offenders = Recolour.glyphSizedRects(frame)
        Log.i(TAG, "${frame.rects.size} rects, $offenders glyph-sized")
        assertEquals("a View hierarchy has no node smaller than a widget", 0, offenders)
    }
}
