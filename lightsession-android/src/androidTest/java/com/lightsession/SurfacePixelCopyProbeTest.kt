package com.lightsession

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which `PixelCopy` overload can actually see content painted into a surface.
 *
 * A capture of a Flutter screen came back solid black — not an error, not a dropped frame, a
 * black JPEG delivered as if it were the screen. The software draw cannot see surface content at
 * all, which is understood and is why such a window goes to `PixelCopy` instead. What is not
 * obvious is that going to `PixelCopy` is not sufficient on its own: the overload matters.
 *
 * `PixelCopy.request(Window, …)` copies the window's own surface. A `SurfaceView` does not draw
 * into that surface — it punches a hole through it and publishes its pixels on a **separate**
 * layer that `SurfaceFlinger` composites underneath. So a window copy of a screen whose content
 * is all surface returns the hole: the window's background, with nothing in it.
 *
 * `PixelCopy.request(SurfaceView, …)` reads that separate layer directly.
 *
 * This test settles which of those two describes the platform, on a surface it paints itself, so
 * the answer does not depend on a toolkit being installed. The colour is the assertion: the
 * surface is painted one colour and the window behind it another, so "did the copy see the
 * surface" is a pixel read rather than a picture to look at.
 */
@RunWith(AndroidJUnit4::class)
class SurfacePixelCopyProbeTest {

    private companion object {
        /** What the surface is painted. Nothing else on screen is this colour. */
        const val SURFACE_COLOUR = Color.RED

        /** What the window behind it is painted, so a hole reads as this rather than as nothing. */
        const val WINDOW_COLOUR = Color.BLUE

        const val TAG = "SurfacePixelCopyProbe"
    }

    @Test
    fun which_pixelcopy_overload_sees_content_painted_into_a_surface() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var surfaceView: SurfaceView
            val painted = CountDownLatch(1)

            scenario.onActivity { activity ->
                activity.window.decorView.setBackgroundColor(WINDOW_COLOUR)

                surfaceView = SurfaceView(activity)
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // Straight into the surface, which is what a rendering toolkit does and
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
            // The post has to reach the compositor before either copy can read it.
            Thread.sleep(1_000)

            val fromWindow = copyFromWindow(scenario, surfaceView)
            val fromSurfaceView = copyFromSurfaceView(surfaceView)

            Log.i(
                TAG,
                "window copy centre = ${hex(fromWindow)}, " +
                    "surfaceview copy centre = ${hex(fromSurfaceView)}, " +
                    "surface painted ${hex(SURFACE_COLOUR)}, window ${hex(WINDOW_COLOUR)}",
            )

            // The finding, asserted rather than printed: reading the SurfaceView directly is the
            // only one of the two that returns the content. If this ever starts failing because
            // the window copy also sees it, the extra path in `ScreenDrawing` can be deleted.
            assertEquals(
                "PixelCopy from a SurfaceView did not return what was painted into it, so the " +
                    "premise of the surface capture path is wrong",
                SURFACE_COLOUR,
                fromSurfaceView,
            )
            assertTrue(
                "PixelCopy from the Window returned the surface's content (${hex(fromWindow)}), " +
                    "which contradicts the reason ScreenDrawing copies surfaces separately",
                fromWindow != SURFACE_COLOUR,
            )
        }
    }

    /** The centre pixel of a window copy, the way `ScreenDrawing` takes one. */
    private fun copyFromWindow(
        scenario: ActivityScenario<ComponentActivity>,
        surfaceView: SurfaceView,
    ): Int {
        var result = 0
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val destination = Bitmap.createBitmap(
                surfaceView.width.coerceAtLeast(1),
                surfaceView.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            PixelCopy.request(
                activity.window,
                destination,
                { status ->
                    result = if (status == PixelCopy.SUCCESS) {
                        destination.getPixel(destination.width / 2, destination.height / 2)
                    } else {
                        Log.w(TAG, "window copy failed with status $status")
                        0
                    }
                    done.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
        }
        assertTrue("the window copy never came back", done.await(10, TimeUnit.SECONDS))
        return result
    }

    /** The centre pixel of a copy taken from the SurfaceView's own layer. */
    private fun copyFromSurfaceView(surfaceView: SurfaceView): Int {
        var result = 0
        val done = CountDownLatch(1)
        val destination = Bitmap.createBitmap(
            surfaceView.width.coerceAtLeast(1),
            surfaceView.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        PixelCopy.request(
            surfaceView,
            destination,
            { status ->
                result = if (status == PixelCopy.SUCCESS) {
                    destination.getPixel(destination.width / 2, destination.height / 2)
                } else {
                    Log.w(TAG, "surfaceview copy failed with status $status")
                    0
                }
                done.countDown()
            },
            Handler(Looper.getMainLooper()),
        )
        assertTrue("the surfaceview copy never came back", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun hex(colour: Int): String = String.format("#%08X", colour)
}
