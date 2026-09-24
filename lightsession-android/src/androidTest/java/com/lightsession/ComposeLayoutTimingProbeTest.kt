package com.lightsession

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.masking.MaskScanner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When a Compose move becomes visible to the mask scan: inside `OnDrawListener`, or only after it.
 *
 * The capture's mid-copy check compares each frame drawn during the copy against the plan, and
 * where it reads the frame's geometry decides whether it can see a move at all. A probe rather
 * than a test of the SDK: it measures the platform and the toolkit, the premise the check is
 * built on, so that premise is measured rather than remembered.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.ComposeLayoutTimingProbeTest
 */
@RunWith(AndroidJUnit4::class)
class ComposeLayoutTimingProbeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var shift by mutableIntStateOf(0)

    @Test
    fun a_compose_move_is_laid_out_after_the_draw_listener_runs() {
        ScreenGeometry.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(ComposeColor.White)) {
                Text("probe", Modifier.offset { IntOffset(0, shift) }, color = ComposeColor.Black)
            }
        }
        compose.waitForIdle()

        val decor = compose.activity.window.decorView
        val scanner = MaskScanner()
        val handler = Handler(Looper.getMainLooper())
        fun top(): Int = scanner.scan(decor, true, false).first().top

        var before = 0
        var duringDraw: Int? = null
        var afterDraw: Int? = null
        val done = CountDownLatch(1)
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener = ViewTreeObserver.OnDrawListener {
            if (duringDraw != null) return@OnDrawListener
            duringDraw = top()
            handler.postAtFrontOfQueue {
                afterDraw = top()
                handler.post { decor.viewTreeObserver.removeOnDrawListener(listener) }
                done.countDown()
            }
        }
        compose.runOnUiThread {
            before = top()
            decor.viewTreeObserver.addOnDrawListener(listener)
            shift = 200
        }
        assertTrue("no frame was drawn after the move", done.await(10, TimeUnit.SECONDS))

        Log.i(
            "ComposeLayoutTiming",
            "before $before, inside the draw listener $duringDraw, right after the frame $afterDraw",
        )
        assertEquals("the scan right after the frame sees the move", before + 200, afterDraw)
        assertEquals(
            "inside the draw listener the frame is not laid out yet, so a scan there reads the " +
                "frame before it",
            before,
            duringDraw,
        )
    }
}
