package com.lightsession

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.replay.ScreenDrawing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether a moving screen actually leaks text through its masks, counted in pixels.
 *
 * ## Why this exists apart from `MaskStalenessTest`
 *
 * That one proves the guard fires. This one asks the question underneath it: was there anything to
 * guard *against*. The mechanism — pixels read at one instant, mask geometry at another — was
 * established by reading the code, and the window was measured, but nobody had produced a frame
 * with readable text on it. An argument for a privacy fix that has never seen the leak it prevents
 * is an argument, not evidence.
 *
 * ## How a leak is recognised
 *
 * The screen is black text on white and nothing else. Masking turns every text rectangle into
 * `#9E9E9E`, which is mid-grey. So in a correctly masked capture there is no dark pixel anywhere
 * in the content area: text is either covered, or the frame did not ship. A dark pixel *is* an
 * exposed glyph, and counting them needs no judgement about what the picture looks like.
 *
 * The system bars are excluded because they are dark by nature and say nothing about masking.
 *
 * ## Why it moves rather than merely redraws
 *
 * The earlier fixture pumped `invalidate()`, which redraws without moving anything — enough to trip
 * a guard watching for draws, and useless here, because masks drawn over unmoved content still land
 * on it. A leak needs the *positions* to change between the scan and the copy, so this shifts the
 * whole column by a chunk every few milliseconds, which is what a fling does.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.MaskLeakProofTest
 */
@RunWith(AndroidJUnit4::class)
class MaskLeakProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "MaskLeakProof"

        /** How many frames each case looks at. */
        const val ATTEMPTS = 40

        /** Pixels the column jumps each step. Far enough that a mask cannot still overlap. */
        const val STEP = 90

        /** Anything this dark on a white page with grey masks is an uncovered glyph. */
        const val INK_LUMA = 90
    }

    private val defaults = Triple(Masking.text, Masking.images, Masking.debugHighlight)

    @Before
    fun setUp() {
        ScreenGeometry.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        Masking.text = true
        Masking.images = false
        Masking.debugHighlight = false
    }

    @After
    fun restore() {
        Masking.text = defaults.first
        Masking.images = defaults.second
        Masking.debugHighlight = defaults.third
    }

    private var shift by mutableIntStateOf(0)

    private fun content() {
        compose.setContent {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(ComposeColor.White)
                    .offset { IntOffset(0, shift) },
            ) {
                repeat(60) { row ->
                    Text(
                        "row $row carries text that masking is supposed to cover completely",
                        color = ComposeColor.Black,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        compose.waitForIdle()
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

    /** Uncovered glyph pixels in the content area, ignoring the system bars. */
    private fun exposedInk(frame: Bitmap): Int {
        val top = frame.height / 6
        val bottom = frame.height * 5 / 6
        val row = IntArray(frame.width)
        var ink = 0
        for (y in top until bottom) {
            frame.getPixels(row, 0, frame.width, 0, y, frame.width, 1)
            for (pixel in row) {
                if (Color.alpha(pixel) < 250) continue
                val luma =
                    (Color.red(pixel) * 30 + Color.green(pixel) * 59 + Color.blue(pixel) * 11) / 100
                if (luma < INK_LUMA) ink++
            }
        }
        return ink
    }

    /** Captures [ATTEMPTS] frames while the column jumps, and reports the worst leak seen. */
    private fun scanWhileMoving(label: String): Pair<Int, Int> {
        content()
        val handler = Handler(Looper.getMainLooper())
        val moving = AtomicBoolean(true)
        val scroll = object : Runnable {
            override fun run() {
                if (!moving.get()) return
                shift = if (shift <= -STEP * 12) 0 else shift - STEP
                handler.postDelayed(this, 8)
            }
        }

        val drawing = ScreenDrawing()
        var leaked = 0
        var worst = 0
        var withheld = 0
        compose.runOnUiThread { handler.post(scroll) }
        try {
            repeat(ATTEMPTS) {
                val frame = captureViaSurface(drawing)
                if (frame == null) {
                    withheld++
                } else {
                    val ink = exposedInk(frame)
                    if (ink > 0) {
                        leaked++
                        if (ink > worst) {
                            worst = ink
                            runCatching {
                                val out = java.io.File(
                                    InstrumentationRegistry.getInstrumentation()
                                        .targetContext.getExternalFilesDir(null),
                                    "leak-$label.png",
                                )
                                java.io.FileOutputStream(out).use {
                                    frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                                Log.i(TAG, "wrote ${out.absolutePath}")
                            }
                        }
                    }
                    drawing.recycleBitmap(frame)
                }
            }
        } finally {
            moving.set(false)
            drawing.release()
        }

        Log.i(
            TAG,
            "$label: $leaked of $ATTEMPTS frames leaked (worst $worst px), $withheld withheld",
        )
        return leaked to worst
    }

    /**
     * The control. A still screen must mask completely, or the counter is measuring something
     * other than the race — a mask that never covered its text would look identical.
     */
    @Test
    fun a_still_screen_leaks_nothing() {
        content()
        val drawing = ScreenDrawing()
        var leaked = 0
        repeat(ATTEMPTS) {
            val frame = captureViaSurface(drawing)
            if (frame != null) {
                if (exposedInk(frame) > 0) leaked++
                drawing.recycleBitmap(frame)
            }
        }
        drawing.release()
        Log.i(TAG, "still: $leaked of $ATTEMPTS frames leaked")
        assertEquals(
            "a still screen leaked, so masking is broken independently of any race",
            0,
            leaked,
        )
    }

    /** The claim. A screen moving under the capture must not produce a readable frame. */
    @Test
    fun a_moving_screen_leaks_nothing() {
        val (leaked, worst) = scanWhileMoving("moving")
        assertEquals(
            "$leaked of $ATTEMPTS frames came back with uncovered text on them, up to $worst " +
                "pixels of it — the masks describe a layout the pixels do not have",
            0,
            leaked,
        )
    }
}
