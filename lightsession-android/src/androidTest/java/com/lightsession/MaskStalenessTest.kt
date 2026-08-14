package com.lightsession

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.replay.ScreenDrawing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.lightsession.masking.Masking

/**
 * A frame whose masks and pixels describe different moments must not ship.
 *
 * ## The race this guards
 *
 * On the surface path the two halves of a masked capture come from different instants: `PixelCopy`
 * reads a surface the compositor already produced, while the mask rectangles are read from live
 * Views. If the screen draws in between — a list mid-fling — the rectangles describe a layout the
 * copied pixels do not have, and the text they were meant to cover is left in plain view.
 *
 * The scan was moved ahead of the copy so the two are nearly simultaneous, but "nearly" is not a
 * guarantee. This covers what is left: a draw inside the window means the frame is withheld.
 *
 * ## Why this counts instead of asserting once
 *
 * Whether a draw lands inside the window is a race, and it stays a race however the fixture is
 * built — because the window is genuinely small. Measured while writing this: 10 to 26 ms from
 * arming to the decision, of which the scan holds the main thread and so *prevents* drawing; the
 * part a draw can land in is the copy alone. Draws arrive on the vsync, every 16 ms. So a single
 * capture over a moving screen is withheld perhaps a third of the time.
 *
 * Two earlier fixtures failed on exactly that. A Compose `infiniteRepeatable` never drew at all —
 * under `createAndroidComposeRule` the frame clock is the test's and does not advance during a
 * sleep. A pump reposting `invalidate()` every 8 ms drew, but mostly outside the window: green
 * alone, red inside the suite. Widening the window by making the scan heavier does not help, since
 * that is the half that blocks the main thread.
 *
 * So this asks the question the mechanism can actually answer: over many captures of a screen that
 * keeps drawing, is *any* of them withheld — and over a screen that is still, is *none*. That the
 * guard fires sometimes rather than always is not a weakness in the test. It is the measurement:
 * what is left to catch after the scan moved ahead of the copy is a fraction of frames, not most
 * of them.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.MaskStalenessTest
 */
@RunWith(AndroidJUnit4::class)
class MaskStalenessTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "MaskStaleness"

        /**
         * Text nodes on the busy screen.
         *
         * Chosen for the *scan* cost, not for realism: each one is a rectangle `planMasks` has to
         * find, and that traversal is what the watched window mostly consists of.
         */
        const val BUSY_ROWS = 400

        /**
         * Captures per case.
         *
         * Sixty because twenty was not enough. Measured over four runs, a screen drawing
         * continuously had 13, 17, 29 and 30 of 60 captures withheld — so the rate is somewhere
         * around a quarter to a half, and at twenty attempts a clean run would have failed
         * outright about one time in ten. At sixty, missing every one is a millionth-order event.
         *
         * The still screen was 0 of 60 on all four, which is the other half of the claim: the
         * guard does not fire on its own.
         */
        const val ATTEMPTS = 60
    }

    private val defaults = Triple(Masking.text, Masking.images, Masking.debugHighlight)

    @Before
    fun setUp() {
        ScreenGeometry.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        // The guard only runs when there is something to protect, which is the default.
        Masking.text = true
    }

    @After
    fun restore() {
        Masking.text = defaults.first
        Masking.images = defaults.second
        Masking.debugHighlight = defaults.third
    }

    /** Forces the path under test; the software draw masks and copies in one atomic pass. */
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

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }

    @Test
    fun a_still_screen_still_captures() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(ComposeColor.White)) {
                Text("nothing here moves", color = ComposeColor.Black)
            }
        }
        compose.waitForIdle()

        val drawing = ScreenDrawing()
        var withheld = 0
        repeat(ATTEMPTS) {
            val frame = captureViaSurface(drawing)
            if (frame == null) withheld++ else drawing.recycleBitmap(frame)
        }
        drawing.release()

        Log.i(TAG, "still screen: $withheld of $ATTEMPTS withheld")
        assertEquals(
            "a screen that is not drawing had frames withheld, so the guard fires on its own",
            0,
            withheld,
        )
    }

    @Test
    fun a_screen_drawing_mid_capture_is_withheld() {
        // Heavy on purpose. The window being watched runs from arming to the decision, and most
        // of it is `planMasks` walking the semantics tree — so a screen with hundreds of text
        // nodes makes that window wide enough for a draw to land in it every time.
        //
        // Without this the test raced: it passed alone and failed inside the suite, because a
        // window of ~10 ms and a pump at 8 ms mostly miss each other. Widening the window is the
        // honest fix; loosening the assertion would have been the other kind.
        compose.setContent {
            Column(Modifier.fillMaxSize().background(ComposeColor.White)) {
                repeat(BUSY_ROWS) { row ->
                    Text("row $row is text and therefore gets a rectangle", fontSize = 5.sp)
                }
            }
        }
        compose.waitForIdle()

        val decor = compose.activity.window.decorView
        val handler = Handler(Looper.getMainLooper())
        val pumping = AtomicBoolean(true)
        // Counted here as well, so a failure says which half broke: no draws at all means the
        // fixture, draws that the SDK did not notice means the guard.
        val draws = java.util.concurrent.atomic.AtomicInteger(0)
        val counter = android.view.ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
        val pump = object : Runnable {
            override fun run() {
                if (!pumping.get()) return
                decor.invalidate()
                handler.postDelayed(this, 1)
            }
        }

        val drawing = ScreenDrawing()
        compose.runOnUiThread {
            decor.viewTreeObserver.addOnDrawListener(counter)
            handler.post(pump)
        }
        var withheld = 0
        try {
            repeat(ATTEMPTS) {
                val frame = captureViaSurface(drawing)
                if (frame == null) withheld++ else drawing.recycleBitmap(frame)
            }
        } finally {
            pumping.set(false)
            compose.runOnUiThread { decor.viewTreeObserver.removeOnDrawListener(counter) }
            drawing.release()
        }

        Log.i(
            TAG,
            "drawing screen: $withheld of $ATTEMPTS withheld, " +
                "over ${draws.get()} draw(s) from the fixture",
        )
        assertTrue(
            "the screen drew ${draws.get()} times and not one of $ATTEMPTS captures was withheld; " +
                "a frame whose masks and pixels describe different moments is shipping",
            withheld > 0,
        )
    }
}
