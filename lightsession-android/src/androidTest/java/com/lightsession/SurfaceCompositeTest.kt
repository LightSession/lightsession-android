package com.lightsession

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.replay.ScreenDrawing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a capture taken through PixelCopy contains the dialog that is on screen.
 *
 * `PixelCopy` reads one window, and a Compose `Dialog` is a window of its own, so the copy
 * used to come back as the screen *behind* the dialog. What made that worth a test rather
 * than a comment is how it failed: masking walks every window, so the dialog's text was
 * covered on an image the dialog was absent from, and the result looked like a properly
 * masked screen instead of a missing one. Nothing about the frame said it was wrong.
 *
 * Colours are the assertion, not a screenshot. The Activity is one flat colour and the
 * dialog another, so "is the dialog in the frame" becomes a pixel read, and "is the
 * Activity still under it" becomes a second one — which is the half a naive fix would
 * break by replacing the frame with only the dialog.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.SurfaceCompositeTest
 */
@RunWith(AndroidJUnit4::class)
class SurfaceCompositeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "SurfaceComposite"

        /** Distinct, saturated, and nothing either theme would produce on its own. */
        val BEHIND = ComposeColor(0xFF00FF00) // green
        val DIALOG = ComposeColor(0xFFFF00FF) // magenta
    }

    /**
     * Forces the surface path, which is the one under test.
     *
     * The software draw would composite the windows by itself; the whole point here is the
     * fallback taken by any app whose content holds a hardware bitmap — which is most of
     * them, since that is what Coil and Glide decode into by default.
     *
     * The Activity's window is passed in rather than found. There is no route from a root
     * view to it on a current Android — the decor view's context is a `DecorContext` over
     * the Application, and Curtains' `phoneWindow` reflects on a field API 36 no longer
     * exposes — so in the app it comes from the Activity the screen mapper tracks, and here
     * it comes from the test rule.
     */
    /**
     * Blocks until the window has actually put a frame on screen.
     *
     * `compose.waitForIdle()` is not this. It waits for the composition and its recompositions
     * to go quiet, which says the tree is settled — not that a traversal has run and produced
     * pixels. Every assertion in this file reads pixels, so the gap between the two is the
     * whole correctness of the fixture.
     *
     * It never showed locally: on a fast emulator the draw lands inside the same idle wait.
     * On CI, where this suite takes seven times as long, the first test in the class captured
     * a window that had settled but not drawn, and read a colour belonging to nobody — the
     * three sampled corners all came back `#9E9E9E` against the green the screen was painted.
     * That reads as "the capture lost the screen", which is a bug report against the SDK for
     * something the fixture never did.
     */
    private fun awaitDraw() {
        val decor = compose.activity.window.decorView
        val drawn = CountDownLatch(1)
        compose.runOnUiThread {
            decor.viewTreeObserver.addOnDrawListener(
                object : android.view.ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        // Posted, not removed inline: the observer is iterating its listeners.
                        decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
                        drawn.countDown()
                    }
                }
            )
            decor.invalidate()
        }
        assertTrue(
            "the screen never drew: ${decor.width}x${decor.height}, shown=${decor.isShown}",
            drawn.await(20, TimeUnit.SECONDS),
        )
    }

    private fun captureViaSurface(drawing: ScreenDrawing): Bitmap? {
        ScreenDrawing::class.java.getDeclaredField("surfaceCaptureRequired").apply {
            isAccessible = true
            setBoolean(drawing, true)
        }
        var result: Bitmap? = null
        val done = CountDownLatch(1)
        compose.runOnUiThread {
            drawing.captureToBitmapAsync(1.0f, compose.activity.window) { bitmap ->
                result = bitmap
                done.countDown()
            }
        }
        assertTrue("the capture never came back", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun Bitmap.at(fractionX: Float, fractionY: Float): Int =
        getPixel((width * fractionX).toInt(), (height * fractionY).toInt())

    private fun describe(color: Int) =
        "#%02X%02X%02X".format(Color.red(color), Color.green(color), Color.blue(color))

    @Test
    fun a_dialog_is_in_the_frame_and_the_screen_is_still_behind_it() {
        var open by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(BEHIND))
            if (open) {
                Dialog(onDismissRequest = { open = false }) {
                    Box(Modifier.size(240.dp).background(DIALOG))
                }
            }
        }
        compose.waitForIdle()
        awaitDraw()

        val drawing = ScreenDrawing()

        val withoutDialog = captureViaSurface(drawing)
        assertNotNull("the plain screen should capture", withoutDialog)
        Log.i(TAG, "no dialog, centre = ${describe(withoutDialog!!.at(0.5f, 0.5f))}")
        assertEquals(
            "the Activity's own colour should be there to begin with",
            Color.GREEN,
            withoutDialog.at(0.5f, 0.5f),
        )

        open = true
        compose.waitForIdle()
        // The dialog animates in; the copy has to happen after it has actually drawn.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        awaitDraw()

        val withDialog = captureViaSurface(drawing)
        assertNotNull("the capture should not be dropped because a dialog is open", withDialog)
        val centre = withDialog!!.at(0.5f, 0.5f)
        // Above the dialog and below the status bar. Not a corner: the first attempt sampled
        // 6% down, which is inside the status bar, and read its grey rather than the screen.
        val above = withDialog.at(0.5f, 0.15f)
        Log.i(TAG, "dialog open, centre = ${describe(centre)}, above = ${describe(above)}")

        assertEquals("the dialog's pixels have to be in the frame", Color.MAGENTA, centre)
        // The other half: a fix that copied only the dialog would pass the line above.
        assertEquals("and the screen underneath has to survive", Color.GREEN, above)

        drawing.release()
    }

    /** With no dialog open the frame is the screen, which is the path nearly every frame takes. */
    @Test
    fun a_plain_screen_still_captures_whole() {
        compose.setContent { Box(Modifier.fillMaxSize().background(BEHIND)) }
        compose.waitForIdle()
        awaitDraw()

        val drawing = ScreenDrawing()
        val frame = captureViaSurface(drawing)
        assertNotNull(frame)
        for ((x, y) in listOf(0.1f to 0.1f, 0.5f to 0.5f, 0.9f to 0.8f)) {
            assertEquals("every corner should be the screen", Color.GREEN, frame!!.at(x, y))
        }
        drawing.release()
    }
}
