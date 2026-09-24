package com.lightsession

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.masking.Masking
import com.lightsession.masking.SuppliedMasks
import com.lightsession.replay.ScreenDrawing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That rectangles handed in by an embedder are covered, and that a failed report drops the frame.
 *
 * The masker finds what to cover by walking Views, and a screen painted into a surface holds no
 * View that carries text — `SurfaceContentMaskingTest` measures that the scan succeeds and
 * returns nothing. Covering such a screen at all means the toolkit that painted it has to say
 * where its text is, and this is the proof that saying so works.
 *
 * The second half is the one worth having a test for. An embedder that tries to measure its frame
 * and fails must not be read as an embedder reporting a clean screen: "could not measure" shipped
 * as "nothing to cover" is a leak with no symptom. So a null report drops frames until a good one
 * arrives, which is the same answer `MaskScanner` gives for its own failures.
 */
@RunWith(AndroidJUnit4::class)
class SuppliedMasksTest {

    private companion object {
        const val SURFACE_COLOUR = Color.RED
        const val MASK_COLOUR = 0xFF9E9E9E.toInt()
    }

    @After
    fun tearDown() {
        // Process-wide state, so a leaked report would reach every later test in this run.
        SuppliedMasks.clear()
    }

    @Test
    fun a_rectangle_an_embedder_reports_is_covered_on_the_frame() {
        withSurfaceScreen { scenario, width, height ->
            // Dead centre, a quarter of the screen: large enough that the centre pixel is
            // unambiguously inside it and the corners unambiguously outside.
            SuppliedMasks.set(
                generation = 1,
                rects = listOf(
                    Rect(width / 4, height / 4, width * 3 / 4, height * 3 / 4),
                ),
            )

            val frame = capture(scenario)
            assertNotNull("no frame came back", frame)

            assertEquals(
                "the reported rectangle was not covered, so a Flutter screen's text would ship " +
                    "legible",
                MASK_COLOUR,
                frame!!.getPixel(frame.width / 2, frame.height / 2),
            )
            assertEquals(
                "everything outside the reported rectangle should be the screen as it was",
                SURFACE_COLOUR,
                frame.getPixel(frame.width / 8, frame.height / 8),
            )
        }
    }

    @Test
    fun an_embedder_that_could_not_measure_its_screen_drops_the_frame() {
        withSurfaceScreen { scenario, _, _ ->
            SuppliedMasks.set(generation = 1, rects = null)

            assertNull(
                "a frame shipped while the embedder had no idea what to cover — the one state " +
                    "masking may never fail into",
                capture(scenario),
            )
        }
    }

    @Test
    fun an_empty_report_is_a_clean_screen_rather_than_a_failure() {
        withSurfaceScreen { scenario, _, _ ->
            // Distinct from null on purpose: a screen really can hold nothing coverable, and
            // refusing to record it would be refusing every splash screen and every image.
            SuppliedMasks.set(generation = 1, rects = emptyList())

            val frame = capture(scenario)
            assertNotNull("an empty report should not drop the frame", frame)
            assertEquals(
                SURFACE_COLOUR,
                frame!!.getPixel(frame.width / 2, frame.height / 2),
            )
        }
    }


    @Test
    fun a_report_that_moves_mid_capture_drops_the_frame() {
        withSurfaceScreen { scenario, width, height ->
            val planned = Rect(width / 4, height / 4, width * 3 / 4, height * 3 / 4)
            SuppliedMasks.set(generation = 1, rects = listOf(planned))
            // The embedder painted a new frame, with its text somewhere else, while this one was
            // being assembled: these rectangles describe a screen these pixels no longer show.
            val frame = captureWhileReporting(scenario, listOf(Rect(0, 0, width, height / 8)))
            assertNull(
                "a frame shipped whose mask rectangles were measured on a different frame — " +
                    "which is how a mask ends up beside the words instead of over them",
                frame,
            )
        }
    }

    @Test
    fun a_report_that_repeats_its_rectangles_mid_capture_still_ships() {
        withSurfaceScreen { scenario, width, height ->
            val planned = Rect(width / 4, height / 4, width * 3 / 4, height * 3 / 4)
            SuppliedMasks.set(generation = 1, rects = listOf(planned))
            // What a spinner or a map does: a new frame every vsync, and nothing masked moved.
            val frame = captureWhileReporting(scenario, listOf(Rect(planned)))
            assertNotNull(
                "the embedder painted again with its masks exactly where they were, and the frame " +
                    "was dropped — a screen that repaints every frame would lose every frame",
                frame,
            )
            assertEquals(
                "shipped, and still covered where the report said",
                MASK_COLOUR,
                frame!!.getPixel(width / 2, height / 2),
            )
        }
    }

