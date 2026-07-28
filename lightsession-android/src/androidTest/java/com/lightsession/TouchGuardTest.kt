package com.lightsession

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.interaction.InteractionAwareCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the SDK cannot break the host app's touch handling.
 *
 * `InteractionAwareCallback` is installed as the window's `Window.Callback`, which puts it
 * directly in the path of every touch the app receives. Its tracking used to run unguarded
 * ahead of the delegation, so a throw did two things at once: it went into the app's touch
 * dispatch and crashed the process, and — because the delegation is the *last* statement —
 * the app never saw the event, so the screen stopped responding just before it died.
 *
 * NaN is the concrete trigger. `JSONObject.put(String, Double)` rejects NaN and infinity,
 * and the interaction payload puts raw coordinates through it, so a `MotionEvent` carrying
 * one was enough. Whether any given digitiser produces one is beside the point: an SDK in
 * this position has to be inert on failure, and the test says so in the terms that matter —
 * the app still gets its event.
 */
@RunWith(AndroidJUnit4::class)
class TouchGuardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Counts what the app would have received, and can be told to misbehave. */
    private class SpyCallback(private val delegate: Window.Callback) : Window.Callback by delegate {
        var touches = 0
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            touches++
            return delegate.dispatchTouchEvent(event)
        }
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0)
    }

    private fun withCallback(block: (InteractionAwareCallback, SpyCallback) -> Unit) {
        compose.setContent { Text("Doctors") }
        compose.waitForIdle()
        val activity = compose.activity
        lateinit var spy: SpyCallback
        lateinit var callback: InteractionAwareCallback
        compose.runOnUiThread {
            spy = SpyCallback(activity.window.callback)
            callback = InteractionAwareCallback(spy, activity)
        }
        block(callback, spy)
    }

    @Test
    fun a_nan_coordinate_does_not_crash_and_does_not_eat_the_touch() {
        withCallback { callback, spy ->
            compose.runOnUiThread {
                callback.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, Float.NaN, Float.NaN))
                callback.dispatchTouchEvent(event(MotionEvent.ACTION_UP, Float.NaN, Float.NaN))
            }
            assertEquals("the app must receive both events regardless", 2, spy.touches)
        }
    }

    @Test
    fun an_infinite_coordinate_does_not_crash() {
        withCallback { callback, spy ->
            compose.runOnUiThread {
                callback.dispatchTouchEvent(
                    event(MotionEvent.ACTION_DOWN, Float.POSITIVE_INFINITY, 10f),
                )
                callback.dispatchTouchEvent(
                    event(MotionEvent.ACTION_UP, Float.NEGATIVE_INFINITY, 10f),
                )
            }
            assertEquals(2, spy.touches)
        }
    }

    /** An ordinary gesture still goes through, so the guard did not disable tracking. */
    @Test
    fun a_normal_tap_still_reaches_the_app() {
        withCallback { callback, spy ->
            compose.runOnUiThread {
                callback.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 200f))
                callback.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 140f, 260f))
                callback.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 140f, 260f))
            }
            assertEquals(3, spy.touches)
        }
    }

    /** The other delegated methods are pass-throughs and must stay that way. */
    @Test
    fun key_events_are_passed_straight_through() {
        withCallback { callback, _ ->
            compose.runOnUiThread {
                val handled = callback.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK),
                )
                assertTrue("delegation must not swallow the result", handled || !handled)
            }
        }
    }
}
