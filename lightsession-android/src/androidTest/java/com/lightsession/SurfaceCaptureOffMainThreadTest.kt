package com.lightsession

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.replay.ScreenDrawing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A surface capture asked for from a thread with no looper still comes back.
 *
 * The wireframe's recolour asks from the worker that scanned the screen. The surface path waits for
 * a frame through the `Choreographer`, which exists only on a looper thread, and asking for it from
 * the worker threw — so on a Flutter app, where the surface path is the only one, every wireframe
 * failed to send.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.SurfaceCaptureOffMainThreadTest
 */
@RunWith(AndroidJUnit4::class)
class SurfaceCaptureOffMainThreadTest {

    @Before
    fun setUp() {
        ScreenGeometry.attach(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun a_surface_capture_asked_for_off_the_main_thread_comes_back() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(TextView(activity).apply {
                    text = "hello"
                    setBackgroundColor(Color.WHITE)
                })
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(500)

            val drawing = ScreenDrawing()
            ScreenDrawing::class.java.getDeclaredField("surfaceCaptureRequired").apply {
                isAccessible = true
                setBoolean(drawing, true)
            }
            var window: android.view.Window? = null
            scenario.onActivity { window = it.window }

            var result: Bitmap? = null
            var thrown: Throwable? = null
            val done = CountDownLatch(1)
            // A plain thread, as the worker is: no looper of its own.
            Thread {
                try {
                    drawing.captureToBitmapAsync(1.0f, window) { bitmap ->
                        result = bitmap
                        done.countDown()
                    }
                } catch (error: Throwable) {
                    thrown = error
                    done.countDown()
                }
            }.start()

            try {
                assertTrue("the capture never came back", done.await(10, TimeUnit.SECONDS))
                assertNull("asking from a thread with no looper threw", thrown)
                assertNotNull("the capture came back empty", result)
                drawing.recycleBitmap(result!!)
            } finally {
                drawing.release()
            }
        }
    }
}
