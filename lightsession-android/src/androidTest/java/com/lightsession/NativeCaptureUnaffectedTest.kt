package com.lightsession

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.replay.ScreenDrawing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That an ordinary Android screen is captured the way it always was.
 *
 * The surface capture path was added for a toolkit that paints into a surface, and the check that
 * routes onto it runs on **every capture of every app**. So the question this answers is not
 * whether the new path works — `SurfaceCaptureTest` covers that — but whether an app that has no
 * surface in it still takes the old one, and what the check costs it to find that out.
 *
 * Worth a test of its own because the failure would be quiet and expensive rather than visible: a
 * screen wrongly routed to `PixelCopy` still produces a correct frame, it just produces it through
 * the compositor, on a second thread, with an intermediate buffer per window. Nothing would look
 * wrong; the SDK would simply cost every customer more than it used to.
 */
@RunWith(AndroidJUnit4::class)
class NativeCaptureUnaffectedTest {

    private companion object {
        const val TAG = "NativeCaptureUnaffected"

        /** Roughly what a real screen nests to, so the detection walk is measured against one. */
        const val DEPTH = 12
        const val BREADTH = 6
    }

    @Test
    fun a_screen_with_no_surface_still_takes_the_software_draw() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(deepHierarchy(activity))
            }
            Thread.sleep(500)

            val drawing = ScreenDrawing()
            val frame = capture(scenario, drawing)

            assertNotNull("no frame came back for an ordinary screen", frame)
            assertFalse(
                "an ordinary screen was routed onto the surface path, which costs the compositor " +
                    "and an extra buffer for nothing",
                drawing.lastWindowHostedSurface,
            )
            // The content is still there, which is the half that would be noticed.
            assertEquals(
                "the captured frame does not hold what the screen was painted",
                Color.RED,
                frame!!.getPixel(frame.width / 2, frame.height / 2),
            )
        }
    }

    @Test
    fun a_screen_that_does_hold_a_surface_is_recognised() {
        // The other side of the same check. Without this, the test above would keep passing if the
        // detection stopped detecting anything at all.
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = deepHierarchy(activity)
                // Buried, not at the top: the walk has to reach it the way it would reach a video
                // player three layouts down a real screen.
                (root.findViewById<ViewGroup>(DEPTH)).addView(
                    SurfaceView(activity),
                    FrameLayout.LayoutParams(200, 200),
                )
                activity.setContentView(root)
            }
            Thread.sleep(500)

            val drawing = ScreenDrawing()
            capture(scenario, drawing)

            assertTrue(
                "a screen holding a SurfaceView was not recognised, so its surface would be " +
                    "captured as a transparent hole and encoded as black",
                drawing.lastWindowHostedSurface,
            )
        }
    }

    @Test
    fun what_the_detection_costs_every_capture() {
        // Reported rather than asserted: a threshold here would pin this machine's speed. What it
        // is here to answer is whether a walk added to every capture of every app is a rounding
        // error against the capture, or a tax worth removing.
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(deepHierarchy(activity))
            }
            Thread.sleep(500)

            var views = 0
            var micros = 0.0
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                views = count(root)

                repeat(20) { hostsSurface(root) }

                val samples = 200
                val started = System.nanoTime()
                repeat(samples) { hostsSurface(root) }
                micros = (System.nanoTime() - started) / 1_000.0 / samples
            }

            Log.i(
                TAG,
                "surface detection over $views views = ${"%.1f".format(micros)}us per capture",
            )
            assertTrue("nothing was measured", views > 0)
        }
    }

    /**
     * The same walk `ScreenDrawing` runs, reimplemented here rather than exposed.
     *
     * Widening a private function to internal so a test can time it would be the test changing the
     * thing it measures. This is six lines and its shape is asserted by the two tests above, which
     * use the real one.
     */
    private fun hostsSurface(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view is SurfaceView || view is android.view.TextureView) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hostsSurface(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun count(view: View): Int {
        var total = 1
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) total += count(view.getChildAt(index))
        }
        return total
    }

    /** A hierarchy with the depth and breadth of a real screen, painted one flat colour. */
    private fun deepHierarchy(activity: ComponentActivity): ViewGroup {
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.RED) }
        var parent: ViewGroup = root
        for (level in 1..DEPTH) {
            val next = LinearLayout(activity).apply {
                id = level
                orientation = LinearLayout.VERTICAL
            }
            for (sibling in 1 until BREADTH) {
                next.addView(TextView(activity).apply { text = "row $level.$sibling" })
            }
            parent.addView(
                next,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            parent = next
        }
        return root
    }

    private fun capture(
        scenario: ActivityScenario<ComponentActivity>,
        drawing: ScreenDrawing,
    ): Bitmap? {
        var frame: Bitmap? = null
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            drawing.captureToBitmapAsync(
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