    /** The rules [SuppliedMasks.movedSince] decides by, one report at a time. */
    @Test
    fun rectangles_move_only_when_a_later_report_says_so() {
        val a = listOf(Rect(0, 0, 10, 10))
        val b = listOf(Rect(0, 20, 10, 30))

        SuppliedMasks.set(generation = 5, rects = a)
        assertFalse("nothing reported since", SuppliedMasks.movedSince(5))
        SuppliedMasks.set(generation = 6, rects = listOf(Rect(0, 0, 10, 10)))
        SuppliedMasks.set(generation = 7, rects = a)
        assertFalse("two more frames, the same rectangles", SuppliedMasks.movedSince(5))

        SuppliedMasks.set(generation = 8, rects = b)
        SuppliedMasks.set(generation = 9, rects = a)
        assertTrue(
            "back where they were, but frame 8 had them elsewhere and may be the one copied",
            SuppliedMasks.movedSince(5),
        )
        assertFalse("measured from frame 9 itself", SuppliedMasks.movedSince(9))

        SuppliedMasks.set(generation = 10, rects = null)
        assertTrue("a report that could not measure", SuppliedMasks.movedSince(9))

        SuppliedMasks.set(generation = 20, rects = a)
        SuppliedMasks.set(generation = 3, rects = a)
        assertTrue("a count that went backwards is a restart, tied to nothing", SuppliedMasks.movedSince(20))

        SuppliedMasks.clear()
        assertTrue("an embedder that stopped reporting", SuppliedMasks.movedSince(3))
    }

    /**
     * Captures the screen, and reports [rects] as a new frame while the copy is in flight.
     *
     * After the plan is read, which is not at the call any more: a surface capture waits for the
     * next frame before it reads its plan. So the new report goes in from a frame callback, posted
     * behind the capture's own work — the capture registered its callback first, its work goes to
     * the front of the queue, and this lands after it, with the copy still out.
     */
    private fun captureWhileReporting(
        scenario: ActivityScenario<ComponentActivity>,
        rects: List<Rect>,
    ): Bitmap? {
        var frame: Bitmap? = null
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            ScreenDrawing().captureToBitmapAsync(
                scaleFactor = ScreenDrawing.Companion.ScalePresets.ORIGINAL,
                baseWindow = activity.window,
            ) { bitmap ->
                frame = bitmap
                done.countDown()
            }
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            android.view.Choreographer.getInstance().postFrameCallback {
                main.post { SuppliedMasks.set(generation = 2, rects = rects) }
            }
        }
        assertTrue("the capture never came back", done.await(15, TimeUnit.SECONDS))
        return frame
    }

    /** An Activity whose whole content is a surface painted [SURFACE_COLOUR], with masking on. */
    private fun withSurfaceScreen(
        body: (ActivityScenario<ComponentActivity>, width: Int, height: Int) -> Unit,
    ) {
        val wasText = Masking.text
        val wasImages = Masking.images
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val painted = CountDownLatch(1)
            var width = 0
            var height = 0

            scenario.onActivity { activity ->
                val surfaceView = SurfaceView(activity)
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val canvas = holder.lockCanvas() ?: return
                        canvas.drawColor(SURFACE_COLOUR, PorterDuff.Mode.SRC)
                        holder.unlockCanvasAndPost(canvas)
                        painted.countDown()
                    }

                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
                activity.setContentView(
                    FrameLayout(activity).apply {
                        addView(
                            surfaceView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    },
                )
            }

            assertTrue("the surface was never created", painted.await(10, TimeUnit.SECONDS))
            Thread.sleep(1_000)
            scenario.onActivity { activity ->
                width = activity.window.decorView.width
                height = activity.window.decorView.height
            }

            try {
                Masking.text = true
                Masking.images = false
                body(scenario, width, height)
            } finally {
                Masking.text = wasText
                Masking.images = wasImages
            }
        }
    }

    private fun capture(scenario: ActivityScenario<ComponentActivity>): Bitmap? {
        var frame: Bitmap? = null
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            ScreenDrawing().captureToBitmapAsync(
                scaleFactor = ScreenDrawing.Companion.ScalePresets.ORIGINAL,
                baseWindow = activity.window,
            ) { bitmap ->
                frame = bitmap
                done.countDown()
            }
        }
        assertTrue("the capture never came back", done.await(15, TimeUnit.SECONDS))
        return frame
    }
}
