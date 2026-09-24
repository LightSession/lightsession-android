package com.lightsession

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.masking.Masking
import com.lightsession.replay.ScreenDrawing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Compose image inside a classic View that scrolls is covered where it is, not where it was.
 *
 * Compose images are found by walking the composition, which is expensive, so the answer is cached
 * and a snapshot apply — any state write — clears it. A Compose host moved by a classic View writes
 * no state: a `ComposeView` in a `ScrollView` or a `RecyclerView` row moves with its parent's
 * scroll while its composition stays exactly as it was. Nothing clears the cache then, and the
 * rectangles it holds are in window space, so they would stay where the image was.
 *
 * The image is solid red on a white page and masking turns it grey, so a red pixel in a capture
 * is an uncovered image.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.ComposeImageInScrollingViewTest
 */
@RunWith(AndroidJUnit4::class)
class ComposeImageInScrollingViewTest {

    private val defaults = Triple(Masking.text, Masking.images, Masking.debugHighlight)

    @Before
    fun setUp() {
        ScreenGeometry.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        Masking.text = false
        Masking.images = true
        Masking.debugHighlight = false
    }

    @After
    fun restore() {
        Masking.text = defaults.first
        Masking.images = defaults.second
        Masking.debugHighlight = defaults.third
    }

    @Test
    fun an_image_scrolled_by_a_classic_view_is_covered_where_it_is() = scrolled(surface = true)

    /** The software draw reads the same rectangles, and a native app with no surface takes it. */
    @Test
    fun an_image_scrolled_by_a_classic_view_is_covered_where_it_is_on_the_software_path() =
        scrolled(surface = false)

    /**
     * A host resized by its parent lays its composition out again with no state written, and a
     * centred image moves by half the change — not by the host's own movement, which is nothing.
     */
    @Test
    fun an_image_its_host_resized_is_covered_where_it_is() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var host: ComposeView
            scenario.onActivity { activity ->
                host = ComposeView(activity).apply {
                    setContent {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Image(
                                painter = ColorPainter(ComposeColor.Red),
                                contentDescription = null,
                                modifier = Modifier.size(120.dp),
                            )
                        }
                    }
                }
                activity.setContentView(
                    LinearLayout(activity).apply {
                        setBackgroundColor(Color.WHITE)
                        addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200))
                    },
                )
            }
            settle()
            val (before, after) = captureAround(scenario, surface = true) {
                host.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600)
            }
            Log.i("ComposeImageScroll", "resized: red pixels before $before, after $after")
            assertEquals("the image was not covered even before its host changed", 0, before)
            assertEquals(
                "$after pixels of the image were left in the clear after its host was resized",
                0,
                after,
            )
        }
    }

    private fun scrolled(surface: Boolean) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var scroll: ScrollView
            scenario.onActivity { activity ->
                val page = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                    addView(View(activity), LinearLayout.LayoutParams(1, 600))
                    addView(
                        ComposeView(activity).apply {
                            setContent {
                                Image(
                                    painter = ColorPainter(ComposeColor.Red),
                                    contentDescription = null,
                                    modifier = Modifier.size(160.dp),
                                )
                            }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(View(activity), LinearLayout.LayoutParams(1, 4000))
                }
                scroll = ScrollView(activity).apply {
                    setBackgroundColor(Color.WHITE)
                    addView(page)
                }
                activity.setContentView(scroll)
            }
            settle()
            // The classic scroll moves the host; the composition inside it does not change.
            val (before, after) = captureAround(scenario, surface) { scroll.scrollBy(0, 400) }
            Log.i(
                "ComposeImageScroll",
                "${if (surface) "surface" else "software"}: red pixels before the scroll $before, " +
                    "after $after",
            )
            assertEquals("the image was not covered even before it moved", 0, before)
            assertEquals(
                "$after pixels of the image were left in the clear after its host scrolled — " +
                    "the mask stayed where the image was",
                0,
                after,
            )
        }
    }

    /** Red pixels in a capture, then [change] on the main thread, then red pixels again. */
    private fun captureAround(
        scenario: ActivityScenario<ComponentActivity>,
        surface: Boolean,
        change: () -> Unit,
    ): Pair<Int, Int> {
        val drawing = ScreenDrawing()
        try {
            val before = capture(scenario, drawing, surface)
            assertTrue("the first capture never came back", before != null)
            val redBefore = red(before!!)
            drawing.recycleBitmap(before)

            scenario.onActivity { change() }
            settle()

            val after = capture(scenario, drawing, surface)
            assertTrue("the second capture never came back", after != null)
            val redAfter = red(after!!)
            drawing.recycleBitmap(after)
            return redBefore to redAfter
        } finally {
            drawing.release()
        }
    }

    private fun settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)
    }

    /**
     * One capture. [surface] forces the surface path, where the plan and the copy are separate —
     * the path every Flutter app takes; otherwise this screen, which has no surface, is drawn in
     * software.
     */
    private fun capture(
        scenario: ActivityScenario<ComponentActivity>,
        drawing: ScreenDrawing,
        surface: Boolean,
    ): Bitmap? {
        ScreenDrawing::class.java.getDeclaredField("surfaceCaptureRequired").apply {
            isAccessible = true
            setBoolean(drawing, surface)
        }
        var result: Bitmap? = null
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            drawing.captureToBitmapAsync(1.0f, activity.window) { bitmap ->
                result = bitmap
                done.countDown()
            }
        }
        assertTrue("the capture never came back", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun red(frame: Bitmap): Int {
        val row = IntArray(frame.width)
        var count = 0
        for (y in 0 until frame.height) {
            frame.getPixels(row, 0, frame.width, 0, y, frame.width, 1)
            for (pixel in row) {
                if (Color.red(pixel) > 200 && Color.green(pixel) < 60 && Color.blue(pixel) < 60) count++
            }
        }
        return count
    }
}
