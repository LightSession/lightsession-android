package com.lightsession

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.replay.ScreenDrawing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a capture of a screen painted into a surface contains the screen.
 *
 * The bug this exists for shipped a **solid black frame** and called it a capture. A
 * surface-rendering toolkit — Flutter, a video player, a map, a camera preview — publishes its
 * pixels on a layer of its own, and neither of the two capture paths could see it:
 *
 *  * The software draw walks Views and calls `draw(canvas)`. A `SurfaceView` contributes nothing
 *    there, and it does not fail while contributing nothing — no exception, so the
 *    hardware-bitmap latch that exists for the other unreadable case never fired.
 *  * `PixelCopy` from the **Window** reads the window's own surface, which carries a transparent
 *    hole where the `SurfaceView` sits. Measured in `SurfacePixelCopyProbeTest`: `#00000000`.
 *    JPEG has no alpha channel, so that hole encodes as black.
 *
 * Both failures are silent, and that is what made them expensive: the frames arrived, the session
 * rendered, and the replay was a black rectangle with a touch heatmap drawn on it.
 *
 * The assertion is a colour rather than a picture. The surface is painted one colour that nothing
 * else on screen uses, so "did the capture see the surface" is a pixel read.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceCaptureTest {

    private companion object {
        const val SURFACE_COLOUR = Color.RED
        const val CAPTURE_TIMEOUT_SECONDS = 15L
    }

    @Test
    fun a_screen_painted_into_a_surface_is_captured_rather_than_coming_back_black() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val painted = CountDownLatch(1)

            scenario.onActivity { activity ->
                val surfaceView = SurfaceView(activity)
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // Into the surface directly, which is what a rendering toolkit does and
                        // what no View draw pass can observe.
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

            assertTrue(
                "the surface was never created, so nothing below proves anything",
                painted.await(10, TimeUnit.SECONDS),
            )
            // The posted buffer has to reach the compositor before a copy can read it.
            Thread.sleep(1_000)

            val captured = captureThroughScreenDrawing(scenario)
            assertNotNull("no frame came back at all", captured)

            val centre = captured!!.getPixel(captured.width / 2, captured.height / 2)
            assertEquals(
                "the capture did not contain what was painted into the surface — a frame like " +
                    "this is the black rectangle the surface capture path exists to prevent",
                SURFACE_COLOUR,
                centre,
            )
            // Opaque, explicitly: a transparent frame survives this far and only turns black at
            // the JPEG encode, which is exactly how the original bug stayed invisible.
            assertEquals(
                "the frame is transparent where the surface is, which encodes as black",
                255,
                Color.alpha(centre),
            )
        }
    }

    /** One frame, taken the way the recorder takes one. */
    private fun captureThroughScreenDrawing(
        scenario: ActivityScenario<ComponentActivity>,
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
        }
        assertTrue(
            "the capture never came back",
            done.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return frame
    }
}
